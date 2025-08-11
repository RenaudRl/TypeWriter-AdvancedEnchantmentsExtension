# HMCCosmetic TypeWriter Extension - Changelog

## Version 0.0.2 - Améliorations et Corrections

### ✅ Problèmes Résolus

#### 1. **Balloons et Backpacks visibles sur les NPCs**
- **Problème** : Les cosmétiques BALLOON et BACKPACK n'étaient pas visibles sur les entités non-joueurs (NPCs)
- **Solution** : Suppression de la restriction qui empêchait l'affichage de ces cosmétiques sur les NPCs
- **Impact** : Les balloons et backpacks s'affichent maintenant correctement sur tous les types d'entités

#### 2. **Sélection des clés cosmétiques**
- **Problème** : Les utilisateurs devaient taper manuellement les noms des slots (HELMET, CHESTPLATE, etc.)
- **Solution** : Implémentation d'un système de sélection par enum avec les clés HMCCosmetics officielles
- **Clés disponibles** :
  - `HELMET` - Casques et chapeaux
  - `CHESTPLATE` - Plastrons et armures de torse
  - `LEGGINGS` - Jambières et pantalons
  - `BOOTS` - Bottes et chaussures
  - `MAINHAND` - Main principale
  - `OFFHAND` - Main secondaire
  - `BACKPACK` - Sacs à dos et accessoires dorsaux
  - `BALLOON` - Ballons et objets flottants

#### 3. **Ajustement automatique du TextDisplay**
- **Problème** : Superposition du texte avec les casques cosmétiques
- **Solution** : Système d'ajustement automatique de la position Y du TextDisplay
- **Fonctionnalités** :
  - Détection automatique des casques équipés
  - Calcul intelligent du décalage basé sur les métadonnées de l'item
  - Option manuelle `helmetOffset` pour un contrôle précis
  - Valeurs par défaut : 0.35 pour les items avec custom model data, 0.22 sinon

### 🔧 Améliorations Techniques

#### Configuration TypeWriter
```yaml
npc:
  type: VILLAGER
  custom-data:
    hmc_cosmetic_data:
      cosmetics:
        HELMET: "beanie"
        BACKPACK: "jetpack"
        BALLOON: "kite"
      helmetOffset: 0.4  # Optionnel : ajustement manuel
```

#### Compatibilité
- ✅ Support complet des NPCs (Citizens, EntityLib, etc.)
- ✅ Fallback automatique pour les entités non-supportées
- ✅ Gestion des erreurs robuste
- ✅ Logs détaillés pour le débogage

#### Performance
- ✅ Cache des classes HMCCosmetics pour éviter les lookups répétés
- ✅ Réflexion optimisée avec gestion d'erreurs
- ✅ Application sélective des cosmétiques

### 📋 Notes de Migration

Si vous utilisez une version antérieure :

1. **Clés de cosmétiques** : Mettez à jour vos configurations pour utiliser les nouvelles clés standardisées
2. **Balloons/Backpacks** : Aucune action requise - ils fonctionneront automatiquement sur les NPCs
3. **TextDisplay** : L'ajustement est automatique, mais vous pouvez utiliser `helmetOffset` pour un contrôle précis

### 🐛 Corrections de Bugs

- Correction de l'erreur de référence non résolue `isPlayerUnderlying`
- Suppression des avertissements de compilation liés aux méthodes dépréciées
- Amélioration de la gestion des exceptions dans les appels réflexifs

### 🔮 Prochaines Améliorations

- Support des couleurs personnalisées pour les cosmétiques dyeable
- Interface graphique pour la configuration des cosmétiques
- Prévisualisation en temps réel des cosmétiques
- Support des animations et effets spéciaux
