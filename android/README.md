# Application Android — KinWaypoint

Kotlin + Jetpack Compose, **un seul APK, deux modes** : Enfant et Parent.
Le mode est choisi au premier lancement ; un **code PIN parent** (4 à 8 chiffres)
est créé à ce moment-là.

## Ce que protège le PIN parent

| Mode | Sans PIN | Avec PIN |
|---|---|---|
| Enfant | Voir l'état du suivi, déclencher un SOS | Régler le serveur / l'identifiant / l'intervalle, arrêter le suivi, changer de mode, changer le PIN |
| Parent | Rien (écran de saisie du PIN) | Tout l'espace parent |

- Le PIN n'est jamais stocké en clair (empreinte PBKDF2 salée).
- 5 essais libres, puis blocage 30 s, 1 min, 2 min… (plafond 1 h). Le compteur
  survit à la fermeture de l'appli.
- L'espace parent et les réglages se reverrouillent après 1 min en arrière-plan.
- **PIN oublié** : Réglages Android → Applis → KinWaypoint → Stockage →
  Effacer les données. L'appli repart de zéro (mode, PIN et configuration perdus).

## Mode enfant

| Élément | Détail |
|---|---|
| `service/TrackingService.kt` | Foreground service `location`. GPS et réseau écoutés, **une seule** position envoyée par intervalle (la meilleure) |
| `data/FixQueue.kt` | File SQLite persistante : sans réseau, les positions attendent (≤ 5000, ≤ 30 j) et repartent au retour de la connexion. Les SOS passent en premier |
| `net/TrackerClient.kt` | POST OsmAnd (`id`, `lat`, `lon`, `batt`, `charge`, `alarm`, `fixAge`) |
| `service/BootReceiver.kt` | Reprise du suivi après redémarrage (mode enfant + suivi activé) |
| `ui/ChildScreens.kt` | Écran enfant (état + SOS) et réglages protégés |

**SOS** : appui maintenu 3 s (jauge + vibration) pour éviter les déclenchements
accidentels. L'alerte part immédiatement avec la dernière position connue,
horodatée « maintenant » ; l'âge réel de la mesure est transmis dans l'attribut
`fixAge` (secondes). La mesure GPS suivante part sans attendre l'intervalle.
L'écran affiche « en cours d'envoi » puis « reçue à HH:MM » quand le serveur l'a
acceptée.

## Mode parent (v1)

| Élément | Détail |
|---|---|
| `net/TraccarApi.kt` | Connexion e-mail / mot de passe → **jeton Traccar valable 1 an** ; seul le jeton est conservé, envoyé en `Authorization: Bearer` |
| `ui/ParentScreens.kt` | Liste des membres : en ligne / hors ligne, dernière position, batterie (rouge < 20 %), bouton « Voir sur la carte » (ouvre l'appli de cartes du téléphone) |
| Alertes SOS | Bandeau rouge pour chaque SOS des dernières 24 h non marqué « J'ai vu » |

Actualisation toutes les 30 s **quand l'appli est ouverte**. Les notifications
en arrière-plan (géofences, SOS appli fermée) nécessitent Firebase : à faire.

L'adresse à saisir est celle de l'**interface** (`https://api.<domaine>`, port
8082), pas celle d'envoi des positions (`https://track.<domaine>`).

## Compilation et signature

La compilation tourne sur GitHub Actions (`.github/workflows/android.yml`) :
le SDK Android ne fournit `aapt2` qu'en x86-64 et la machine de dev est un
Raspberry Pi (arm64).

- push sur n'importe quelle branche → APK en artefact `kinwaypoint` (30 jours)
- tag `v*` → APK attaché à la release GitHub (**refusé sans clé de signature**)

### Clé de signature (à faire une seule fois)

Android n'accepte une mise à jour que si elle est signée par **la même clé**
que la version installée. Il faut donc une clé fixe, gardée précieusement :
la perdre oblige à désinstaller l'appli sur tous les téléphones.

```bash
keytool -genkeypair -v -keystore kinwaypoint.jks -alias kinwaypoint \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 kinwaypoint.jks > kinwaypoint.jks.b64
```

Puis, dans GitHub → Settings → Secrets and variables → Actions, créer :

| Secret | Valeur |
|---|---|
| `KW_KEYSTORE_BASE64` | contenu de `kinwaypoint.jks.b64` |
| `KW_KEYSTORE_PASSWORD` | mot de passe du keystore |
| `KW_KEY_ALIAS` | `kinwaypoint` |
| `KW_KEY_PASSWORD` | mot de passe de la clé |

Sauvegarder `kinwaypoint.jks` et ses mots de passe hors du dépôt (gestionnaire
de mots de passe). Ne jamais les committer.

Sans ces secrets, la CI produit quand même un APK de test, signé par une clé
jetable (avertissement dans le build) : il ne s'installera pas par-dessus une
version précédente.

> Passage de la v0.1 à la v0.2 : la v0.1 était signée par une clé de debug
> jetable. Il faut **désinstaller la v0.1 une dernière fois** ; les versions
> suivantes s'installeront par-dessus.

```bash
gh run download --name kinwaypoint
# ou via l'API : /repos/{owner}/{repo}/actions/artifacts/{id}/zip
#   ⚠️ suivre la redirection SANS le header Authorization, sinon 401
```

## Installation sur un téléphone enfant

1. Installer l'APK (autoriser l'installation depuis cette source)
2. Choisir **Enfant**, créer le PIN parent
3. Saisir le serveur d'envoi (`https://track.…`), l'identifiant d'appareil, l'intervalle
4. Localisation → **Toujours autoriser** ; désactiver l'optimisation batterie
5. « Enregistrer et démarrer le suivi », puis « Terminé — verrouiller »

Sans le point 4, Android coupe le service écran éteint : c'est la cause n°1
des « positions qui n'arrivent plus ».

## Reste à faire

- notifications push Firebase (SOS et zones, appli parent fermée)
- fréquence adaptative (1 min en mouvement, 10-15 min à l'arrêt)
- carte intégrée (MapLibre), historique, gestion des géofences
- provisioning par QR code au lieu du copier-coller de l'identifiant
- tests sur téléphones réels (chaque fabricant gère l'arrière-plan différemment)
