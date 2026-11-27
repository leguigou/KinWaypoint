# Application Android — KinWaypoint

Kotlin + Jetpack Compose, **un seul APK, deux modes** : Enfant et Parent.
Le mode est choisi au premier lancement ; un **code PIN parent** (4 à 8 chiffres)
est créé à ce moment-là. Présentation générale et installation du serveur :
[README du dépôt](../README.md).

## Ce que protège le PIN parent

| Mode | Sans PIN | Avec PIN (ou empreinte en mode parent) |
|---|---|---|
| Enfant | État du suivi, pas du jour, SOS, « je suis bien arrivé·e », carte de la famille | Liaison (QR ou code d'invitation), écoute en direct, arrêt du suivi, changement de mode ou de PIN |
| Parent | Rien (écran de saisie) | Tout l'espace parent |

- Le PIN n'est jamais stocké en clair (empreinte PBKDF2 salée).
- 5 essais libres, puis blocage 30 s, 1 min, 2 min… (plafond 1 h). Le compteur
  survit à la fermeture de l'appli.
- Reverrouillage : réglable en mode parent (1 min, 5 min, 15 min, 1 h, jamais),
  y compris après un redémarrage de l'appli ; toujours 1 min pour les réglages
  d'un téléphone enfant.
- **Empreinte** (Android 9+, biométrie forte) : seulement pour ouvrir l'espace
  parent sur un téléphone en mode parent. Jamais pour les réglages d'un
  téléphone enfant (l'enfant peut y avoir enregistré son doigt), ni pour changer
  de PIN ou de mode.
- **PIN oublié** : Réglages Android → Applis → KinWaypoint → Stockage →
  Effacer les données (mode, PIN et configuration perdus).

## Organisation du code

| Dossier / fichier | Rôle |
|---|---|
| `service/TrackingService.kt` | Service au premier plan (`location`) : une position par intervalle, fréquence adaptative, podomètre, SOS, commandes du serveur (sonner, écouter) |
| `service/ListenService.kt`, `ListenActivity.kt` | Écoute en direct, côté enfant (service `microphone` + écran visible) |
| `service/RingAlarm.kt` | « Faire sonner » (canal alarme, même en silencieux ou « Ne pas déranger » avec l'autorisation ; arrêt par une touche de volume ou à distance, `kwRingStop`) |
| `service/RemoteActions.kt` | Lit les demandes du parent posées sur l'appareil Traccar : sonner, écouter, fréquence de suivi |
| `data/FixQueue.kt` | File SQLite : sans réseau, les positions attendent (≤ 20 000, ≤ 30 j) ; les SOS passent en premier |
| `service/TrackingWatchdog.kt` | Chien de garde (WorkManager, 15 min, et 30 s après un arrêt) : relance le suivi s'il est activé mais arrêté |
| `data/Settings.kt` | Réglages (DataStore) |
| `net/TrackerClient.kt` | Envoi des positions (protocole OsmAnd) |
| `net/TraccarApi.kt` | API Traccar : membres, positions, lieux, calendriers, alertes, commandes, proximité |
| `net/AudioRelay.kt` | Relais audio de l'écoute en direct |
| `motion/` | Vitesse, activité (marche / voiture), podomètre, heure d'arrivée estimée |
| `push/PushService.kt` | Réception Firebase (alertes, réveil pour « localiser » / « sonner ») |
| `ui/ChildOnboarding.kt` | Liaison d'un téléphone enfant (QR ou code), puis autorisations une par une |
| `ui/` | Écrans Compose : carte, historique, lieux, réglages, bilan, mises à jour |

## Suivi (mode enfant, et parent qui partage sa position)

- **Cadence** (réglable à distance par un parent, attributs `kwInt*`) :
  - 5 min en temps normal ;
  - 15 s dès qu'un déplacement est détecté (pas réguliers, ou déplacement net
    entre deux points), pendant 5 min ;
  - 10 min à l'arrêt, GPS coupé.
- **Position grossière** (GPS coupé, intérieur) : si elle reste compatible
  avec la dernière position précise, celle-ci est renvoyée (avec la vraie
  précision), pour éviter les fausses sorties puis rentrées de zone.
- **Arrêt** : aucun pas et téléphone immobile (accéléromètre, mesures groupées)
  depuis 3 min. À l'arrêt, la position envoyée reste celle du moment de l'arrêt :
  le « tremblement » du GPS ne crée ni faux trajet ni fausse alerte. Le suivi
  repart aux premiers pas, au capteur de mouvement significatif, ou sur un
  déplacement vu par le réseau.
- **Attributs envoyés** en plus de la position : batterie et charge, sonnerie,
  Wi-Fi (activé `wifiOn`, nom `ssid`), pas du jour, activité, vitesse, âge de la
  mesure (`fixAge`), alarme.
- **SOS** : appui maintenu 3 s. L'alerte part tout de suite avec la dernière
  position connue (son âge réel dans `fixAge`), puis la mesure suivante part sans
  attendre. L'écran affiche « en cours d'envoi », puis « reçue à HH:MM ».
- **Redémarrage** du téléphone ou mise à jour de l'appli : le suivi reprend seul
  (`service/BootReceiver.kt`).
- **Arrêt par Android ou par l'utilisateur** (volet « Applications actives »,
  appli balayée sur certaines surcouches) : `TrackingWatchdog` relance le suivi
  sous 30 s à 15 min s'il est toujours activé. La notification balayée (Android
  14+) revient aussitôt. Pour que la relance depuis l'arrière-plan soit permise,
  il faut « Toujours autoriser » la localisation et retirer la restriction
  batterie. « Forcer l'arrêt » reste définitif jusqu'à la réouverture de l'appli.
- **Hors connexion** : chaque position est d'abord écrite dans `FixQueue`, puis
  envoyée ; en cas d'échec, nouvel essai après 30 s, 1 min, 2 min… (5 min au
  plus) et immédiatement au retour du réseau.

## Adresse du serveur familial

L'appli a besoin de l'adresse du serveur familial (dépôt d'APK, découverte des
adresses Traccar, écoute en direct). Elle **n'est pas dans le code** : elle est
fournie à la compilation dans `BuildConfig.FAMILY_HUB`.

- **CI** : secret GitHub `KW_FAMILY_HUB` (ex. `https://famille.exemple.fr`). Sans
  lui, la CI refuse de produire l'APK.
- **En local** : variable d'environnement `KW_FAMILY_HUB`, ou propriété Gradle
  `kwFamilyHub=https://…` dans `~/.gradle/gradle.properties` (hors du dépôt).

## Compilation et signature

La compilation tourne sur GitHub Actions (`.github/workflows/android.yml`) :
le SDK Android ne fournit `aapt2` qu'en x86-64. À chaque push :

1. tests unitaires (`testReleaseUnitTest`), puis APK signé ;
2. vérification de la signature ;
3. publication dans la release `test-latest` avec `version.json` ;
4. dépôt sur le serveur familial (secret `KW_HUB_TOKEN`), d'où l'appli se met à jour.

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
| `KW_FAMILY_HUB` | adresse du serveur familial (`https://…`) |
| `KW_HUB_TOKEN` | jeton de dépôt des APK (`KW_ADMIN_TOKEN` côté serveur) |

Sauvegarder `kinwaypoint.jks` et ses mots de passe hors du dépôt (gestionnaire
de mots de passe). Ne jamais les committer.

## Installation sur un téléphone enfant

1. Installer l'APK (autoriser l'installation depuis cette source).
2. Choisir **Enfant**, créer le PIN parent.
3. « Scanner le QR code » affiché par l'appli parent (Paramètres → Famille →
   « Ajouter un enfant », ou membre → ⋮ → QR code), ou taper le code
   d'invitation à 8 chiffres (il part tout seul au 8e chiffre).
4. Suivre le bouton unique : localisation, « Toujours autoriser », activité
   physique, notifications, batterie sans restriction. En facultatif : micro
   et affichage par-dessus (écoute en direct), « Ne pas déranger » (sonnerie).
   Au 2e appui sans effet (autorisation refusée définitivement), la page de
   l'appli s'ouvre dans les réglages Android.
5. « Terminer » : le suivi est déjà actif.

Sans la localisation « toujours » et l'exemption batterie, Android coupe le
service écran éteint : c'est la cause n°1 des « positions qui n'arrivent plus ».
Certaines surcouches (Xiaomi, Oppo, Huawei…) demandent en plus d'autoriser le
« démarrage automatique ».

## Code de configuration

Un lien HTTPS avec jeton (collé, ou lu dans un QR code) renvoie un JSON qui
remplit l'appli : au premier lancement, dans les réglages enfant et sur l'écran
de connexion parent. Format et exemples : [docs/CONFIG-DISTANTE.md](../docs/CONFIG-DISTANTE.md).

## Reste à faire

- Mesurer la consommation de batterie réelle sur plusieurs modèles.
- Tester sur des téléphones réels de chaque fabricant (gestion de l'arrière-plan).
