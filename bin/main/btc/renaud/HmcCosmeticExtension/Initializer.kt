package btc.renaud.HmcCosmeticExtension

import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.entry.AudienceManager
import com.typewritermc.engine.paper.entry.entity.AudienceEntityDisplay
import me.tofaa.entitylib.wrapper.WrapperLivingEntity
import org.koin.java.KoinJavaComponent
import org.bukkit.scheduler.BukkitTask
import java.lang.reflect.Field
import java.util.*

/**
 * Initializer for the extension.
 *
 * Starts a periodic Bukkit task that scans TypeWriter's AudienceManager displays and applies
 * HMCCosmetic data (if present) to the underlying wrapper living entities.
 *
 * The implementation is intentionally defensive and reflective to avoid hard runtime coupling
 * to TypeWriter internal classes. It logs successes/failures and swallows exceptions to avoid
 * breaking TypeWriter's lifecycle.
 */
@Singleton
object Initializer : Initializable {
    @Volatile
    private var task: BukkitTask? = null

    override suspend fun initialize() {

        try {
            val manager = KoinJavaComponent.get<AudienceManager>(AudienceManager::class.java)

            // Schedule a repeating task: initial delay 1 tick, repeat every 5 ticks
            task = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                try {
                    // Iterate all entity displays
                    val displays = manager.findDisplays(AudienceEntityDisplay::class).toList()
                    for (display in displays) {
                        try {
                            // For each online player, check if there's a cosmetic property for this display
                                    for (player in org.bukkit.Bukkit.getServer().onlinePlayers) {
                                try {
                                    val playerId = player.uniqueId

                                    // Attempt to read a CosmeticProperty for this player (if present)
                                    val cosmetic = try {
                                        // Use the generic API on the display to request our property type
                                        display.property(playerId, btc.renaud.HmcCosmeticExtension.CosmeticProperty::class) as? btc.renaud.HmcCosmeticExtension.CosmeticProperty
                                    } catch (t: Throwable) {
                                        null
                                    } ?: continue

                                    // If no cosmetics to apply, skip
                                    if (cosmetic.data.isEmpty()) continue

                                    // Try to locate the DisplayEntity instance via reflection to extract the underlying FakeEntity
                                    val entitiesField = findFieldRecursive(display, "entities") ?: continue
                                    val entitiesMap = entitiesField.get(display) as? Map<*, *> ?: continue
                                    val displayEntity = entitiesMap[playerId] ?: continue

                                    // Get the 'entity' property from the DisplayEntity
                                    val entityField = findFieldRecursive(displayEntity, "entity") ?: continue
                                    val fakeEntity = entityField.get(displayEntity) ?: continue

                                    // Attempt to obtain a wrapper entity instance by walking the object graph.
                                    // This handles nested structures like NpcEntity -> NamedEntity -> PlayerEntity -> WrapperPlayer.
                                    var wrapperObj: Any? = null
                                    try {
                                        wrapperObj = findWrapperEntityRecursive(fakeEntity, mutableSetOf(), 8)
                                    } catch (_: Throwable) {
                                    }

                                    if (wrapperObj != null) {
                                        try {
                                            // Delegate to the helper in HmcCosmeticData (reflective, safe)
                                            applyHmcCosmeticData(wrapperObj, cosmetic)
                                        } catch (t: Throwable) {
                                            // swallow to avoid disrupting TypeWriter flow
                                        }
                                    } else {
                                    }
                                } catch (t: Throwable) {
                                    // Per-player error should not stop other players / displays
                                }
                            }
                        } catch (t: Throwable) {
                        }
                    }
                } catch (t: Throwable) {
                    // swallow scheduler error
                }
            }, 1L, 5L)
        } catch (t: Throwable) {
            // swallow initialization error
        }
    }

    override suspend fun shutdown() {
        try {
            task?.cancel()
            task = null
        } catch (t: Throwable) {
        }

    }

    private fun findFieldRecursive(instance: Any, name: String): Field? {
        var cls: Class<*>? = instance.javaClass
        while (cls != null && cls != java.lang.Object::class.java) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (_: NoSuchFieldException) {
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * Recursively walk the object graph (fields, collections, maps) to find an instance that looks like
     * a me.tofaa.entitylib.wrapper.* wrapper (WrapperPlayer, WrapperEntity, WrapperLivingEntity).
     *
     * - visited tracks already inspected objects to avoid cycles
     * - depth limits recursion
     */
    private fun findWrapperEntityRecursive(root: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (root == null) return null
        if (depth <= 0) return null

        // Avoid reference loops using identity (visited contains actual object references)
        try {
            if (!visited.add(root)) return null
        } catch (_: Throwable) {
            // some objects may not behave well as set keys; ignore and continue
        }

        // Direct instance: accept known wrapper types by package/name heuristic
        try {
            val cls = root.javaClass
            val pkg = cls.`package`?.name ?: ""
            val simple = cls.simpleName ?: ""
            if (pkg.startsWith("me.tofaa.entitylib.wrapper") || simple.contains("Wrapper")) {
                return root
            }
        } catch (_: Throwable) {
        }

        // If it's a map, check values
        if (root is Map<*, *>) {
            for (v in root.values) {
                val found = findWrapperEntityRecursive(v, visited, depth - 1)
                if (found != null) return found
            }
        }

        // If it's an iterable, check elements
        if (root is Iterable<*>) {
            for (it in root) {
                val found = findWrapperEntityRecursive(it, visited, depth - 1)
                if (found != null) return found
            }
        }

        // Walk fields reflectively
        var cls: Class<*>? = root.javaClass
        while (cls != null && cls != java.lang.Object::class.java) {
            try {
                for (f in cls.declaredFields) {
                    try {
                        f.isAccessible = true
                        val v = f.get(root) ?: continue
                        // quick check
                        try {
                            val vcls = v.javaClass
                            val vpkg = vcls.`package`?.name ?: ""
                            val vsimple = vcls.simpleName ?: ""
                            if (vpkg.startsWith("me.tofaa.entitylib.wrapper") || vsimple.contains("Wrapper")) return v
                        } catch (_: Throwable) {
                        }

                        val found = findWrapperEntityRecursive(v, visited, depth - 1)
                        if (found != null) return found
                    } catch (_: Throwable) {
                        // ignore field access errors
                    }
                }
            } catch (_: Throwable) {
            }
            cls = cls.superclass
        }

        return null
    }
}
