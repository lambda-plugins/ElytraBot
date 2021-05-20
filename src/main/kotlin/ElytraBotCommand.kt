import com.lambda.client.command.ClientCommand
import com.lambda.client.util.text.MessageSendHelper
import net.minecraft.util.math.BlockPos

object ElytraBotCommand : ClientCommand(
    name = "elytrabot",
    description = "Commands for elytrabot"
) {
    init {
        literal("goto") {

            int("x/y") { xArg ->
                executeSafe("Set goal to a Y level.") {
                    MessageSendHelper.sendChatMessage("You need at specify x and z pos")
                }

                int("y/z") { yArg ->
                    executeSafe("Set goal to X Z.") {
                        ElytraBotModule.goal = BlockPos(xArg.value, 0, yArg.value)
                        ElytraBotModule.enable()

                    }


                }
            }
        }
        literal("goal", "coordinates") {
            literal("clear") {
                executeSafe("Clear the current goal.") {
                    ElytraBotModule.goal = null
                }
            }

            int("x/y") { xArg ->
                executeSafe("Not enough arguments") {
                    MessageSendHelper.sendChatMessage("You need at specify x and z pos")
                }

                int("y/z") { yArg ->
                    executeSafe("Set goal to X Z.") {
                        ElytraBotModule.goal = BlockPos(xArg.value, 0, yArg.value)
                    }
                }
            }


        }
        literal("path") {
            literal("clear") {
                executeSafe("clears current path") {
                    ElytraBotModule.goal = null
                    ElytraBotModule.disable()
                }
            }
            executeSafe("Paths to goal postition") {
                ElytraBotModule.enable()
            }

        }

    }
}