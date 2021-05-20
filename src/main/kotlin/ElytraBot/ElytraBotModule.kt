package ElytraBot


import ExtraMovment
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.MovementUtils.speed
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import net.minecraft.util.EnumHand


import net.minecraft.util.math.BlockPos

import net.minecraft.init.Items

import java.util.ArrayList

import net.minecraft.util.math.Vec3d

import net.minecraft.network.play.client.CPacketEntityAction

import com.lambda.client.util.Timer
import com.lambda.client.util.items.allSlots
import com.lambda.client.util.items.countItem
import com.lambda.client.util.items.inventorySlots
import com.lambda.client.util.math.Vec2d
import com.lambda.client.util.math.Vec2f
import com.lambda.client.util.math.VectorUtils.toVec3d

import net.minecraft.item.ItemStack

import net.minecraft.util.SoundCategory

import net.minecraft.init.SoundEvents
import java.lang.Exception
import com.lambda.client.util.math.VectorUtils.distanceTo

import net.minecraft.util.math.MathHelper
import com.lambda.client.util.text.MessageSendHelper



internal object ElytraBotModule: PluginModule(
    name = "ElytraBot",
    category = Category.MOVEMENT,
    description = "Baritone like Elytra bot module, credit CookieClient",
    pluginMain = ExtraMovment
) {


    var thread: Thread? = null
    private var path: ArrayList<BlockPos>? = null
    var goal: BlockPos? = null
    private  var previous:BlockPos? = null
    private  var lastSecondPos:BlockPos? = null

    private var jumpY = -1.0
    private var packetsSent = 0
    private  var lagbackCounter = 0
    private  var useBaritoneCounter = 0
    private var lagback = false
    private var blocksPerSecond = 0.0
    private var blocksPerSecondCounter = 0
    private val blocksPerSecondTimer = TickTimer(TimeUnit.MILLISECONDS)
    private val packetTimer = TickTimer(TimeUnit.MILLISECONDS)
    private val fireworkTimer = TickTimer(TimeUnit.MILLISECONDS)
    private val takeoffTimer= TickTimer(TimeUnit.MILLISECONDS)
    private var direction: Direction? = null

    enum class ElytraBotMode {
        Highway, Overworld
    }
    enum class ElytraBotTakeOffMode {
        SlowGlide, Jump
    }
    enum class ElytraBotFlyMode {
        Firework
    }
    var TravelMode = setting("Travel Mode", ElytraBotMode.Overworld)
    var TakeoffMode = setting("Takeoff Mode", ElytraBotTakeOffMode.Jump)
    var ElytraMode = setting("Flight Mode", ElytraBotFlyMode.Firework)


    //private val elytraFlySpeed by setting("Elytra Speed", 1f, 0.1f..20.0f, 0.25f, { ElytraMode.value != ElytraBotFlyMode.Firework })

    private val elytraFlyManuverSpeed by setting("Manuver Speed", 1f, 0.0f..10.0f, 0.25f)

    private val fireworkDelay by setting("Firework Delay", 1f, 0.0f..10.0f, 0.25f,{ ElytraMode.value == ElytraBotFlyMode.Firework })

    var pathfinding = setting("Pathfinding", true)

    var avoidLava = setting("AvoidLava", true)

    private var directional = setting("Directional", false)

    private var toggleOnPop = setting("ToggleOnPop", false)

    private val maxY by setting("Firework Delay", 1f, 0.0f..300.0f, 0.25f)



    private fun isItemBroken(itemStack: ItemStack): Boolean { // (100 * damage / max damage) >= (100 - 70)
        return if (itemStack.maxDamage == 0) {
            false
        } else {
            itemStack.maxDamage - itemStack.itemDamage <= 3
        }
    }




    init {
        onEnable {
            val up = 1
            if (directional.value) {
                //Calculate the direction so it will put it to diagonal if the player is on diagonal highway.
                direction = if (Math.abs(mc.player.posX - mc.player.posZ) <= 5 && Math.abs(mc.player.posX) > 10 && Math.abs(mc.player.posZ) > 10 && TravelMode.value== ElytraBotMode.Highway) {
                    Direction.getDiagonalDirection()
                } else {
                    Direction.getDirection()
                }
                goal = generateGoalFromDirection(direction, up)
            } else {

                if(goal==null){
                    MessageSendHelper.sendChatMessage("You need a goal position")
                    disable()
                }
            }
            thread = object : Thread() {
                override fun run() {
                    while (thread != null && thread == this) {
                        try {
                            loop()
                        } catch (e: NullPointerException) {

                        }
                        try {
                            sleep(50)
                        } catch(e: Exception){}

                    }
                }
            }
            blocksPerSecondTimer.reset()

            thread?.start()
        }

        onDisable {
            direction = null
            path = null
            useBaritoneCounter = 0
            lagback = false
            lagbackCounter = 0
            blocksPerSecond = 0.0
            blocksPerSecondCounter = 0
            lastSecondPos = null
            jumpY = -1.0
            //RenderPath.clearPath()

            //clearStatus()
            //BaritoneUtil.forceCancel()
            if(thread!==null)
            thread!!.suspend()
            thread = null
        }


    }
    fun loop() {
        if (mc.player == null) {
            return
        }
        if (goal == null){
            disable()
            MessageSendHelper.sendChatMessage("You need a goal position")
            return;
        }

        //Check if the goal is reached and then stop
        if (mc.player.positionVector.distanceTo(goal!!.toVec3d()) < 15) {
            mc.world.playSound(mc.player.position, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.AMBIENT, 100.0f, 18.0f, true)
            MessageSendHelper.sendChatMessage("$chatName Goal reached!.")
            disable()
            return
        }

        //Check if there is an elytra equipped if not then equip it or toggle off if no elytra in inventory
        if (mc.player.inventory.armorInventory[2].item !== Items.ELYTRA || isItemBroken(mc.player.inventory.armorInventory[2])) {
                MessageSendHelper.sendChatMessage("$chatName You need an elytra.")
                disable()
                return
        }

        //Toggle off if no fireworks while using firework mode
        if (ElytraMode.value == ElytraBotFlyMode.Firework && !(mc.player.inventorySlots.countItem(Items.FIREWORKS)>0)) {
            MessageSendHelper.sendChatMessage("You need fireworks as your using firework mode")
            disable()
            return
        }

        //Wait still if in unloaded chunk
        if (!mc.world.getChunk(mc.player.position).isLoaded()) {
            //setStatus("We are in unloaded chunk. Waiting")
            mc.player.setVelocity(0.0, 0.0, 0.0)
            return
        }



        if (!mc.player.isElytraFlying) {
            //ElytraFly.toggle(false)

//            //If there is a block above then usebaritone
//            if (Helper.mc.player.onGround && mc.world.getBlockState(mc.player.position.add(0,2,0)).material.isSolid && useBaritone && mode.value == ElytraBotMode.Tunnel) {
//                //setStatus("Using baritone because a block above is preventing takeoff")
//                useBaritone()
//            }
//
//            //Mine above block in tunnel mode
//            if ( mc.world.getBlockState(mc.player.position.add(0,2,0)).material.isSolid && mode.value == ElytraBotMode.Tunnel) {
//                if (mc.player.position.add(0,2,0) !== Blocks.BEDROCK) {
//                    //setStatus("Mining above block so we can takeoff")
//                    centerMotion()
//                    MiningUtil.mineAnyway(getPlayerPos().add(0, 2, 0), false)
//                } else {
//                    if (useBaritone.booleanValue()) {
//                        setStatus("Using baritone to walk because above block is bedrock")
//                        useBaritone()
//                    } else {
//                        sendMessage("Above block is bedrock and usebaritone is false", true)
//                        disable()
//                        return
//                    }
//                }
//            }


            //if (packetsSent < 20) setStatus("Trying to takeoff")
            fireworkTimer.reset()

            //Jump if on ground
            if (mc.player.onGround) {
                jumpY = mc.player.posY
                generatePath()
                mc.player.jump()
            } else if (mc.player.posY < mc.player.lastTickPosY) {
                if (TakeoffMode.value== ElytraBotTakeOffMode.SlowGlide) {
                    mc.player.setVelocity(0.0, -0.04, 0.0)
                }

                //Dont send anymore packets for about 15 seconds if the takeoff isnt successfull.
                //Bcs 2b2t has this annoying thing where it will not let u open elytra if u dont stop sending the packets for a while
                if (packetsSent <= 15) {
                    if (takeoffTimer.time >= 650) {
                        mc.connection!!.sendPacket(CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_FALL_FLYING))
                        takeoffTimer.reset()
                        packetTimer.reset()
                        packetsSent++
                    }
                } else if (packetTimer.time>=15000){//hasPassed(15000)) {
                    packetsSent = 0
                } else {
                    //setStatus("Waiting for 15s before sending elytra open packets again")
                }
            }
            return
        }  else {
            packetsSent = 0;

            //If we arent moving anywhere then activate usebaritone
            var speed = mc.player.speed

            if (ElytraMode.value == ElytraBotFlyMode.Firework) {
                //Prevent lagback on 2b2t by not clicking on fireworks. I hope hause would fix hes plugins tho
                if (speed > 3) {
                    lagback = true;
                }

                //Remove lagback thing after it stops and click on fireworks again.
                if (lagback) {
                    if (speed < 1) {
                        lagbackCounter++;
                        if (lagbackCounter > 3) {
                            lagback = false;
                            lagbackCounter = 0;
                        }
                    } else {
                        lagbackCounter = 0;
                    }
                }
                //
                //Click on fireworks

                if (fireworkTimer.tick((fireworkDelay*1000).toInt()) && !lagback) {

                    clickOnFirework();
                }
            }
        }

        //Generate more path
        if (path == null || path!!.size <= 20 || isNextPathTooFar()) {
            generatePath()
        }

        //Distance how far to remove the upcoming path.
        //The higher it is the smoother the movement will be but it will need more space.
        var distance = 12
        if (TravelMode.value == ElytraBotMode.Highway) {
            distance = 2
        }

        //Remove passed positions from path
        var remove = false
        val removePositions = ArrayList<BlockPos>()
        for (pos in path!!) {
            //if (!remove && BlockUtil.distance(pos, getPlayerPos()) <= distance) {
            if (!remove && mc.player.position.distanceSq(pos)<= distance) {

                remove = true
            }
            if (remove) {
                removePositions.add(pos)
            }
        }
        for (pos in removePositions) {
            path!!.remove(pos)
            previous = pos
        }
        if (path!!.size > 0) {
            if (direction != null) {
                //setStatus("Going to " + direction.name)
            } else {
                //setStatus("Going to X: $x Z: $z")
//                if (blocksPerSecondTimer.hasPassed(1000)) {
//                    blocksPerSecondTimer.reset()
//                    if (lastSecondPos != null) {
//                        blocksPerSecondCounter++
//                        blocksPerSecond += BlockUtil.distance(getPlayerPos(), lastSecondPos)
//                    }
//                    lastSecondPos = getPlayerPos()
//                }
//                val seconds = (BlockUtil.distance(getPlayerPos(), goal) / (blocksPerSecond / blocksPerSecondCounter)) as Int
//                val h = seconds / 3600
//                val m = seconds % 3600 / 60
//                val s = seconds % 60
//                addToStatus("Estimated arrival in " + ChatFormatting.GOLD + h + "h " + m + "m " + s + "s", 1)
//                if (flyMode.stringValue().equals("Firework")) {
//                    //addToStatus("Estimated fireworks needed: " + ChatFormatting.GOLD + (seconds / fireworkDelay.doubleValue()) as Int, 2)
//                }
            }
            if (ElytraMode.value == ElytraBotFlyMode.Firework) {
                //Rotate head to next position
                val pos = Vec3d(path!![path!!.size - 1]).add(0.5, 0.5, 0.5)

                val eyesPos = Vec3d(mc.player.posX, mc.player.posY + mc.player.getEyeHeight(), mc.player.posZ)
                val diffX = pos.x - eyesPos.x
                val diffY = pos.y - eyesPos.y
                val diffZ = pos.z - eyesPos.z
                val diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ)
                val yaw = Math.toDegrees(Math.atan2(diffZ, diffX)).toFloat() - 90f
                val pitch = (-Math.toDegrees(Math.atan2(diffY, diffXZ))).toFloat()

                val rotation = floatArrayOf(mc.player.rotationYaw + MathHelper.wrapDegrees(yaw - mc.player.rotationYaw), mc.player.rotationPitch + MathHelper.wrapDegrees(pitch - mc.player.rotationPitch))

//                sendPlayerPacket {
//                    rotate(Vec2f(rotation[0],rotation[1]))
//                }

                mc.player.rotationYaw = rotation[0];
                mc.player.rotationPitch = rotation[1];
            }
        }
    }

    //Generate path
    fun generatePath() {
        //The positions the AStar algorithm is allowed to move from current.
        var positions = arrayOf(BlockPos(1, 0, 0), BlockPos(-1, 0, 0), BlockPos(0, 0, 1), BlockPos(0, 0, -1),
            BlockPos(1, 0, 1), BlockPos(-1, 0, -1), BlockPos(-1, 0, 1), BlockPos(1, 0, -1),
            BlockPos(0, -1, 0), BlockPos(0, 1, 0))
        var checkPositions = ArrayList<BlockPos>()
        if (TravelMode.value == ElytraBotMode.Highway) {
            val list = arrayOf(BlockPos(1, 0, 0), BlockPos(-1, 0, 0), BlockPos(0, 0, 1), BlockPos(0, 0, -1),
                BlockPos(1, 0, 1), BlockPos(-1, 0, -1), BlockPos(-1, 0, 1), BlockPos(1, 0, -1))
            checkPositions = ArrayList<BlockPos>(list.asList())
        } else if (TravelMode.value == ElytraBotMode.Overworld) {
            val radius = 3
            for (x in -radius until radius) {
                for (z in -radius until radius) {
                    for (y in radius downTo -radius + 1) {
                        checkPositions.add(BlockPos(x, y, z))
                    }
                }
            }
        }

        if (path == null || path!!.size == 0 || isNextPathTooFar() || mc.player.onGround) {
            var start: BlockPos?
            start = if (TravelMode.value == ElytraBotMode.Overworld) {
                mc.player.position.add(0, 4, 0)
            } else if (Math.abs(jumpY - mc.player.posY) <= 2) {
                BlockPos(mc.player.posX, jumpY + 1, mc.player.posZ)
            } else {
                mc.player.position.add(0, 1, 0)
            }
            if (isNextPathTooFar()) {
                start = mc.player.position
            }
            path = AStar.generatePath(start, goal!!, positions, checkPositions, 500)

        } else {
            val temp: ArrayList<BlockPos> = AStar.generatePath(path!![0], goal!!, positions, checkPositions, 500)
            try {
                temp.addAll(path!!)
            } catch (ignored: NullPointerException) {
            }
            path = temp
        }
        //RenderPath.setPath(path, Color(255, 0, 0, 150))
    }

//    @EventHandler
//    private val packetEvent: Listener<PacketEvent> = Listener { event ->
//        if (event.packet is SPacketEntityStatus) {
//            val packet = event.packet as SPacketEntityStatus
//            if (packet.opCode.toInt() == 35 && packet.getEntity(mc.world) === mc.player && toggleOnPop.booleanValue()) {
//                sendMessage("You popped a totem.", false)
//                disable()
//            }
//        }
//    }



    fun clickOnFirework() {
        if (mc.player.heldItemMainhand.item !== Items.FIREWORKS) {
            //InventoryUtil.switchItem(InventoryUtil.getSlot(Items.FIREWORKS), false)
                var slot: Int = -1;
            for (itemStack in mc.player.allSlots) {
                if (itemStack.equals(Items.FIREWORKS)) {

                    slot = itemStack.slotIndex
                }
            }
            //if (slot == -1){return;}
            //mc.player.inventory.currentItem = slot;
        }

        //Click
        mc.playerController.processRightClick(mc.player, mc.world, EnumHand.MAIN_HAND)
        //fireworkTimer.

    }

    fun generateGoalFromDirection(direction: Direction?, up: Int): BlockPos? {
        return if (direction === Direction.ZM) {
            BlockPos(0.0, mc.player.posY + up, mc.player.posZ - 42042069)
        } else if (direction === Direction.ZP) {
            BlockPos(0.0, mc.player.posY + up, mc.player.posZ + 42042069)
        } else if (direction === Direction.XM) {
            BlockPos(mc.player.posX - 42042069, mc.player.posY + up, 0.0)
        } else if (direction === Direction.XP) {
            BlockPos(mc.player.posX + 42042069, mc.player.posY + up, 0.0)
        } else if (direction === Direction.XP_ZP) {
            BlockPos(mc.player.posX + 42042069, mc.player.posY + up, mc.player.posZ + 42042069)
        } else if (direction === Direction.XM_ZM) {
            BlockPos(mc.player.posX - 42042069, mc.player.posY + up, mc.player.posZ - 42042069)
        } else if (direction === Direction.XP_ZM) {
            BlockPos(mc.player.posX + 42042069, mc.player.posY + up, mc.player.posZ - 42042069)
        } else {
            BlockPos(mc.player.posX - 42042069, mc.player.posY + up, mc.player.posZ + 42042069)
        }
    }

    fun isNextPathTooFar(): Boolean {
        return try {
            //BlockUtil.distance(getPlayerPos(), path!![path!!.size - 1]) > 15
            mc.player.position.distanceTo(path!![path!!.size - 1]) > 15
        } catch (e: Exception) {
            false
        }
    }

}