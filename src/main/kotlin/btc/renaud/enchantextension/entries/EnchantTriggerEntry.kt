package btc.renaud.enchantextension.entries

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.entries.ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.AudienceEntry
import com.typewritermc.engine.paper.entry.entries.AudienceFilter
import com.typewritermc.engine.paper.entry.entries.AudienceFilterEntry
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.matches
import com.typewritermc.engine.paper.entry.triggerFor
import net.advancedplugins.ae.api.AEAPI
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.InventoryAction
import java.util.*

@Entry("advancedenchants_trigger", "A trigger for when players enchant items", Colors.PURPLE, "ph:sparkle-bold")
class EnchantTriggerEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val children: List<Ref<AudienceEntry>> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    @Help("The enchantment name that needs to be applied.")
    val enchantment: Optional<String> = Optional.empty(),
    @Help("The minimum level of the enchantment required.")
    val level: Optional<Int> = Optional.empty(),
) : AudienceFilterEntry, TriggerableEntry {

    override suspend fun display(): AudienceFilter {
        return EnchantTriggerDisplay(ref())
    }
}

private class EnchantTriggerDisplay(private val ref: Ref<EnchantTriggerEntry>) : AudienceFilter(ref) {

    override fun filter(player: Player): Boolean {
        val entry = ref.get() ?: return false
        return entry.criteria.matches(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val entry = ref.get() ?: return
        
        if (!filter(player)) return

        // Check if this is an enchanting table or anvil
        if (event.inventory.type != InventoryType.ENCHANTING && event.inventory.type != InventoryType.ANVIL) return
        
        // Only proceed if the action is taking an item from the result slot
        if (event.action != InventoryAction.PICKUP_ALL && 
            event.action != InventoryAction.PICKUP_HALF &&
            event.action != InventoryAction.MOVE_TO_OTHER_INVENTORY) return
        
        // Only proceed if clicking on the top inventory (not player inventory)
        if (event.clickedInventory != event.inventory) return
        
        // Get the item being clicked - use a more specific approach
        val clickedItem = when (event.inventory.type) {
            InventoryType.ENCHANTING -> {
                // For enchanting table, only check slot 0 (result slot)
                if (event.slot == 0) {
                    event.currentItem
                } else return
            }
            InventoryType.ANVIL -> {
                // For anvil, only check slot 2 (result slot)  
                if (event.slot == 2) {
                    event.currentItem
                } else return
            }
            else -> return
        } ?: return
        
        // Check if the item has any Advanced Enchantments
        try {
            val enchantmentsOnItem = AEAPI.getEnchantmentsOnItem(clickedItem)
            if (enchantmentsOnItem.isEmpty()) return
            
            val requiredEnchantment = entry.enchantment
            val requiredLevel = entry.level
            
            // If no specific enchantment is required, trigger for all enchanted items
            if (!requiredEnchantment.isPresent || requiredEnchantment.get().isEmpty()) {
                entry.triggers.forEach { it.triggerFor(player, context()) }
                return
            }
            
            // Check if the required enchantment is present with the right level
            for ((enchantName, enchantLevel) in enchantmentsOnItem) {
                val enchantmentMatches = enchantName.equals(requiredEnchantment.get(), ignoreCase = true)
                val levelMatches = !requiredLevel.isPresent || enchantLevel >= requiredLevel.get()
                
                if (enchantmentMatches && levelMatches) {
                    entry.triggers.forEach { it.triggerFor(player, context()) }
                    break
                }
            }
        } catch (e: Exception) {
            // Ignore items that don't have Advanced Enchantments
        }
    }
}
