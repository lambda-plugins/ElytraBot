import com.lambda.client.event.SafeClientEvent
import com.lambda.client.plugin.api.PluginLabelHud
import com.lambda.client.util.color.ColorHolder

internal object ElytraBotStatus : PluginLabelHud(
    name = "Elytra Bot Status",
    category = Category.CLIENT,
    description = "Elytra Bot Status",
    pluginMain = ElytraBotPlugin
) {

    private val textColor by setting("Text Color", ColorHolder(0, 255, 0, 255))

    override fun SafeClientEvent.updateText() {
        if(ElytraBotModule.isEnabled){
            displayText.addLine("Going to ${ElytraBotModule.goal?.x}, ${ElytraBotModule.goal?.y}, ${ElytraBotModule.goal?.z}", textColor)
        }
    }

}