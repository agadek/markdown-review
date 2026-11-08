package pl.mdreview.ide

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor

/**
 * Reacts when the optional AsciiDoc plugin is enabled or disabled while the IDE is running, without reopening a
 * project. The normal startup path is [asciidoc.AsciiDocIntegrationStartup]; this listener covers the dynamic case.
 *
 * The class deliberately imports no AsciidoctorJ class: [AsciiDocIntegration] loads the optional provider by name.
 */
class AsciiDocPluginListener : DynamicPluginListener {

    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString == AsciiDocIntegration.PLUGIN_ID) {
            AsciiDocIntegration.enable(notifyOnFailure = true)
        }
    }

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString == AsciiDocIntegration.PLUGIN_ID) AsciiDocIntegration.disable()
    }
}
