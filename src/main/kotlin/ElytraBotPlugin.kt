import com.lambda.client.plugin.api.Plugin

internal object ElytraBotPlugin : Plugin() {

    override fun onLoad() {
        // Load any modules, commands, or HUD elements here
        modules.add(ElytraBotModule)
        commands.add(ElytraBotCommand)
        hudElements.add(ElytraBotStatus)
    }

    override fun onUnload() {}
}