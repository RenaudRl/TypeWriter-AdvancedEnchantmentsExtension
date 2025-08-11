But : this repo already contains Kotlin helper classes that call HMCCosmetics (HmcCosmeticService.kt + extension helpers).
Below is guidance and best-effort example code showing how to wire TypeWriter data -> HMCCosmetic using those helpers.

But (FR) : ce dépôt contient déjà des helpers Kotlin qui appellent HMCCosmetics (HmcCosmeticService.kt + helpers d'extension).
La doc ci-dessous explique comment TypeWriter peut exposer et appliquer des cosmetics HMCCosmetics via ces helpers.

Goal / Objectif
----
Expose HMCCosmetics cosmetics in the same way TypeWriter exposes equipment_data:
- In TypeWriter configuration you should be able to reference a cosmetic by id (string).
- When TypeWriter applies entity data (equipment-like), call the HmcCosmeticService helpers to apply that cosmetic to the entity/player.

Exposer les cosmetics HMCCosmetics de la même manière que TypeWriter expose equipment_data :
- Dans la config TypeWriter on doit pouvoir renseigner l'id du cosmetic (string).
- Quand TypeWriter applique des données d'entité (type equipment), les helpers HmcCosmeticService appliquent le cosmetic.

What is implemented in this extension
----
Files of interest:
- src/main/kotlin/btc/renaud/HmcCosmeticExtension/HmcCosmeticService.kt
  - helpers reflectifs vers HMCCosmeticsAPI (applyToPlayer/applyToUuid/getCosmeticItem)
- src/main/kotlin/btc/renaud/HmcCosmeticExtension/HmcCosmeticData.kt
  - entry TypeWriter `@Entry("hmc_cosmetic_data", ...)` : map cosmetics key -> cosmetic id
  - applyHmcCosmeticData(entity, property) : central apply logic that tries:
    1) HmcCosmeticService (authoritative)
    2) HMCCPacketManager packet fallbacks (vanilla equipment map OR HMCC-specific slot APIs)
    3) NPC client-side fallbacks (armorstand + pufferfish for BALLOON, then mount)
  - TextDisplay (nameplate/hologram) Y-adjustment when a HELMET cosmetic is actually applied
- src/main/resources/META-INF/typewriter/adapters.json
  - Editor adapter: `cosmetics` is a map whose key is an enum. This enables dropdown selection in TypeWriter's editor.

Points implemented / corrections made
----
1) BACKPACK & BALLOON visible on NPCs
- HMCCosmetics exposes BACKPACK and BALLOON as HMCC-specific cosmetic slots (they do not map to vanilla EquipmentSlot).
- The extension's logic was updated so:
  - HMCC-specific slots are handled via HMCC packet-level APIs (HMCCPacketManager) when available.
  - If HMCC packet APIs don't apply (or are unavailable), there's a client-side NPC fallback:
    - spawn a client-side ArmorStand to display the cosmetic item,
    - for BALLOON spawn also a Pufferfish and leash/mount as HMCC does,
    - mount the armorstand to the NPC (via riding packets),
    - this makes backpack/balloon visible on NPCs even when HMCC applies cosmetics only to players.
- Practical note: this relies on HMCCPacketManager (present in HMCCosmetics implementation) and some auxiliary server utilities (to get next entity id). If your HMCC version has different signatures the reflection attempts are defensive and fall back gracefully.

2) Key selection in editor (don't type keys manually)
- The adapter manifest at src/main/resources/META-INF/typewriter/adapters.json defines the `cosmetics` map key as an enum with values:
  - HELMET, CHESTPLATE, LEGGINGS, BOOTS, MAINHAND, OFFHAND, BACKPACK, BALLOON
- TypeWriter's editor should show a dropdown (selection) for the key instead of requiring free text.
- Implementation detail: code accepts legacy/synonym names at runtime (e.g., HEAD, CHEST, MAIN_HAND, OFF_HAND, LEGS, FEET) by matching keys case-insensitively; adapters.json keeps the canonical HMCC keys so the editor UX is clean.

3) TextDisplay (NPC name/hologram) Y movement only when helmet is actually applied
- Previously the TextDisplay offset was attempted even if no helmet had been successfully applied, which caused undesired vertical movement.
- Now the Y-adjustment runs only if the HELMET cosmetic is actually applied (the code checks appliedSlots to ensure the helmet applied successfully).
- Offset heuristic: the code uses cosmetic item's ItemMeta.customModelData to choose a conservative offset:
  - customModelData > 0 -> 0.35
  - otherwise -> 0.22
  This is a best-effort heuristic and conservative to avoid overlaps; you can adjust values if your models require different spacing.

Usage (YAML / TypeWriter data)
----
Example (equipment-like) YAML fragment where an NPC or entity data contains cosmetics:

npc:
  type: VILLAGER
  custom-data:
    hmc_cosmetic_data:
      id: "villager_cosmetics"
      name: "Villager cosmetics"
      cosmetics:
        HELMET: "beanie_cosmetic"         # HMCCosmetics cosmetic id (selectable key)
        CHESTPLATE: "jetpack"
        BACKPACK: "small_backpack"        # BACKPACK is HMCC-specific
        BALLOON: "red_balloon"            # BALLOON is HMCC-specific

Notes:
- The extension looks for cosmetic id values as strings.
- Helpers accept any of these keys case-insensitively: HELMET, HEAD, CHESTPLATE, CHEST, LEGGINGS, LEGS, BOOTS, FEET, MAINHAND/MAIN_HAND, OFFHAND/OFF_HAND, BACKPACK, BALLOON.
- Leave an entry blank or empty string to remove/reset that slot.

How backpack & balloon differ (analysis)
----
- HMCCosmetics treats BACKPACK and BALLOON as special cosmetic slots that are not equivalent to Minecraft equipment slots. They are registered as CosmeticSlot.register("BACKPACK") / register("BALLOON") (see HMCC source in temp_HMCCosmetics).
- Visual representation uses custom code:
  - BACKPACK often attaches a model to the player's back (not an armor piece).
  - BALLOON is implemented in HMCC by spawning a pufferfish client-side, leashing it and mounting model armorstand(s) on/above it — it's not simply equipping an item on a vanilla slot.
- Because of this, the extension:
  - Avoids mapping BACKPACK/BALLOON to a vanilla EquipmentSlot.
  - Uses HMCC packet APIs (CosmeticSlot-aware overloads) to request HMCC to display them for players.
  - For NPCs (non-player entities) the extension attempts packet calls that accept an entity id + CosmeticSlot (where HMCC provides such overloads) and, failing that, falls back to the client-side armorstand+pufferfish technique so NPCs also display those cosmetics.

Limitations & troubleshooting
----
- HMCCosmetics versions differ: the extension uses reflection and multiple candidate method signatures. If your installed HMCC version changed method names/signatures, some reflective calls may not match — the code tries multiple overloads and falls back to client-side techniques where feasible.
- HMCC must be loaded/initialized before cosmetic application; if TypeWriter runs very early, some cosmetic users may not be attached yet. The helpers log and return false rather than throwing.
- TextDisplay offsets are heuristic; if your helmet models have very different heights you may need to tune the offsets (0.22/0.35) in HmcCosmeticData.kt.
- Editor dropdown depends on TypeWriter reading adapters.json. If your TypeWriter version requires a different adapter schema, adjust adapters.json accordingly. The file included already contains the enum key and should enable selection in the editor.

Developer notes (where changes were made)
----
- HmcCosmeticData.kt
  - Cosmetic map parsing and application logic is implemented here.
  - Packet-level fallbacks for both vanilla-mapped slots and HMCC-specific slots are present.
  - NPC client-side fallback: armorstand spawn + equip + riding packets (BALLOON also spawns pufferfish and handles leash + riding).
  - TextDisplay Y adjustment moved to run only when HELMET was actually applied.

- HmcCosmeticService.kt
  - Reflective wrappers around HMCCosmeticsAPI:
    - applyToUuid, applyToPlayer, applyToEntity (best-effort UUID extraction), getCosmeticItem
  - applyToEntity continues to favor players but tries to extract UUIDs from wrapper objects to call applyToUuid where possible.

- adapters.json (src/main/resources/META-INF/typewriter/adapters.json)
  - The `cosmetics` map key uses an enum key type listing HMCC canonical keys so editor selection is possible:
    - HELMET, CHESTPLATE, LEGGINGS, BOOTS, MAINHAND, OFFHAND, BACKPACK, BALLOON

If you want next changes
----
- I can:
  - Add legacy synonyms to adapters.json enum so editor shows HEAD/CHEST/MAIN_HAND etc in addition to canonical values (makes the dropdown longer but explicit).
  - Expose the helmet offset as a configurable field in the adapter so users can tweak Y offset per entry.
  - Implement stronger logging / debug toggles for tracing HMCC packet fallback paths.

This doc reflects the current code in this repository and the defensive reflection-based approach used to remain compatible with multiple HMCC versions.
