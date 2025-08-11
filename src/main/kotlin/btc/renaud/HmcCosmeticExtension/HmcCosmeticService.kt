package btc.renaud.HmcCosmeticExtension

import com.typewritermc.engine.paper.logger
import org.bukkit.Color
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.UUID

/**
 * Minimal reflective helpers that delegate to HMCCosmetics' public API (HMCCosmeticsAPI).
 *
 * These methods are purposely reflective to avoid hard compile-time coupling when HMCCosmetics
 * is not present at compile time. They return boolean success/failure so callers can proceed
 * safely.
 */
object HmcCosmeticService {
    private val apiClass: Class<*>? = try {
        Class.forName("com.hibiscusmc.hmccosmetics.api.HMCCosmeticsAPI")
    } catch (e: ClassNotFoundException) {
        null
    }

    private val getUserMethod: Method? = try {
        apiClass?.getMethod("getUser", UUID::class.java)
    } catch (_: Exception) {
        null
    }

    private val getCosmeticMethod: Method? = try {
        apiClass?.getMethod("getCosmetic", String::class.java)
    } catch (_: Exception) {
        null
    }

    private val equipCosmeticMethod: Method? = try {
        // equipCosmetic(CosmeticUser, Cosmetic, Color)
        apiClass?.getMethod("equipCosmetic", Class.forName("com.hibiscusmc.hmccosmetics.user.CosmeticUser"), Class.forName("com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic"), Color::class.java)
    } catch (_: Exception) {
        null
    }

    /**
     * Apply a cosmetic to a player by UUID.
     * Returns true if the operation succeeded (cosmetic + user found, equip invoked).
     */
    @JvmStatic
    fun applyToUuid(uuid: UUID, cosmeticId: String, color: Color? = null): Boolean {
        if (apiClass == null) {
            logger.fine("HmcCosmeticService: HMCCosmetics API not present")
            return false
        }

        try {
            val user = getUserMethod?.invoke(null, uuid) ?: return false
            val cosmetic = getCosmeticMethod?.invoke(null, cosmeticId) ?: return false

            // call equipCosmetic(user, cosmetic, color)
            if (equipCosmeticMethod != null) {
                equipCosmeticMethod.invoke(null, user, cosmetic, color)
                return true
            } else {
                // try alternative signatures (without Color)
                val alt = apiClass.getMethod("equipCosmetic", Class.forName("com.hibiscusmc.hmccosmetics.user.CosmeticUser"), Class.forName("com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic"))
                alt.invoke(null, user, cosmetic)
                return true
            }
        } catch (t: Throwable) {
            logger.warning("HmcCosmeticService.applyToUuid failed: ${t.message}")
            return false
        }
    }

    /**
     * Apply a cosmetic to a Bukkit Player entity.
     */
    @JvmStatic
    fun applyToPlayer(player: Player, cosmeticId: String, color: Color? = null): Boolean {
        return try {
            applyToUuid(player.uniqueId, cosmeticId, color)
        } catch (t: Throwable) {
            logger.warning("HmcCosmeticService.applyToPlayer failed: ${t.message}")
            false
        }
    }

    /**
     * Attempt to apply a cosmetic to any entity. For non-players this currently returns false.
     * If HMCCosmetics exposes a hook for entities in the future, implement it here.
     */
    @JvmStatic
    fun applyToEntity(entity: Entity, cosmeticId: String, color: Color? = null): Boolean {
        // Prefer direct player path
        if (entity is Player) return applyToPlayer(entity, cosmeticId, color)

        // Try to extract a UUID from arbitrary entity wrappers (NPCs / fake entities)
        try {
            val cls = entity::class.java
            val uuidCandidates = listOf("getUniqueId", "getUuid", "uniqueId", "uuid")
            for (m in uuidCandidates) {
                try {
                    val meth = cls.getMethod(m)
                    val res = meth.invoke(entity)
                    if (res is UUID) return applyToUuid(res, cosmeticId, color)
                    if (res is String) {
                        val u = UUID.fromString(res)
                        return applyToUuid(u, cosmeticId, color)
                    }
                } catch (_: Throwable) { }
            }

            // try declared fields
            try {
                val f = cls.getDeclaredField("uuid")
                f.isAccessible = true
                val v = f.get(entity)
                if (v is UUID) return applyToUuid(v, cosmeticId, color)
                if (v is String) return applyToUuid(UUID.fromString(v), cosmeticId, color)
            } catch (_: Throwable) { }

            // try to obtain underlying bukkit entity then extract UUID
            val bMethods = listOf("getBukkitEntity", "toBukkitEntity", "getEntity", "entity")
            for (bm in bMethods) {
                try {
                    val meth = cls.getMethod(bm)
                    val r = meth.invoke(entity)
                    if (r != null) {
                        // if it's a Player -> handled above, otherwise try UUID getters on it
                        val subCls = r::class.java
                        for (m in uuidCandidates) {
                            try {
                                val sm = subCls.getMethod(m)
                                val res = sm.invoke(r)
                                if (res is UUID) return applyToUuid(res, cosmeticId, color)
                                if (res is String) return applyToUuid(UUID.fromString(res), cosmeticId, color)
                            } catch (_: Throwable) {}
                        }
                        try {
                            val ff = subCls.getDeclaredField("uuid")
                            ff.isAccessible = true
                            val v = ff.get(r)
                            if (v is UUID) return applyToUuid(v, cosmeticId, color)
                            if (v is String) return applyToUuid(UUID.fromString(v), cosmeticId, color)
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            logger.fine("HmcCosmeticService.applyToEntity reflection error: ${t.message}")
        }

        logger.fine("HmcCosmeticService.applyToEntity: could not resolve UUID from entity, skipping")
        return false
    }

    /**
     * Obtain the display ItemStack for a cosmetic id (if available) via HMCCosmetics API.
     * This is reflective and returns null if HMCCosmetics is absent or the cosmetic cannot be resolved.
     */
    @JvmStatic
    fun getCosmeticItem(cosmeticId: String): org.bukkit.inventory.ItemStack? {
        if (apiClass == null) {
            logger.fine("HmcCosmeticService: HMCCosmetics API not present (getCosmeticItem)")
            return null
        }

        try {
            val cosmetic = getCosmeticMethod?.invoke(null, cosmeticId) ?: return null
            // call cosmetic.getItem()
            val getItem = cosmetic.javaClass.getMethod("getItem")
            val item = getItem.invoke(cosmetic)
            return if (item is org.bukkit.inventory.ItemStack) item else null
        } catch (t: Throwable) {
            logger.warning("HmcCosmeticService.getCosmeticItem failed: ${t.message}")
            return null
        }
    }
}
