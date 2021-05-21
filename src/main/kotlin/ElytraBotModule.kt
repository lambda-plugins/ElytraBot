import com.lambda.client.event.SafeClientEvent
import com.lambda.client.manager.managers.HotbarManager
import com.lambda.client.manager.managers.HotbarManager.serverSideItem
import com.lambda.client.manager.managers.HotbarManager.spoofHotbar
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.mixin.extension.tickLength
import com.lambda.client.mixin.extension.timer
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.MovementUtils.speed
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.items.*
import com.lambda.client.util.math.Direction
import com.lambda.client.util.math.Vec2f
import com.lambda.client.util.math.VectorUtils
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.client.util.math.VectorUtils.toVec3d
import com.lambda.client.util.text.MessageSendHelper.sendChatMessage
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.runSafe
import com.lambda.client.util.threads.runSafeR
import com.lambda.client.util.threads.safeListener
import com.lambda.client.util.world.getGroundPos
import kotlinx.coroutines.launch
import net.minecraft.init.Items
import net.minecraft.init.SoundEvents
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.client.CPacketPlayerTryUseItem
import net.minecraft.util.EnumHand
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraftforge.fml.common.gameevent.TickEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt


internal object ElytraBotModule : PluginModule(
    name = "ElytraBot",
    category = Category.MOVEMENT,
    description = "Baritone like Elytra bot module, credit CookieClient",
    pluginMain = ElytraBotPlugin
) {


    private var path = mutableListOf<BlockPos>()
    var goal: BlockPos? = null
    private var previous: BlockPos? = null
    private var lastSecondPos: BlockPos? = null

    private var jumpY = -1.0
    private var packetsSent = 0
    private var lagbackCounter = 0
    private var useBaritoneCounter = 0
    private var lagback = false
    private var blocksPerSecond = 0.0
    private var blocksPerSecondCounter = 0
    private val blocksPerSecondTimer = TickTimer(TimeUnit.MILLISECONDS)
    private val packetTimer = TickTimer(TimeUnit.MILLISECONDS)
    private val fireworkTimer = TickTimer(TimeUnit.MILLISECONDS)
    private val takeoffTimer = TickTimer(TimeUnit.MILLISECONDS)

    enum class ElytraBotMode {
        Highway, Overworld
    }

    enum class ElytraBotTakeOffMode {
        SlowGlide, Jump
    }

    enum class ElytraBotFlyMode {
        Firework
    }

    var travelMode by setting("Travel Mode", ElytraBotMode.Overworld)
    private var takeoffMode by setting("Takeoff Mode", ElytraBotTakeOffMode.Jump)
    private var elytraMode by setting("Flight Mode", ElytraBotFlyMode.Firework)
    private val highPingOptimize by setting("High Ping Optimize", false)
    private val minTakeoffHeight by setting("Min Takeoff Height", 0.5f, 0.0f..1.5f, 0.1f, { !highPingOptimize })
    private val spoofHotbar by setting("Spoof Hotbar", true)
    private val aStarRadius by setting("AStarRadius", 3f, 1f..10f, 0.5f)
    private val minElytraVelocity by setting("MinElytraVelocity", 1.0, 0.1..5.0, 0.1)
    private val interacting by setting("Rotation Mode", RotationMode.VIEW_LOCK)
//    private val elytraFlySpeed by setting("Elytra Speed", 1f, 0.1f..20.0f, 0.25f, { ElytraMode != ElytraBotFlyMode.Firework })
    private val elytraFlyManeuverSpeed by setting("Maneuver Speed", 1f, 0.0f..10.0f, 0.25f)
    private val fireworkDelay by setting("Firework Delay", 1f, 0.0f..10.0f, 0.25f, { elytraMode == ElytraBotFlyMode.Firework })
//    var pathfinding by setting("Pathfinding", true)
    var avoidLava by setting("AvoidLava", true)
    private var directional by setting("Directional", false)
//    private var toggleOnPop by setting("ToggleOnPop", false)
//    private val maxY by setting("Max Y", 1f, 0.0f..300.0f, 0.25f)

    @Suppress("UNUSED")
    private enum class RotationMode {
        OFF, SPOOF, VIEW_LOCK
    }

    init {
        onEnable {
            runSafeR {
                if (directional) {
                    //Calculate the direction so it will put it to diagonal if the player is on diagonal highway.
                    goal = BlockPos(Direction.fromEntity(player).directionVec.multiply(6942069))
                } else { if (goal == null) {
                        sendChatMessage("You need a goal position")
                        disable()
                    }
                }
                blocksPerSecondTimer.reset()
            }
        }

        onDisable {
            runSafe {
                path = mutableListOf()
                useBaritoneCounter = 0
                lagback = false
                lagbackCounter = 0
                blocksPerSecond = 0.0
                blocksPerSecondCounter = 0
                lastSecondPos = null
                jumpY = -1.0
            }
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (goal == null) {
                disable()
                sendChatMessage("You need a goal position")
                return@safeListener
            }

            //Check if the goal is reached and then stop
            goal?.let {
                if (player.positionVector.distanceTo(it.toVec3d()) < 15) {
                    world.playSound(player.position, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.AMBIENT, 100.0f, 18.0f, true)
                    sendChatMessage("$chatName Goal reached!.")
                    disable()
                    return@safeListener
                }
            }

            //Check if there is an elytra equipped if not then equip it or toggle off if no elytra in inventory
            if (player.inventory.armorInventory[2].item != Items.ELYTRA ||
                isItemBroken(player.inventory.armorInventory[2])) {
                sendChatMessage("$chatName You need an elytra.")
                disable()
                return@safeListener
            }

            //Toggle off if no fireworks while using firework mode
            if (elytraMode == ElytraBotFlyMode.Firework &&
                player.inventorySlots.countItem(Items.FIREWORKS) <= 0) {
                sendChatMessage("You need fireworks as your using firework mode")
                disable()
                return@safeListener
            }

            //Wait still if in unloaded chunk
            if (!world.getChunk(player.position).isLoaded) {
                //setStatus("We are in unloaded chunk. Waiting")
                player.setVelocity(0.0, 0.0, 0.0)
                return@safeListener
            }

            if (!player.isElytraFlying) {
                // if (packetsSent < 20) setStatus("Trying to takeoff")
                fireworkTimer.reset()

                // Jump if on ground
                if (player.onGround) {
//                    jumpY = player.posY
//                    defaultScope.launch {
//                        generatePath()
//                    }
//                    player.jump()
                } else if (player.posY < player.lastTickPosY) {
                    if (takeoffMode == ElytraBotTakeOffMode.SlowGlide) {
                        player.setVelocity(0.0, -0.04, 0.0)
                    } else {
//                        takeoff()
                    }
                }
                return@safeListener
            } else {
                packetsSent = 0

                // If we arent moving anywhere then activate use baritone
                val speed = player.speed

                if (elytraMode == ElytraBotFlyMode.Firework) {
                    // Prevent lagback on 2b2t by not clicking on fireworks. I hope hause would fix hes plugins tho
                    if (speed > 3) {
                        lagback = true
                    }

                    //Remove lagback thing after it stops and click on fireworks again.
                    if (lagback) {
                        if (speed < 1) {
                            lagbackCounter++
                            if (lagbackCounter > 3) {
                                lagback = false
                                lagbackCounter = 0
                            }
                        } else {
                            lagbackCounter = 0
                        }
                    }

                    //Click on fireworks
                    if (player.speed < minElytraVelocity && !lagback && fireworkTimer.tick((fireworkDelay * 1000).toInt())) {
                        activateFirework()
                    }
                }
            }

            //Generate more path
            if (path.size <= 20 || isNextPathTooFar()) {
                generatePath()
            }

            //Distance how far to remove the upcoming path.
            //The higher it is the smoother the movement will be but it will need more space.
            var distance = 12
            if (travelMode == ElytraBotMode.Highway) {
                distance = 2
            }

            //Remove passed positions from path
            val removePositions = ArrayList<BlockPos>()
            path.forEach { pos ->
                if (player.position.distanceSq(pos) <= distance) {
                    removePositions.add(pos)
                }
            }
            removePositions.forEach { pos ->
                path.remove(pos)
                previous = pos
            }
            if (path.isNotEmpty() && elytraMode == ElytraBotFlyMode.Firework) {
                path.lastOrNull()?.let { pathPos ->
                    val pos = Vec3d(pathPos).add(0.5, 0.5, 0.5)

                    val eyesPos = Vec3d(player.posX, player.posY + player.getEyeHeight(), player.posZ)
                    val diffX = pos.x - eyesPos.x
                    val diffY = pos.y - eyesPos.y
                    val diffZ = pos.z - eyesPos.z
                    val diffXZ = sqrt(diffX * diffX + diffZ * diffZ)
                    val yaw = Math.toDegrees(atan2(diffZ, diffX)).toFloat() - 90f
                    val pitch = (-Math.toDegrees(atan2(diffY, diffXZ))).toFloat()

                    val rotation = Vec2f(player.rotationYaw + MathHelper.wrapDegrees(yaw - player.rotationYaw), player.rotationPitch + MathHelper.wrapDegrees(pitch - player.rotationPitch))

                    when (interacting) {
                        RotationMode.SPOOF -> {
                            sendPlayerPacket {
                                rotate(rotation)
                            }
                        }
                        RotationMode.VIEW_LOCK -> {
                            player.rotationYaw = rotation.x
                            player.rotationPitch = rotation.y
                        }
                        else -> {
                            // RotationMode.OFF
                        }
                    }
                }
            }
        }
    }

    private fun isItemBroken(itemStack: ItemStack): Boolean { // (100 * damage / max damage) >= (100 - 70)
        return if (itemStack.maxDamage == 0) {
            false
        } else {
            itemStack.maxDamage - itemStack.itemDamage <= 3
        }
    }

    //Generate path
    private fun SafeClientEvent.generatePath() {
        //The positions the AStar algorithm is allowed to move from current.
        val positions = arrayOf(BlockPos(1, 0, 0), BlockPos(-1, 0, 0), BlockPos(0, 0, 1), BlockPos(0, 0, -1),
            BlockPos(1, 0, 1), BlockPos(-1, 0, -1), BlockPos(-1, 0, 1), BlockPos(1, 0, -1),
            BlockPos(0, -1, 0), BlockPos(0, 1, 0))

        val checkPositions = when (travelMode) {
            ElytraBotMode.Highway -> {
                val list = arrayOf(BlockPos(1, 0, 0), BlockPos(-1, 0, 0), BlockPos(0, 0, 1), BlockPos(0, 0, -1),
                    BlockPos(1, 0, 1), BlockPos(-1, 0, -1), BlockPos(-1, 0, 1), BlockPos(1, 0, -1))
                ArrayList(list.asList())
            }
            ElytraBotMode.Overworld -> {
                VectorUtils.getBlockPosInSphere(Vec3d.ZERO, aStarRadius)
            }
        }

        if (path.isEmpty() || isNextPathTooFar() || player.onGround) {
            var start = when {
                travelMode == ElytraBotMode.Overworld -> {
                    player.position.add(0, 4, 0)
                }
                abs(jumpY - player.posY) <= 2 -> {
                    BlockPos(player.posX, jumpY + 1, player.posZ)
                }
                else -> {
                    player.position.add(0, 1, 0)
                }
            }
            if (isNextPathTooFar()) {
                start = player.position
            }
            goal?.let {
                path = AStar.generatePath(mc, start, it, positions, checkPositions, 500)
            }
        } else {
            goal?.let {
                path = AStar.generatePath(mc, path[0], it, positions, checkPositions, 500)
            }
        }
    }

    private fun SafeClientEvent.takeoff() {
        /* Pause Takeoff if server is lagging, player is in water/lava, or player is on ground */
        val timerSpeed = if (highPingOptimize) 400.0f else 200.0f
        val height = if (highPingOptimize) 0.0f else minTakeoffHeight
        val closeToGround = player.posY <= world.getGroundPos(player).y + height && !mc.isSingleplayer

        if (player.motionY < 0 && !highPingOptimize || player.motionY < -0.02) {
            if (closeToGround) {
                mc.timer.tickLength = 25.0f
                return
            }

            if (!mc.isSingleplayer) mc.timer.tickLength = timerSpeed * 2.0f
            connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_FALL_FLYING))
        } else if (highPingOptimize && !closeToGround) {
            mc.timer.tickLength = timerSpeed
        }
    }

    private fun SafeClientEvent.activateFirework() {
        if (player.heldItemMainhand.item != Items.FIREWORKS) {
            if (spoofHotbar) {
                val slot = if (player.serverSideItem.item == Items.FIREWORKS) HotbarManager.serverSideHotbar
                else player.hotbarSlots.firstItem(Items.FIREWORKS)?.hotbarSlot

                slot?.let {
                    spoofHotbar(it, 1000L)
                }
            } else {
                if (player.serverSideItem.item != Items.FIREWORKS) {
                    player.hotbarSlots.firstItem(Items.FIREWORKS)?.let {
                        swapToSlot(it)
                    }
                }
            }
        }
        connection.sendPacket(CPacketPlayerTryUseItem(EnumHand.MAIN_HAND))
    }

    private fun SafeClientEvent.isNextPathTooFar(): Boolean {
        return path.lastOrNull()?.let {
            player.position.distanceTo(it) > 15
        } ?: run {
            false
        }
    }

}