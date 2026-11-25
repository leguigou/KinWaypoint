# Application Android — KinWaypoint

Kotlin + Jetpack Compose, un seul APK, deux modes à terme (Enfant / Parent).
Aujourd'hui seul le **mode enfant** est implémenté.

## Ce que fait le mode enfant

| Élément | Détail |
|---|---|
| `service/TrackingService.kt` | Foreground service de type `location`, cadence configurable, retry ×3 |
| `net/TrackerClient.kt` | POST de formulaire au format OsmAnd (`id`, `lat`, `lon`, `batt`, `charge`, `alarm`) |
| `data/Settings.kt` | DataStore : serveur, identifiant d'appareil, intervalle, activation |
| `service/BootReceiver.kt` | Reprise du suivi après redémarrage si l'utilisateur l'avait activé |
| `MainActivity.kt` | Écran unique : config, état du dernier envoi, permissions, bouton SOS |

Le bouton SOS envoie une position avec `alarm=SOS` : l'événement `alarm` remonte
côté serveur et pourra déclencher une notification prioritaire.

## Compilation

**La compilation ne se fait pas sur la machine de développement** : le SDK Android
officiel ne fournit `aapt2` qu'en x86-64, et l'hôte est un Raspberry Pi (arm64).
Le binaire communautaire arm64 segfault. Le build tourne donc sur GitHub Actions
(`.github/workflows/android.yml`, runner Ubuntu x86-64) :

- push sur `main` → APK en artefact de workflow (30 jours)
- tag `v*` → APK attaché à la release GitHub

```bash
# provoquer un build sans changer de code
git commit --allow-empty -m "ci: rebuild" && git push
```

Récupérer l'APK depuis la ligne de commande :

```bash
gh run download --name kinwaypoint-debug
# ou via l'API : /repos/{owner}/{repo}/actions/artifacts/{id}/zip
#   ⚠️ suivre la redirection SANS le header Authorization, sinon 401
```

## Installation sur le téléphone

1. Ouvrir l'APK, autoriser l'installation depuis cette source
2. Localisation → **Toujours autoriser**
3. Désactiver l'optimisation batterie pour l'app
4. Renseigner le serveur (`https://…`), l'identifiant d'appareil et l'intervalle
5. Démarrer le suivi

Sans les points 2 et 3, Android coupe le service écran éteint : c'est la cause
n°1 des « positions qui n'arrivent plus ».

## Reste à faire

- mode parent (carte MapLibre, historique, géofences)
- sélection du mode au premier lancement (elle n'a de sens qu'une fois le mode
  parent disponible — pas d'écran qui promet une fonction absente)
- provisioning par QR code au lieu du copier-coller de l'identifiant
