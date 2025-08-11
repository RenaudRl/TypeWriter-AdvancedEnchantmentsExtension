package btc.renaud.HmcCosmeticExtension

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import org.bukkit.entity.Player
import java.util.*
import kotlin.reflect.KClass

@Entry("hmc_cosmetic_data", "HMCCosmetics data", Colors.RED, "mdi:face-smile")
@Tags("hmc_cosmetic", "cosmetic_data", "equipment_data")
class HmcCosmeticData(
    override val id: String = "",
    override val name: String = "",
    /**
     * Map of cosmetic type (string) -> cosmetic id (string). The Var<String> allows the TypeWriter config
     * to provide either a scalar string or a variable.
     *
     * Accepted keys (case-insensitive):
     * - HMCCosmetic types: HELMET, CHESTPLATE, LEGGINGS, BOOTS, MAINHAND, OFFHAND, BACKPACK, BALLOON
     * - Legacy equipment names: MAIN_HAND, OFF_HAND, HELMET/HEAD, CHEST_PLATE/CHEST, LEGGINGS/LEGS, BOOTS/FEET
     */
    val cosmetics: Map<String, Var<String>> = emptyMap(),
    val helmetOffset: Double? = null,
    override val priorityOverride: Optional<Int> = Optional.empty(),
) : LivingEntityData<CosmeticProperty> {
    override fun type(): KClass<CosmeticProperty> = CosmeticProperty::class

    override fun build(player: Player): CosmeticProperty =
        CosmeticProperty(cosmetics.mapValues { (_, v) -> v.get(player) }, helmetOffset)
}

data class CosmeticProperty(val data: Map<String, String>, val helmetOffset: Double? = null) : EntityProperty {
    constructor(slot: String, cosmeticId: String) : this(mapOf(slot to cosmeticId), null)

    companion object : PropertyCollectorSupplier<CosmeticProperty> {
        override val type: KClass<CosmeticProperty> = CosmeticProperty::class

        override fun collector(suppliers: List<PropertySupplier<out CosmeticProperty>>): PropertyCollector<CosmeticProperty> {
            return CosmeticCollector(suppliers.filterIsInstance<PropertySupplier<CosmeticProperty>>())
        }
    }
}

private class CosmeticCollector(
    private val suppliers: List<PropertySupplier<CosmeticProperty>>,
) : PropertyCollector<CosmeticProperty> {
    override val type: KClass<CosmeticProperty> = CosmeticProperty::class

    override fun collect(player: Player): CosmeticProperty {
        val properties = suppliers.associateWith { it.build(player) }
        val applicableProperties = properties.filter { (s, _) -> s.canApply(player) }.map { it.value }

        val data = mutableMapOf<String, String>()
        applicableProperties.asSequence()
            .flatMap { it.data.asSequence() }
            .filter { (slot, _) -> !data.containsKey(slot.uppercase()) } // first applicable wins (case-insensitive)
            .forEach { (slot, cosmetic) -> data[slot.uppercase()] = cosmetic }

        // Reset slots that were present in any supplier but not in final data to empty string
        val allSlots = properties.values.flatMap { it.data.keys }.map { it.uppercase() }.toSet()
        allSlots.filter { !data.containsKey(it) }
            .forEach { data[it] = "" }

        val offset = applicableProperties.mapNotNull { it.helmetOffset }.firstOrNull()
        return CosmeticProperty(data, offset)
    }
}

/**
 * Cache HMCCPacketManager class lookup to avoid repeated Class.forName calls.
 * Null if HMCCosmetics is not present on the server classpath.
 */
private val HMCC_PACKET_MANAGER_CLASS: Class<*>? = try {
    Class.forName("com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager")
} catch (_: Throwable) {
    null
}

/**
 * Map a cosmetic key (string) to a Bukkit EquipmentSlot for packet fallback.
 * Accepts both cosmetic types and legacy equipment names. Keys are expected to be upper-cased before calling.
 *
 * Note: Return null for HMCC-specific slots that should not fall back to vanilla equipment mapping
 * (BACKPACK/BALLOON) to avoid incorrect visual placement in a player's hand.
 */
private fun cosmeticKeyToBukkitSlot(key: String): org.bukkit.inventory.EquipmentSlot? {
    val k = key.uppercase()
    return when {
        // HMCCosmetics standard mappings
        k == "MAINHAND" || k == "MAIN_HAND" || k == "HAND" || k == "MAIN" -> org.bukkit.inventory.EquipmentSlot.HAND
        k == "OFFHAND" || k == "OFF_HAND" || k == "OFF" -> org.bukkit.inventory.EquipmentSlot.OFF_HAND
        k == "HELMET" || k == "HEAD" -> org.bukkit.inventory.EquipmentSlot.HEAD
        k == "CHESTPLATE" || k == "CHEST_PLATE" || k == "CHEST" -> org.bukkit.inventory.EquipmentSlot.CHEST
        k == "LEGGINGS" || k == "LEGS" -> org.bukkit.inventory.EquipmentSlot.LEGS
        k == "BOOTS" || k == "FEET" -> org.bukkit.inventory.EquipmentSlot.FEET

        // HMCCosmetics-specific types that should NOT map to vanilla slots
        k == "BACKPACK" || k == "BALLOON" -> null

        else -> null
    }
}

/**
 * Apply the given CosmeticProperty to a runtime entity object using TypeWriter-compatible approach.
 *
 * Strategy:
 * 1) Apply cosmetics using HMCCosmetics API when possible
 * 2) For backpack/balloon: create ArmorStand entities and mount them as passengers (TypeWriter pattern)
 * 3) For helmet offset: use TranslationProperty to adjust TextDisplay position
 *
 * This function is defensive: failures are logged at fine level and swallowed to avoid breaking TypeWriter.
 */
fun applyHmcCosmeticData(entity: Any, property: CosmeticProperty) {
    org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: applyHmcCosmeticData called for entity=${entity?.javaClass?.name}")
    logger.info("HmcCosmeticExtension: applyHmcCosmeticData called for entity=${entity?.javaClass?.name}")
    // Normalize keys (uppercase)
    val entries = property.data.mapKeys { it.key.uppercase() }
    val nonBlankEntries = entries.filterValues { it.isNotBlank() }
    val hasNonBlank = nonBlankEntries.isNotEmpty()

    // Debug visibility: log what TypeWriter provided for this entity
    try {
        org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: raw entries=${property.data}")
        org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: normalized entries=$entries")
        org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: nonBlankEntries=$nonBlankEntries hasNonBlank=$hasNonBlank")
    } catch (_: Throwable) {}

    if (!hasNonBlank) {
        logger.fine("HmcCosmeticExtension: no non-blank cosmetics to apply for entity=${entity?.javaClass?.name}")
        // Do not return: still attempt TextDisplay / mounting behavior below
    }

    // Extract common values via reflection (best-effort)
    var uuidLocal: java.util.UUID? = null
    var underlyingBukkitLocal: Any? = null
    var entityIdLocal: Int? = null
    var locLocal: org.bukkit.Location? = null

    try {
        val cls0 = entity::class.java

        // UUID extraction
        run {
            val uuidMethods0 = listOf("getUniqueId", "getUuid", "uuid", "uniqueId")
            for (m in uuidMethods0) {
                try {
                    val meth = cls0.getMethod(m)
                    val res = meth.invoke(entity)
                    if (res is java.util.UUID) { uuidLocal = res; break }
                    if (res is String) { uuidLocal = java.util.UUID.fromString(res); break }
                } catch (_: Throwable) {}
            }
            if (uuidLocal == null) {
                try {
                    val f = cls0.getDeclaredField("uuid")
                    f.isAccessible = true
                    val v = f.get(entity)
                    if (v is java.util.UUID) uuidLocal = v
                    else if (v is String) uuidLocal = java.util.UUID.fromString(v)
                } catch (_: Throwable) {}
            }
        }

        // underlying bukkit entity extraction
        run {
            val bMethods = listOf("getBukkitEntity", "toBukkitEntity", "getEntity", "entity")
            for (bm in bMethods) {
                try {
                    val meth = cls0.getMethod(bm)
                    val r = meth.invoke(entity)
                    if (r != null) { underlyingBukkitLocal = r; break }
                } catch (_: Throwable) {}
            }
            if (underlyingBukkitLocal != null) {
                // try to extract entityId and location from the underlying bukkit entity
                try {
                    val m = underlyingBukkitLocal!!.javaClass.getMethod("getEntityId")
                    val r = m.invoke(underlyingBukkitLocal)
                    when (r) {
                        is Int -> entityIdLocal = r
                        is Number -> entityIdLocal = r.toInt()
                        is String -> entityIdLocal = r.toIntOrNull()
                    }
                } catch (_: Throwable) {}
                try {
                    val lm = underlyingBukkitLocal!!.javaClass.getMethod("getLocation")
                    val lr = lm.invoke(underlyingBukkitLocal)
                    if (lr is org.bukkit.Location) locLocal = lr
                } catch (_: Throwable) {}
            }

            // If still no underlying bukkit, try direct entity id/location on wrapper
            if (entityIdLocal == null) {
                try {
                    val idMethods = listOf("getEntityId", "getId", "getEntityID", "getEntityId0")
                    for (m in idMethods) {
                        try {
                            val meth = cls0.getMethod(m)
                            val r = meth.invoke(entity)
                            when (r) {
                                is Int -> { entityIdLocal = r; break }
                                is Number -> { entityIdLocal = r.toInt(); break }
                                is String -> {
                                    val v = r.toIntOrNull()
                                    if (v != null) { entityIdLocal = v; break }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
            if (locLocal == null) {
                try {
                    val m2 = cls0.getMethod("getLocation")
                    val r2 = m2.invoke(entity)
                    if (r2 is org.bukkit.Location) locLocal = r2
                } catch (_: Throwable) {}
            }
        }
    } catch (t: Throwable) {
        logger.fine("HmcCosmeticExtension: preliminary reflection failed: ${t.message}")
    }

    // Immutable local copies to avoid smart-cast/capture issues in lambdas
    val uuidVal: java.util.UUID? = uuidLocal
    val underlyingBukkitVal: Any? = underlyingBukkitLocal
    val entityIdVal: Int? = entityIdLocal
    val locVal: org.bukkit.Location? = locLocal

    // Compute a viewers list once for use in packet fallbacks and teleport packets.
    // Prefer HMCCPacketManager.getViewers(loc) when available; otherwise broadcast to online players.
    val viewers: List<org.bukkit.entity.Player> = try {
        if (locVal != null && HMCC_PACKET_MANAGER_CLASS != null) {
            try {
                val getViewers = HMCC_PACKET_MANAGER_CLASS.getMethod("getViewers", org.bukkit.Location::class.java)
                @Suppress("UNCHECKED_CAST")
                getViewers.invoke(null, locVal) as? List<org.bukkit.entity.Player> ?: org.bukkit.Bukkit.getServer().onlinePlayers.toList()
            } catch (_: Throwable) {
                org.bukkit.Bukkit.getServer().onlinePlayers.toList()
            }
        } else {
            org.bukkit.Bukkit.getServer().onlinePlayers.toList()
        }
    } catch (_: Throwable) {
        org.bukkit.Bukkit.getServer().onlinePlayers.toList()
    }

    // 1) Try HMCCosmeticService authoritative apply for each non-blank cosmetic
    val appliedSlots = mutableSetOf<String>()
    if (hasNonBlank) {
        for ((slotKey, cosmeticId) in nonBlankEntries) {
            try {
                var applied = false
                try {
                    if (underlyingBukkitVal is org.bukkit.entity.Entity) {
                        try {
                            val e = underlyingBukkitVal as org.bukkit.entity.Entity
                            applied = HmcCosmeticService.applyToEntity(e, cosmeticId)
                        } catch (_: Throwable) {}
                    }
                    if (!applied && uuidVal != null) {
                        applied = HmcCosmeticService.applyToUuid(uuidVal, cosmeticId)
                    }
                } catch (_: Throwable) {}

                if (applied) {
                    appliedSlots.add(slotKey.uppercase())
                    logger.info("HmcCosmeticExtension: applied via HmcCosmeticService slot=$slotKey id=$cosmeticId")
                    org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: applied via HmcCosmeticService slot=$slotKey id=$cosmeticId")
                } else {
                    logger.fine("HmcCosmeticExtension: HmcCosmeticService did not apply slot=$slotKey id=$cosmeticId")
                }
            } catch (t: Throwable) {
                logger.fine("HmcCosmeticExtension: service apply error for slot=$slotKey id=$cosmeticId: ${t.message}")
            }
        }
    }

    // 2) Packet-level fallbacks using HMCCPacketManager
    if (HMCC_PACKET_MANAGER_CLASS != null && hasNonBlank) {
        try {
            // compute viewers once (respect HMCC getViewers + Settings.getViewDistance)
            val viewers: List<org.bukkit.entity.Player> = try {
                val rawViewers: List<org.bukkit.entity.Player> = try {
                    if (locVal != null) {
                        try {
                            val getViewers = HMCC_PACKET_MANAGER_CLASS.getMethod("getViewers", org.bukkit.Location::class.java)
                            @Suppress("UNCHECKED_CAST")
                            getViewers.invoke(null, locVal) as? List<org.bukkit.entity.Player> ?: org.bukkit.Bukkit.getServer().onlinePlayers.toList()
                        } catch (_: Throwable) {
                            org.bukkit.Bukkit.getServer().onlinePlayers.toList()
                        }
                    } else {
                        org.bukkit.Bukkit.getServer().onlinePlayers.toList()
                    }
                } catch (_: Throwable) {
                    org.bukkit.Bukkit.getServer().onlinePlayers.toList()
                }

                // filter by Settings.getViewDistance if available
                try {
                    val settingsClass = try { Class.forName("com.hibiscusmc.hmccosmetics.config.Settings") } catch (_: Throwable) { null }
                    val maxDist = try {
                        if (settingsClass != null) {
                            val gv = settingsClass.getMethod("getViewDistance")
                            val v = gv.invoke(null)
                            when (v) {
                                is Int -> v
                                is Number -> v.toInt()
                                else -> 0
                            }
                        } else 0
                    } catch (_: Throwable) { 0 }

                    if (locVal != null && maxDist > 0) {
                        val maxSq = maxDist.toDouble() * maxDist.toDouble()
                        rawViewers.filter { p ->
                            try {
                                p.world == locVal.world && p.location.distanceSquared(locVal) <= maxSq
                            } catch (_: Throwable) {
                                true
                            }
                        }
                    } else rawViewers
                } catch (_: Throwable) {
                    rawViewers
                }
            } catch (_: Throwable) {
                org.bukkit.Bukkit.getServer().onlinePlayers.toList()
            }

            // Prepare map of vanilla EquipmentSlot -> ItemStack for all cosmetics that map to a vanilla slot
            val itemsMap = java.util.HashMap<org.bukkit.inventory.EquipmentSlot, org.bukkit.inventory.ItemStack>()
            val hmccSpecific = mutableListOf<Pair<String, String>>() // (slotKey, cosmeticId)

            for ((slotKey, cosmeticId) in nonBlankEntries) {
                try {
                    val bukkitSlot = cosmeticKeyToBukkitSlot(slotKey)
                    val item = try { HmcCosmeticService.getCosmeticItem(cosmeticId) } catch (_: Throwable) { null }
                    if (bukkitSlot != null && item != null) {
                        itemsMap[bukkitSlot] = item
                    } else {
                        // HMCC-specific cosmetic (e.g., BACKPACK/BALLOON) — record for specific handling
                        hmccSpecific.add(slotKey to cosmeticId)
                    }
                } catch (_: Throwable) {
                    logger.fine("HmcCosmeticExtension: error preparing fallback for slot=$slotKey id=$cosmeticId")
                }
            }

            // Debug: show what was resolved for packet fallbacks
            try {
                org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: prepared itemsMap=${itemsMap.keys.map { it.name }} hmccSpecific=$hmccSpecific appliedSlots=$appliedSlots")
                logger.info("HmcCosmeticExtension: prepared itemsMap=${itemsMap.keys.map { it.name }} hmccSpecific=$hmccSpecific appliedSlots=$appliedSlots")
            } catch (_: Throwable) {}

            // Try to call map overload once for all vanilla-mapped slots
            try {
                val mapMethod = HMCC_PACKET_MANAGER_CLASS.methods.firstOrNull { m ->
                    if (m.name != "equipmentSlotUpdate") return@firstOrNull false
                    val pts = m.parameterTypes
                    pts.size == 3 && (pts[0] == java.lang.Integer.TYPE || pts[0] == java.lang.Integer::class.java) &&
                            java.util.Map::class.java.isAssignableFrom(pts[1]) &&
                            java.util.List::class.java.isAssignableFrom(pts[2])
                }
                if (mapMethod != null && entityIdVal != null && itemsMap.isNotEmpty()) {
                    try {
                        mapMethod.invoke(null, entityIdVal, itemsMap, viewers)
                        appliedSlots.addAll(itemsMap.keys.map { it.name.uppercase() })
                        logger.fine("HmcCosmeticExtension: sent map equipment update for entityId=$entityIdVal slots=${itemsMap.keys}")
                    } catch (t: Throwable) {
                        logger.fine("HmcCosmeticExtension: mapMethod.invoke failed: ${t.message}")
                    }
                } else if (itemsMap.isNotEmpty() && entityIdVal != null) {
                    // no mapMethod available -> fall back to per-slot int overload if present
                    val perSlotCandidate = HMCC_PACKET_MANAGER_CLASS.methods.firstOrNull { m ->
                        if (m.name != "equipmentSlotUpdate") return@firstOrNull false
                        val pts = m.parameterTypes
                        if (pts.size != 4) return@firstOrNull false
                        (pts[0] == java.lang.Integer.TYPE || pts[0] == java.lang.Integer::class.java) &&
                                pts[1].name == "org.bukkit.inventory.EquipmentSlot" &&
                                pts[2].name == "org.bukkit.inventory.ItemStack" &&
                                java.util.List::class.java.isAssignableFrom(pts[3])
                    }
                    if (perSlotCandidate != null) {
                        for ((slot, item) in itemsMap) {
                            try {
                                perSlotCandidate.invoke(null, entityIdVal, slot, item, viewers)
                                appliedSlots.add(slot.name.uppercase())
                            } catch (_: Throwable) {
                                logger.fine("HmcCosmeticExtension: per-slot invoke failed for slot=${slot.name}")
                            }
                        }
                        logger.fine("HmcCosmeticExtension: sent per-slot equipment updates for entityId=$entityIdVal")
                    }
                }

                // HMCC-specific cosmetic updates (BACKPACK/BALLOON etc) - only try for players
                if (hmccSpecific.isNotEmpty()) {
                    val cosmeticSlotClass = try { Class.forName("com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot") } catch (_: Throwable) { null }
                    val cosmeticUsersClass = try { Class.forName("com.hibiscusmc.hmccosmetics.user.CosmeticUsers") } catch (_: Throwable) { null }
                    val getUserMeth = cosmeticUsersClass?.getMethod("getUser", java.util.UUID::class.java)

                    for ((slotKey, cosmeticId) in hmccSpecific) {
                        try {
                            // Allow backpack/balloon on NPCs - they should be visible
                            // Remove the restriction that was preventing cosmetics on non-player entities

                            var applied = false
                            if (cosmeticSlotClass != null) {
                                // try to find matching enum constant (relaxed matching)
                                val cosmeticEnum = cosmeticSlotClass.enumConstants?.firstOrNull { c ->
                                    try {
                                        val s = c.toString()
                                        s.equals(slotKey, ignoreCase = true) ||
                                                s.contains(slotKey, ignoreCase = true) ||
                                                s.replace("_", "").contains(slotKey.replace("_", ""), ignoreCase = true)
                                    } catch (_: Throwable) { false }
                                }

                                if (cosmeticEnum != null) {
                                    // 1) Try (Player, CosmeticSlot, List<Player>) if underlying is Player
                                    val playerMethod = HMCC_PACKET_MANAGER_CLASS.methods.firstOrNull { m ->
                                        if (m.name != "equipmentSlotUpdate") return@firstOrNull false
                                        val pts = m.parameterTypes
                                        pts.size == 3 && pts[0] == org.bukkit.entity.Player::class.java &&
                                                pts[1] == cosmeticSlotClass && java.util.List::class.java.isAssignableFrom(pts[2])
                                    }
                                    if (!applied && playerMethod != null && underlyingBukkitVal is org.bukkit.entity.Player) {
                                        try {
                                            playerMethod.invoke(null, underlyingBukkitVal, cosmeticEnum, viewers)
                                            applied = true
                                            appliedSlots.add(slotKey.uppercase())
                                            logger.fine("HmcCosmeticExtension: applied HMCC playerMethod for slot=$slotKey id=$cosmeticId")
                                        } catch (_: Throwable) { logger.fine("HmcCosmeticExtension: playerMethod invoke failed for slot=$slotKey") }
                                    }

                                    // 2) Try (CosmeticUser, CosmeticSlot, List<Player>)
                                    val userObj = try {
                                        if (getUserMeth != null && uuidVal != null) getUserMeth.invoke(null, uuidVal) else null
                                    } catch (_: Throwable) { null }

                                    if (!applied && userObj != null) {
                                        val userFirst = HMCC_PACKET_MANAGER_CLASS.methods.firstOrNull { m ->
                                            if (m.name != "equipmentSlotUpdate") return@firstOrNull false
                                            val pts = m.parameterTypes
                                            pts.size == 3 && pts[0].name == "com.hibiscusmc.hmccosmetics.user.CosmeticUser" &&
                                                    pts[1] == cosmeticSlotClass && java.util.List::class.java.isAssignableFrom(pts[2])
                                        }
                                        if (userFirst != null) {
                                            try {
                                                userFirst.invoke(null, userObj, cosmeticEnum, viewers)
                                                applied = true
                                                appliedSlots.add(slotKey.uppercase())
                                                logger.fine("HmcCosmeticExtension: applied HMCC userFirst for slot=$slotKey id=$cosmeticId")
                                            } catch (_: Throwable) { logger.fine("HmcCosmeticExtension: userFirst invoke failed for slot=$slotKey") }
                                        }
                                    }

                                    // 3) Try (int, CosmeticUser, CosmeticSlot, List<Player>) - when entityId + userObj available
                                    if (!applied && entityIdVal != null && userObj != null) {
                                        val candidate = HMCC_PACKET_MANAGER_CLASS.methods.firstOrNull { m ->
                                            if (m.name != "equipmentSlotUpdate") return@firstOrNull false
                                            val pts = m.parameterTypes
                                            if (pts.size != 4) return@firstOrNull false
                                            (pts[0] == java.lang.Integer.TYPE || pts[0] == java.lang.Integer::class.java) &&
                                                    pts[1].name == "com.hibiscusmc.hmccosmetics.user.CosmeticUser" &&
                                                    pts[2] == cosmeticSlotClass
                                        }
                                        if (candidate != null) {
                                            try {
                                                candidate.invoke(null, entityIdVal, userObj, cosmeticEnum, viewers)
                                                applied = true
                                                appliedSlots.add(slotKey.uppercase())
                                                logger.fine("HmcCosmeticExtension: applied HMCC candidate(int,user,slot) for slot=$slotKey id=$cosmeticId")
                                            } catch (_: Throwable) { logger.fine("HmcCosmeticExtension: candidate invoke failed for slot=$slotKey") }
                                        }
                                    }

                                    // 4) Try (int, CosmeticSlot, List<Player>) - handle NPCs by entityId without CosmeticUser
                                    if (!applied && entityIdVal != null) {
                                        val intSlotCandidate = HMCC_PACKET_MANAGER_CLASS.methods.firstOrNull { m ->
                                            if (m.name != "equipmentSlotUpdate") return@firstOrNull false
                                            val pts = m.parameterTypes
                                            pts.size == 3 && (pts[0] == java.lang.Integer.TYPE || pts[0] == java.lang.Integer::class.java) &&
                                                    pts[1] == cosmeticSlotClass && java.util.List::class.java.isAssignableFrom(pts[2])
                                        }
                                        if (intSlotCandidate != null) {
                                            try {
                                                intSlotCandidate.invoke(null, entityIdVal, cosmeticEnum, viewers)
                                                applied = true
                                                appliedSlots.add(slotKey.uppercase())
                                                logger.fine("HmcCosmeticExtension: applied HMCC intSlotCandidate for slot=$slotKey id=$cosmeticId")
                                            } catch (_: Throwable) { logger.fine("HmcCosmeticExtension: intSlotCandidate invoke failed for slot=$slotKey") }
                                        }
                                    }
                                }
                            }
                            if (!applied) {
                                // Attempt NPC/client-side fallback when HMCC APIs didn't apply and we have an entityId + location.
                                // Strategy: spawn client-side armorstand(s) (and pufferfish for balloons), equip them, then mount them to the NPC.
                                if (entityIdVal != null && locVal != null) {
                                    try {
                                        val itemForDisplay = try { HmcCosmeticService.getCosmeticItem(cosmeticId) } catch (_: Throwable) { null }
                                        val serverUtilsCls = try { Class.forName("me.lojosho.hibiscuscommons.util.ServerUtils") } catch (_: Throwable) { null }
                                        val nextIdAny = try { serverUtilsCls?.getMethod("getNextEntityId")?.invoke(null) } catch (_: Throwable) { null }
                                        val armorStandId = (nextIdAny as? Int) ?: null

                                        if (armorStandId != null) {
                                            try {
                                                // Spawn armorstand client-side
                                                try {
                                                    val spawnMeth = HMCC_PACKET_MANAGER_CLASS.getMethod("sendEntitySpawnPacket", org.bukkit.Location::class.java, Int::class.javaPrimitiveType, org.bukkit.entity.EntityType::class.java, java.util.UUID::class.java, java.util.List::class.java)
                                                    spawnMeth.invoke(null, locVal, armorStandId, org.bukkit.entity.EntityType.ARMOR_STAND, java.util.UUID.randomUUID(), viewers)
                                                } catch (_: Throwable) {
                                                    try {
                                                        val spawnAlt = HMCC_PACKET_MANAGER_CLASS.getMethod("sendEntitySpawnPacket", org.bukkit.Location::class.java, Int::class.javaPrimitiveType, org.bukkit.entity.EntityType::class.java, java.util.UUID::class.java)
                                                        spawnAlt.invoke(null, locVal, armorStandId, org.bukkit.entity.EntityType.ARMOR_STAND, java.util.UUID.randomUUID())
                                                    } catch (_: Throwable) {}
                                                }

                                                // Armorstand metadata
                                                try {
                                                    val metaM = HMCC_PACKET_MANAGER_CLASS.getMethod("sendArmorstandMetadata", Int::class.javaPrimitiveType, java.util.List::class.java)
                                                    metaM.invoke(null, armorStandId, viewers)
                                                } catch (_: Throwable) {}

                                                // Equip helmet on armorstand if we have an item
                                                if (itemForDisplay != null) {
                                                    try {
                                                        val pktCls = Class.forName("me.lojosho.hibiscuscommons.util.packets.PacketManager")
                                                        val equipM = pktCls.getMethod("equipmentSlotUpdate", Int::class.javaPrimitiveType, org.bukkit.inventory.EquipmentSlot::class.java, org.bukkit.inventory.ItemStack::class.java, java.util.List::class.java)
                                                        equipM.invoke(null, armorStandId, org.bukkit.inventory.EquipmentSlot.HEAD, itemForDisplay, viewers)
                                                    } catch (_: Throwable) {}
                                                }

                                                // For BALLOON we also spawn a pufferfish and leash it to the NPC, then mount the armorstand to the pufferfish
                                                if (slotKey.equals("BALLOON", ignoreCase = true)) {
                                                    try {
                                                        val nextPufferAny = try { serverUtilsCls?.getMethod("getNextEntityId")?.invoke(null) } catch (_: Throwable) { null }
                                                        val pufferId = (nextPufferAny as? Int) ?: null
                                                        if (pufferId != null) {
                                                            // spawn pufferfish
                                                            try {
                                                                val spawnP = HMCC_PACKET_MANAGER_CLASS.getMethod("sendEntitySpawnPacket", org.bukkit.Location::class.java, Int::class.javaPrimitiveType, org.bukkit.entity.EntityType::class.java, java.util.UUID::class.java, java.util.List::class.java)
                                                                spawnP.invoke(null, locVal, pufferId, org.bukkit.entity.EntityType.PUFFERFISH, java.util.UUID.randomUUID(), viewers)
                                                            } catch (_: Throwable) {
                                                                try {
                                                                    val spawnAlt = HMCC_PACKET_MANAGER_CLASS.getMethod("sendEntitySpawnPacket", org.bukkit.Location::class.java, Int::class.javaPrimitiveType, org.bukkit.entity.EntityType::class.java, java.util.UUID::class.java)
                                                                    spawnAlt.invoke(null, locVal, pufferId, org.bukkit.entity.EntityType.PUFFERFISH, java.util.UUID.randomUUID())
                                                                } catch (_: Throwable) {}
                                                            }

                                                            // leash pufferfish to the NPC (attach visual rope)
                                                            try {
                                                                val leashM = HMCC_PACKET_MANAGER_CLASS.getMethod("sendLeashPacket", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, org.bukkit.Location::class.java)
                                                                leashM.invoke(null, pufferId, entityIdVal, locVal)
                                                            } catch (_: Throwable) {
                                                                try {
                                                                    val leashAlt = HMCC_PACKET_MANAGER_CLASS.getMethod("sendLeashPacket", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, java.util.List::class.java)
                                                                    leashAlt.invoke(null, pufferId, entityIdVal, viewers)
                                                                } catch (_: Throwable) {}
                                                            }

                                                            // Mount armorstand onto the pufferfish so the model follows the puffer
                                                            try {
                                                                val ridM = HMCC_PACKET_MANAGER_CLASS.getMethod("sendRidingPacket", Int::class.javaPrimitiveType, IntArray::class.java, java.util.List::class.java)
                                                                ridM.invoke(null, pufferId, intArrayOf(armorStandId), viewers)
                                                            } catch (_: Throwable) {}
                                                        }
                                                    } catch (_: Throwable) {
                                                        logger.fine("HmcCosmeticExtension: balloon npc fallback failed for slot=$slotKey id=$cosmeticId")
                                                    }
                                                } else {
                                                    // generic: mount armorstand to NPC
                                                    try {
                                                        val ridM = HMCC_PACKET_MANAGER_CLASS.getMethod("sendRidingPacket", Int::class.javaPrimitiveType, IntArray::class.java, java.util.List::class.java)
                                                        ridM.invoke(null, entityIdVal, intArrayOf(armorStandId), viewers)
                                                    } catch (_: Throwable) {}
                                                }

                                                logger.info("HmcCosmeticExtension: sent NPC armorstand fallback for slot=$slotKey id=$cosmeticId")
                                                org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: sent NPC armorstand fallback for slot=$slotKey id=$cosmeticId")
                                                applied = true
                                            } catch (_: Throwable) {
                                                logger.fine("HmcCosmeticExtension: npc fallback inner error for slot=$slotKey id=$cosmeticId")
                                            }
                                        }
                                    } catch (_: Throwable) {
                                        logger.fine("HmcCosmeticExtension: npc fallback error for slot=$slotKey id=$cosmeticId")
                                    }
                                }

                                if (!applied) {
                                    logger.fine("HmcCosmeticExtension: HMCC-specific fallback not applied for slot=$slotKey id=$cosmeticId")
                                }
                            }
                        } catch (_: Throwable) {
                            logger.fine("HmcCosmeticExtension: error applying HMCC-specific slot=$slotKey id=$cosmeticId")
                        }
                    }
                }
            } catch (t: Throwable) {
                logger.fine("HmcCosmeticExtension: HMCC packet-level fallback error: ${t.message}")
            }
        } catch (t: Throwable) {
            logger.fine("HmcCosmeticExtension: error preparing viewers/map: ${t.message}")
        }
    }

    // After HMCC packet attempts: if BACKPACK/BALLOON still not applied, try entity-handler packet fallback for NPCs
    try {
        // Only attempt handler-based spawning if we have a location and entityId
        if (locVal != null && entityIdVal != null) {
            // BACKPACK
            val backpackKey = "BACKPACK"
            val backpackId = entries[backpackKey]
            if (!backpackId.isNullOrBlank() && !appliedSlots.contains(backpackKey)) {
                try {
                    val item = HmcCosmeticService.getCosmeticItem(backpackId)
                    HmcCosmeticEntityHandler.spawnAndMountArmorStand(locVal, entityIdVal, item, viewers)
                    appliedSlots.add(backpackKey)
                    org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: handler spawned backpack fallback for id=$backpackId")
                } catch (_: Throwable) {
                    logger.fine("HmcCosmeticExtension: handler backpack fallback failed for id=$backpackId")
                }
            }

            // BALLOON
            val balloonKey = "BALLOON"
            val balloonId = entries[balloonKey]
            if (!balloonId.isNullOrBlank() && !appliedSlots.contains(balloonKey)) {
                try {
                    val item = HmcCosmeticService.getCosmeticItem(balloonId)
                    HmcCosmeticEntityHandler.spawnBalloonPufferAndMount(locVal, entityIdVal, item, viewers)
                    appliedSlots.add(balloonKey)
                    org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: handler spawned balloon fallback for id=$balloonId")
                } catch (_: Throwable) {
                    logger.fine("HmcCosmeticExtension: handler balloon fallback failed for id=$balloonId")
                }
            }
        }
    } catch (e: Throwable) {
        logger.fine("HmcCosmeticExtension: handler fallback final step failed: ${e.message}")
    }

    // 3) Apply helmet offset
    val helmetId = entries["HELMET"] ?: ""
    try {
        val offset = property.helmetOffset ?: if (helmetId.isNotBlank()) {
            try {
                val item = HmcCosmeticService.getCosmeticItem(helmetId)
                if (item?.itemMeta?.hasCustomModelData() == true) 0.35 else 0.22
            } catch (_: Throwable) { 0.22 }
        } else null

        if (offset != null && offset != 0.0) {
            try {
                var offsetApplied = false
                val entityClass = entity::class.java

                // Prefer calling applyProperties(List<EntityProperty>) on TypeWriter wrapper entities
                val applyPropertiesMethod = entityClass.methods.firstOrNull { m ->
                    m.name == "applyProperties" && m.parameterCount == 1
                }

                if (applyPropertiesMethod != null) {
                    val translationClass = try { Class.forName("com.typewritermc.entity.entries.data.minecraft.display.TranslationProperty") } catch (_: Throwable) { null }
                    val vectorClass = try { Class.forName("com.typewritermc.core.utils.point.Vector") } catch (_: Throwable) { null }

                    if (translationClass != null && vectorClass != null) {
                        try {
                            // Create Vector(x=0.0,y=offset,z=0.0)
                            val vectorCtor = vectorClass.constructors.firstOrNull { it.parameterCount == 3 }
                            val vectorInstance = vectorCtor?.newInstance(0.0, offset, 0.0)

                            // Create TranslationProperty(Vector)
                            val transCtor = translationClass.constructors.firstOrNull { ctor ->
                                val pts = ctor.parameterTypes
                                pts.size == 1 && pts[0] == vectorClass
                            }
                            val translationInstance = transCtor?.newInstance(vectorInstance)

                                if (translationInstance != null) {
                                // invoke applyProperties with a java.util.List containing the translation property
                                val singletonList = java.util.Collections.singletonList(translationInstance)
                                applyPropertiesMethod.invoke(entity, singletonList)
                                logger.info("HmcCosmeticExtension: Applied helmet offset $offset via applyProperties on ${entityClass.name}")
                                org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: Applied helmet offset $offset via applyProperties on ${entityClass.name}")
                                // done
                                offsetApplied = true
                            }
                        } catch (e: Throwable) {
                            logger.fine("HmcCosmeticExtension: reflection create/apply TranslationProperty failed: ${e.message}")
                        }
                    }
                } else {
                    logger.fine("HmcCosmeticExtension: applyProperties method not found on entity ${entity::class.java.name}")
                }

                // Fallback: as a last resort, adjust nearby TextDisplay-like entities by sending teleport packets to viewers
                if (!offsetApplied && locVal != null) {
                    try {
                        val world = locVal.world
                        val nearby = world?.getNearbyEntities(locVal, 1.5, 2.5, 1.5)
                        if (nearby != null) {
                            var adjusted = false

                            // Resolve NMS packet handler if available (prefer sending teleport packets to viewers)
                            val nmsHandlersClass = try { Class.forName("me.lojosho.hibiscuscommons.nms.NMSHandlers") } catch (_: Throwable) { null }
                            val packetHandler = try {
                                if (nmsHandlersClass != null) {
                                    val getHandler = nmsHandlersClass.getMethod("getHandler")
                                    val handler = getHandler.invoke(null)
                                    handler?.javaClass?.getMethod("getPacketHandler")?.invoke(handler)
                                } else null
                            } catch (_: Throwable) { null }

                            for (e in nearby) {
                                val tn = e.javaClass.simpleName.uppercase()
                                val typeName = e.type.name.uppercase()
                                if (tn.contains("TEXTDISPLAY") || tn.contains("HOLOGRAM") || typeName.contains("DISPLAY") || tn.contains("ITEMDISPLAY") || tn.contains("ARMORSTAND")) {
                                    try {
                                        val newLoc = e.location.clone().add(0.0, offset, 0.0)

                                        // If we have a packet handler, send teleport packets to viewers to avoid server-side overrides
                                        if (packetHandler != null) {
                                            try {
                                                // Try to find sendTeleportPacket overloads
                                                val intClass = Integer.TYPE
                                                // common signatures observed:
                                                // sendTeleportPacket(int entityId, double x, double y, double z, float yaw, float pitch, boolean onGround, Player viewer)
                                                // sendTeleportPacket(int entityId, double x, double y, double z, float yaw, float pitch, boolean onGround, List<Player> viewers)
                                                val methods = packetHandler.javaClass.methods.filter { it.name == "sendTeleportPacket" }
                                                var invoked = false
                                                for (m in methods) {
                                                    try {
                                                        val pts = m.parameterTypes
                                                        if (pts.size == 8 && pts[0] == intClass && pts[1] == java.lang.Double.TYPE && pts[2] == java.lang.Double.TYPE && pts[3] == java.lang.Double.TYPE &&
                                                            pts[4] == java.lang.Float.TYPE && pts[5] == java.lang.Float.TYPE && pts[6] == java.lang.Boolean.TYPE) {
                                                            // last param could be Player or List
                                                            if (pts[7] == org.bukkit.entity.Player::class.java) {
                                                                // send individually to viewers
                                                                for (v in viewers) {
                                                                    try {
                                                                        m.invoke(packetHandler, e.entityId, newLoc.x, newLoc.y, newLoc.z, newLoc.yaw.toFloat(), newLoc.pitch.toFloat(), false, v)
                                                                    } catch (_: Throwable) {}
                                                                }
                                                                invoked = true
                                                                break
                                                            } else if (java.util.List::class.java.isAssignableFrom(pts[7])) {
                                                                try {
                                                                    m.invoke(packetHandler, e.entityId, newLoc.x, newLoc.y, newLoc.z, newLoc.yaw.toFloat(), newLoc.pitch.toFloat(), false, viewers)
                                                                    invoked = true
                                                                    break
                                                                } catch (_: Throwable) {}
                                                            }
                                                        }
                                                    } catch (_: Throwable) {}
                                                }
                                                if (invoked) {
                                                    adjusted = true
                                                    logger.fine("HmcCosmeticExtension: Sent teleport packet for ${e.javaClass.simpleName} by $offset")
                                                    continue
                                                }
                                            } catch (_: Throwable) {
                                                // fall back to Bukkit teleport
                                            }
                                        }

                                        // Last fallback to server-side teleport (may be overwritten by wrapper updates)
                                        try {
                                            e.teleport(newLoc)
                                            adjusted = true
                                            logger.fine("HmcCosmeticExtension: Teleported ${e.javaClass.simpleName} by $offset for helmet offset (server fallback)")
                                        } catch (_: Throwable) {
                                            // ignore
                                        }
                                    } catch (_: Throwable) {
                                        // ignore individual entity failures
                                    }
                                }
                            }
                            if (!adjusted) logger.fine("HmcCosmeticExtension: no nearby display entities found to adjust for helmet offset")
                        }
                    } catch (e: Throwable) {
                        logger.fine("HmcCosmeticExtension: fallback teleport/packet attempt failed: ${e.message}")
                    }
                } else {
                    logger.fine("HmcCosmeticExtension: location unavailable, cannot apply helmet offset fallback")
                }
            } catch (t: Throwable) {
                logger.fine("HmcCosmeticExtension: helmet offset processing failed: ${t.message}")
            }
        }
    } catch (t: Throwable) {
        logger.fine("HmcCosmeticExtension: helmet offset processing failed: ${t.message}")
    }

    // 4) Create ArmorStand entities for backpack/balloon and mount them as passengers
    try {
        val backpackId = entries["BACKPACK"]
        val balloonId = entries["BALLOON"]
        
        if (!backpackId.isNullOrBlank() || !balloonId.isNullOrBlank()) {
            // Try to get addPassenger method from entity
            val entityClass = entity::class.java
            val addPassengerMethod = entityClass.methods.find { 
                it.name == "addPassenger" && it.parameterCount == 1 
            }
            
            if (addPassengerMethod != null && locVal != null) {
                // Create backpack ArmorStand
                if (!backpackId.isNullOrBlank()) {
                    try {
                        val backpackItem = HmcCosmeticService.getCosmeticItem(backpackId)
                        if (backpackItem != null) {
                            val backpackLoc = locVal.clone().add(0.0, -0.1, 0.0)
                            val backpackStand = locVal.world?.spawn(backpackLoc, org.bukkit.entity.ArmorStand::class.java) { stand ->
                                stand.isInvisible = true
                                stand.isSmall = true
                                stand.setGravity(false)
                                stand.isMarker = true
                                stand.equipment?.helmet = backpackItem
                                stand.persistentDataContainer.set(
                                    org.bukkit.NamespacedKey(plugin, "hmccosmetic_backpack"),
                                    org.bukkit.persistence.PersistentDataType.STRING,
                                    backpackId
                                )
                            }
                            logger.info("HmcCosmeticExtension: Created backpack ArmorStand for $backpackId")
                            org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: Created backpack ArmorStand for $backpackId")
                        }
                    } catch (e: Exception) {
                        logger.fine("HmcCosmeticExtension: Failed to create backpack: ${e.message}")
                    }
                }
                
                // Create balloon ArmorStand
                if (!balloonId.isNullOrBlank()) {
                    try {
                        val balloonItem = HmcCosmeticService.getCosmeticItem(balloonId)
                        if (balloonItem != null) {
                            val balloonLoc = locVal.clone().add(0.0, 2.5, 0.0)
                            val balloonStand = locVal.world?.spawn(balloonLoc, org.bukkit.entity.ArmorStand::class.java) { stand ->
                                stand.isInvisible = true
                                stand.isSmall = true
                                stand.setGravity(false)
                                stand.isMarker = true
                                stand.equipment?.helmet = balloonItem
                                stand.persistentDataContainer.set(
                                    org.bukkit.NamespacedKey(plugin, "hmccosmetic_balloon"),
                                    org.bukkit.persistence.PersistentDataType.STRING,
                                    balloonId
                                )
                            }
                            logger.info("HmcCosmeticExtension: Created balloon ArmorStand for $balloonId")
                            org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: Created balloon ArmorStand for $balloonId")
                        }
                    } catch (e: Exception) {
                        logger.fine("HmcCosmeticExtension: Failed to create balloon: ${e.message}")
                    }
                }
            } else {
                logger.fine("HmcCosmeticExtension: Entity does not support addPassenger or location unavailable")
            }
        }
    } catch (t: Throwable) {
        logger.fine("HmcCosmeticExtension: backpack/balloon creation failed: ${t.message}")
    }

    // Final logging summary
    logger.info("HmcCosmeticExtension: appliedSlots=$appliedSlots for entity=${entity?.javaClass?.name}")
    org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: appliedSlots=$appliedSlots for entity=${entity?.javaClass?.name}")
}
