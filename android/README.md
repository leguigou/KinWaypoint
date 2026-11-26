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
- **Empreinte digitale** (Android 9+, biométrie forte uniquement) : possible pour
  **ouvrir l'espace parent** sur un téléphone en mode parent. Jamais pour les
  réglages d'un téléphone enfant (l'enfant peut y avoir enregistré son doigt),
  ni pour changer de PIN ou de mode. Toute empreinte enregistrée sur le
  téléphone est acceptée : y penser si d'autres personnes y ont mis la leur.
- **PIN oublié** : Réglages Android → Applis → KinWaypoint → Stockage →
  Effacer les données. L'appli repart de zéro (mode, PIN et configuration perdus).

## Mode enfant

| Élément | Détail |
|---|---|
| `service/TrackingService.kt` | Foreground service `location`. GPS et réseau écoutés, **une seule** position envoyée par intervalle (la meilleure) |
| `data/FixQueue.kt` | File SQLite persistante : sans réseau, les positions attendent (≤ 5000, ≤ 30 j) et repartent au retour de la connexion. Les SOS passent en premier |
| `net/TrackerClient.kt` | POST OsmAnd (`id`, `lat`, `lon`, `batt`, `charge`, `alarm`, `fixAge`) |
| Fréquence adaptative | Immobile 5 min → GPS coupé, une position toutes les 10 min (Wi-Fi / réseau). Retour au GPS dès que le capteur de mouvement significatif se déclenche ou qu'un déplacement > ~200 m est vu. Sans Play Services |
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

**Ajouter un enfant** : le parent saisit un prénom, l'appli crée l'appareil sur
le serveur avec un identifiant aléatoire de 28 caractères et affiche un **QR
code**. Sur le téléphone de l'enfant : Réglages parent → « Scanner le QR code ».
Chaque membre a aussi un bouton « QR code de configuration ». Le QR contient
l'identifiant secret : ne pas le photographier ni l'envoyer.
Créer un appareil demande un compte Traccar autorisé à le faire (l'admin l'est).

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

## Code de configuration (configuration à distance)

Un lien HTTPS avec jeton (collé, ou lu dans un QR code) renvoie un JSON qui
remplit l'appli : au premier lancement (choix du mode compris), dans les
réglages enfant et sur l'écran de connexion parent. Format du JSON, exemples
et recommandations de sécurité : [`docs/CONFIG-DISTANTE.md`](../docs/CONFIG-DISTANTE.md).

## Installation sur un téléphone enfant

1. Installer l'APK (autoriser l'installation depuis cette source)
2. Choisir **Enfant**, créer le PIN parent
3. « Scanner le QR code de configuration » affiché par l'appli parent
   (ou saisir à la main le serveur d'envoi `https://track.…` et l'identifiant)
4. Localisation → **Toujours autoriser** ; désactiver l'optimisation batterie
5. « Enregistrer et démarrer le suivi », puis « Terminé — verrouiller »

Sans le point 4, Android coupe le service écran éteint : c'est la cause n°1
des « positions qui n'arrivent plus ».

## Reste à faire

- notifications push Firebase (SOS et zones, appli parent fermée)
- carte intégrée (MapLibre), historique, gestion des géofences
- mesurer la consommation batterie réelle sur plusieurs modèles
- tests sur téléphones réels (chaque fabricant gère l'arrière-plan différemment)

## Notifications

Les alertes de zone et le SOS arrivent par Firebase quand l'app est fermée, et par Telegram côté serveur en secours.

Test de la chaine webhook : ce commit doit declencher un build et une verification automatique.
Test final du rapport automatique.
