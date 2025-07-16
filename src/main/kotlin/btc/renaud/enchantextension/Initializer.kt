package btc.renaud.enchantextension

import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
@Singleton
object Initializer : Initializable {
    override suspend fun initialize() {
        plugin.server.pluginManager.getPlugin("AdvancedEnchantments")
        logger.info("AdvancedEnchantmentsExtension has been successfully initialized. By Renaud")
    }

    override suspend fun shutdown() {
        logger.info("AdvancedEnchantmentsExtension has been successfully stopped.")
    }
}
