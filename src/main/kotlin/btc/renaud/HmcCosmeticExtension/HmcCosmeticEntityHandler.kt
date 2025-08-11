package btc.renaud.HmcCosmeticExtension

import com.typewritermc.engine.paper.logger
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import java.util.*

/**
 * Helper that encapsulates packet-based spawning/mounting logic for HMCC cosmetics
 * (backpack / balloon) and lightweight helpers for applying helmet offsets.
 *
 * This class is intentionally conservative and uses reflection to call HMCC packet helpers
 * when available so the project can compile without HMCC on the classpath.
 */
object HmcCosmeticEntityHandler {

    private val HMCC_PACKET_MANAGER_CLASS: Class<*>? = try {
        Class.forName("com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager")
    } catch (e: Throwable) {
        null
    }

    private val SERVER_UTILS_CLASS: Class<*>? = try {
        Class.forName("me.lojosho.hibiscuscommons.util.ServerUtils")
    } catch (e: Throwable) {
        null
    }

    /**
     * Best-effort: try to obtain a Bukkit Entity from an arbitrary wrapper object.
     * Returns null if none found.
     */
    fun extractBukkitEntity(wrapper: Any?): Entity? {
        if (wrapper == null) return null
        if (wrapper is Entity) return wrapper

        val cls = wrapper::class.java
        val candidates = listOf("getBukkitEntity", "toBukkitEntity", "getEntity", "entity")
        for (m in candidates) {
            try {
                val meth = cls.getMethod(m)
                val res = meth.invoke(wrapper)
                if (res is Entity) return res
            } catch (_: Throwable) {
            }
        }

        // try fields
        for (fName in listOf("entity", "bukkitEntity", "bukkit")) {
            try {
                val f = cls.getDeclaredField(fName)
                f.isAccessible = true
                val r = f.get(wrapper)
                if (r is Entity) return r
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * Spawn a client-side armorstand (for backpack) and mount it to the mountEntityId (NPC/entity).
     * This uses HMCCPacketManager reflection - no-op if HMCC not present.
     *
     * viewers may be null; when null this will attempt to resolve viewers via HMCCPacketManager.getViewers(location).
     */
    fun spawnAndMountArmorStand(location: Location, mountEntityId: Int, itemStack: org.bukkit.inventory.ItemStack?, viewers: List<Player>? = null) {
        try {
            if (HMCC_PACKET_MANAGER_CLASS == null) {
                logger.fine("HmcCosmeticExtension: HMCC packet manager not present; cannot spawn armorstand fallback")
                return
            }

            // Resolve viewers
            val actualViewers = viewers ?: try {
                val getViewers = HMCC_PACKET_MANAGER_CLASS.getMethod("getViewers", Location::class.java)
                @Suppress("UNCHECKED_CAST")
                getViewers.invoke(null, location) as? List<Player> ?: org.bukkit.Bukkit.getServer().onlinePlayers.toList()
            } catch (_: Throwable) {
                org.bukkit.Bukkit.getServer().onlinePlayers.toList()
            }

            // next entity id
            val nextId = try {
                SERVER_UTILS_CLASS?.getMethod("getNextEntityId")?.invoke(null) as? Int
            } catch (_: Throwable) {
                null
            } ?: return

            // spawn armorstand
            try {
                val spawnMeth = HMCC_PACKET_MANAGER_CLASS.getMethod("sendEntitySpawnPacket", Location::class.java, Int::class.javaPrimitiveType, EntityType::class.java, UUID::class.java, List::class.java)
                spawnMeth.invoke(null, location, nextId, EntityType.ARMOR_STAND, UUID.randomUUID(), actualViewers)
            } catch (_: Throwable) {
                // try shorter overload
                try {
                    val spawnAlt = HMCC_PACKET_MANAGER_CLASS.getMethod("sendEntitySpawnPacket", Location::class.java, Int::class.javaPrimitiveType, EntityType::class.java, UUID::class.java)
                    spawnAlt.invoke(null, location, nextId, EntityType.ARMOR_STAND, UUID.randomUUID())
                } catch (t: Throwable) {
                    logger.fine("HmcCosmeticExtension: spawn armorstand failed: ${t.message}")
                }
            }

            // armorstand metadata
            try {
                val metaM = HMCC_PACKET_MANAGER_CLASS.getMethod("sendArmorstandMetadata", Int::class.javaPrimitiveType, List::class.java)
                metaM.invoke(null, nextId, actualViewers)
            } catch (_: Throwable) { }

            // equip helmet if provided (PacketManager.equipmentSlotUpdate)
            if (itemStack != null) {
                try {
                    val pktCls = Class.forName("me.lojosho.hibiscuscommons.util.packets.PacketManager")
                    val equipM = pktCls.getMethod("equipmentSlotUpdate", Int::class.javaPrimitiveType, org.bukkit.inventory.EquipmentSlot::class.java, org.bukkit.inventory.ItemStack::class.java, List::class.java)
                    equipM.invoke(null, nextId, org.bukkit.inventory.EquipmentSlot.HEAD, itemStack, actualViewers)
                } catch (_: Throwable) { }
            }

            // mount to NPC
            try {
                val ridM = HMCC_PACKET_MANAGER_CLASS.getMethod("sendRidingPacket", Int::class.javaPrimitiveType, IntArray::class.java, List::class.java)
                ridM.invoke(null, mountEntityId, intArrayOf(nextId), actualViewers)
            } catch (_: Throwable) {
                // older overloads
                try {
                    val singleRid = HMCC_PACKET_MANAGER_CLASS.getMethod("sendRidingPacket", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, List::class.java)
                    singleRid.invoke(null, mountEntityId, nextId, actualViewers)
                } catch (_: Throwable) { }
            }

            org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: spawnAndMountArmorStand -> spawnedId=$nextId mountedTo=$mountEntityId")
        } catch (t: Throwable) {
            logger.fine("HmcCosmeticExtension: spawnAndMountArmorStand failed: ${t.message}")
        }
    }

    /**
     * Spawn a pufferfish + armorstand and mount armorstand to puffer, then leash puffer to mountEntityId.
     * This matches HMCC balloon fallback sequence.
     */
    fun spawnBalloonPufferAndMount(location: Location, mountEntityId: Int, itemStack: org.bukkit.inventory.ItemStack?, viewers: List<Player>? = null) {
        try {
            if (HMCC_PACKET_MANAGER_CLASS == null) {
                logger.fine("HmcCosmeticExtension: HMCC packet manager not present; cannot spawn balloon fallback")
                return
            }

            val actualViewers = viewers ?: try {
                val getViewers = HMCC_PACKET_MANAGER_CLASS.getMethod("getViewers", Location::class.java)
                @Suppress("UNCHECKED_CAST")
                getViewers.invoke(null, location) as? List<Player> ?: org.bukkit.Bukkit.getServer().onlinePlayers.toList()
            } catch (_: Throwable) {
                org.bukkit.Bukkit.getServer().onlinePlayers.toList()
            }

            val nextIdArmor = try { SERVER_UTILS_CLASS?.getMethod("getNextEntityId")?.invoke(null) as? Int } catch (_: Throwable) { null }
            val nextIdPuffer = try { SERVER_UTILS_CLASS?.getMethod("getNextEntityId")?.invoke(null) as? Int } catch (_: Throwable) { null }

            if (nextIdArmor == null || nextIdPuffer == null) {
                logger.fine("HmcCosmeticExtension: cannot obtain entity ids for balloon fallback")
                return
            }

            // spawn armorstand
            try {
                val spawnMeth = HMCC_PACKET_MANAGER_CLASS.getMethod("sendEntitySpawnPacket", Location::class.java, Int::class.javaPrimitiveType, EntityType::class.java, UUID::class.java, List::class.java)
                spawnMeth.invoke(null, location, nextIdArmor, EntityType.ARMOR_STAND, UUID.randomUUID(), actualViewers)
            } catch (_: Throwable) { }

            // armorstand metadata + equip
            try {
                val metaM = HMCC_PACKET_MANAGER_CLASS.getMethod("sendArmorstandMetadata", Int::class.javaPrimitiveType, List::class.java)
                metaM.invoke(null, nextIdArmor, actualViewers)
            } catch (_: Throwable) { }

            if (itemStack != null) {
                try {
                    val pktCls = Class.forName("me.lojosho.hibiscuscommons.util.packets.PacketManager")
                    val equipM = pktCls.getMethod("equipmentSlotUpdate", Int::class.javaPrimitiveType, org.bukkit.inventory.EquipmentSlot::class.java, org.bukkit.inventory.ItemStack::class.java, List::class.java)
                    equipM.invoke(null, nextIdArmor, org.bukkit.inventory.EquipmentSlot.HEAD, itemStack, actualViewers)
                } catch (_: Throwable) { }
            }

            // spawn pufferfish
            try {
                val spawnP = HMCC_PACKET_MANAGER_CLASS.getMethod("sendEntitySpawnPacket", Location::class.java, Int::class.javaPrimitiveType, EntityType::class.java, UUID::class.java, List::class.java)
                spawnP.invoke(null, location, nextIdPuffer, EntityType.PUFFERFISH, UUID.randomUUID(), actualViewers)
            } catch (_: Throwable) { }

            // leash puffer to mount
            try {
                val leashM = HMCC_PACKET_MANAGER_CLASS.getMethod("sendLeashPacket", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Location::class.java)
                leashM.invoke(null, nextIdPuffer, mountEntityId, location)
            } catch (_: Throwable) {
                try {
                    val leashAlt = HMCC_PACKET_MANAGER_CLASS.getMethod("sendLeashPacket", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, List::class.java)
                    leashAlt.invoke(null, nextIdPuffer, mountEntityId, actualViewers)
                } catch (_: Throwable) { }
            }

            // mount armor to puffer
            try {
                val ridM = HMCC_PACKET_MANAGER_CLASS.getMethod("sendRidingPacket", Int::class.javaPrimitiveType, IntArray::class.java, List::class.java)
                ridM.invoke(null, nextIdPuffer, intArrayOf(nextIdArmor), actualViewers)
            } catch (_: Throwable) { }

            org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: spawnBalloonPufferAndMount -> armorId=$nextIdArmor pufferId=$nextIdPuffer mount=$mountEntityId")
        } catch (t: Throwable) {
            logger.fine("HmcCosmeticExtension: spawnBalloonPufferAndMount failed: ${t.message}")
        }
    }

    /**
     * Attempt to apply a TranslationProperty by invoking applyProperties on the wrapper or by
     * teleporting nearby display-like bukkit entities. This is a small convenience wrapper
     * used by HmcCosmeticData but isolated for easier iteration.
     */
    fun applyHelmetOffset(entityWrapper: Any?, offset: Double?) {
        if (offset == null) return
        if (entityWrapper == null) return

        try {
            val cls = entityWrapper::class.java
            val applyMethod = cls.methods.firstOrNull { m -> m.name == "applyProperties" && m.parameterCount == 1 }
            if (applyMethod != null) {
                // Try to construct TranslationProperty class reflectively
                val translationClass = try { Class.forName("com.typewritermc.entity.entries.data.minecraft.display.TranslationProperty") } catch (_: Throwable) { null }
                val vectorClass = try { Class.forName("com.typewritermc.core.utils.point.Vector") } catch (_: Throwable) { null }
                if (translationClass != null && vectorClass != null) {
                    try {
                        val vectorCtor = vectorClass.constructors.firstOrNull { it.parameterCount == 3 }
                        val vectorInstance = vectorCtor?.newInstance(0.0, offset, 0.0)
                        val transCtor = translationClass.constructors.firstOrNull { it.parameterCount == 1 }
                        val translationInstance = transCtor?.newInstance(vectorInstance)
                        if (translationInstance != null) {
                            val list = java.util.Collections.singletonList(translationInstance)
                            applyMethod.invoke(entityWrapper, list)
                            org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: applyHelmetOffset via applyProperties succeeded")
                            return
                        }
                    } catch (_: Throwable) { }
                }
            }

            // Fallback: if we can obtain underlying bukkit entity and location, teleport nearby displays (conservative)
            val bukkit = extractBukkitEntity(entityWrapper)
            val loc = try { bukkit?.location } catch (_: Throwable) { null }
            if (loc != null) {
                val nearby = loc.world?.getNearbyEntities(loc, 1.5, 2.5, 1.5)
                nearby?.forEach { e ->
                    try {
                        val name = e.javaClass.simpleName.uppercase()
                        val typeName = e.type.name.uppercase()
                        if (name.contains("TEXTDISPLAY") || name.contains("HOLOGRAM") || typeName.contains("DISPLAY") || name.contains("ITEMDISPLAY") || name.contains("ARMORSTAND")) {
                            val newLoc = e.location.clone().add(0.0, offset, 0.0)
                            e.teleport(newLoc)
                        }
                    } catch (_: Throwable) { }
                }
                org.bukkit.Bukkit.getLogger().info("HmcCosmeticExtension: applyHelmetOffset fallback completed")
            }
        } catch (t: Throwable) {
            logger.fine("HmcCosmeticExtension: applyHelmetOffset failed: ${t.message}")
        }
    }
}
