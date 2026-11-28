# KinWaypoint

> **Kin** — les siens. **Waypoint** — le point de route.
> Le point de ralliement de la famille sur une carte.

Suivi de localisation familial **auto-hébergé**. Une seule application Android
(Kotlin / Jetpack Compose), deux modes protégés par un code PIN :

- **Parent** : carte, historique, lieux, alertes ;
- **Enfant** : envoie sa position au serveur familial, affiche l'état du suivi
  et la carte de la famille.

Un serveur **Traccar** collecte, stocke et déclenche les alertes.

[APK de test sur GitHub](https://github.com/leguigou/KinWaypoint/releases/tag/test-latest)
· Android 8.0 ou plus. Le dépôt GitHub est privé : il faut un compte autorisé.

- Les positions et les événements restent sur votre serveur, sans service
  commercial de géolocalisation.
- Historique limité dans le temps par construction (30 jours).
- Distribution hors Play Store : APK signé, mise à jour depuis l'application.

---

## Fonctionnalités

### Carte et suivi
- Carte familiale (MapLibre + OpenStreetMap, sans clé d'API) : avatar photo ou
  initiale de chacun, mise à jour **en direct** (WebSocket, actualisation
  périodique en secours).
- Fiches glissantes par personne, **toutes de la même hauteur** et rangées de
  la même façon : état (« ● En ligne · il y a 3 min »), une ligne « où » (lieu
  et durée, trajet vers un lieu avec heure d'arrivée, ou déplacement), quatre
  chiffres (batterie, précision, à pied, en voiture), objectif de pas, bouton
  Itinéraire. Seul le **tracé de la personne affichée** est dessiné : il change
  quand on fait glisser les fiches.
- **Ancienneté et précision** de chaque position en couleur (vert / orange /
  rouge) et cercle de précision sur la carte.
- Pastilles sur l'avatar : 🚗 / 🚶 avec la vitesse, 🔇 silencieux, 🪫 batterie
  faible, SOS ; un **écusson** en haut à droite distingue les comptes parents
  (liste des membres, fiches et marqueurs de la carte, côté parent comme enfant).
- Distance du jour **à pied** et **en voiture**, podomètre et objectif de pas.
- Fréquence adaptative selon la situation (réglages des parents = bornes) :
  - **à pied** et en temps normal : toutes les **5 min** ;
  - **en voiture** (plus de 15 km/h) : **1 min au plus** (15 s en « Équilibré ») ;
    un départ en voiture est repéré en 1 à 2 min grâce à la position réseau,
    peu gourmande, relevée chaque minute ;
  - **téléphone branché** : bien plus serré (10 s en voiture, 1 min à pied,
    2 min sinon) ;
  - **économie d'énergie** (jamais si branché) : sur le **Wi-Fi d'une box**
    (lieu sûr), ou sous un **seuil de batterie** choisi une fois pour toute la
    famille (Paramètres → Famille : jamais, 15, 20, 30 ou 40 %). Position par le
    réseau (sans GPS), toutes les 15 min (2 min en voiture, 30 min à l'arrêt),
    accéléromètre coupé ; les pas dans la maison ne réveillent plus le suivi,
    c'est la perte du Wi-Fi qui signale un départ. **Feuille verte** sur la
    batterie de la personne concernée ;
  - toutes les **10 min** à l'arrêt, GPS coupé. L'arrêt est détecté par le
    podomètre et l'accéléromètre, et la position est figée pour ne pas
    enregistrer le « tremblement » du GPS.
- **Fréquence réglable à distance**, par personne (Paramètres → Famille →
  Fréquence de suivi) : Économe, Équilibré (par défaut), Précis, ou réglage
  libre des trois délais. Le téléphone l'applique à son prochain envoi.
- **Sans réseau, rien n'est perdu** : les positions sont gardées sur le
  téléphone (jusqu'à 20 000, soit plusieurs jours de déplacement) puis envoyées
  dès le retour de la connexion, alertes SOS en premier.
- **Suivi toujours actif** : il reprend seul après un redémarrage du téléphone
  ou une mise à jour ; un chien de garde le relance (sous 15 min) s'il est
  arrêté depuis le volet « Applications actives » d'Android ou par une
  surcouche ; la notification revient si elle est balayée. Seul « Forcer
  l'arrêt » dans les réglages Android l'arrête jusqu'à la réouverture de
  l'appli (règle d'Android) : l'alerte « pas de nouvelles depuis 1 h » prévient
  alors les parents.

### Lieux et alertes
- Lieux (maison, école…) avec icône, couleur, rayon, **personnes concernées**
  et **horaires** (ex. lun–ven 08:00–17:00, créneaux passant minuit).
- **Pas de fausse sortie de zone** : GPS coupé puis rallumé, ou intérieur sans
  GPS, le téléphone n'a qu'une position grossière (±500 m à 2 km). Tant qu'elle
  reste compatible avec la dernière position précise, c'est cette dernière qui
  est envoyée : ni « départ » ni « nouvelle arrivée » à tort.
- **Wi-Fi d'un lieu** (optionnel) : quand le téléphone est connecté à ce réseau,
  la personne est affichée dans le lieu (« 🏠 Maison · Wi-Fi »), même si le GPS
  est imprécis à l'intérieur.
- **Fiche par lieu** : dernier passage, nombre de visites et temps cumulé, par
  personne, sur 7 ou 30 jours.
- **Lieux fréquents suggérés** à partir des 14 derniers jours (« Maison ? »,
  « École ? »).
- Notifications **même application fermée** (Firebase, et Telegram en option),
  rédigées en français :
  - arrivée / départ d'un lieu ;
  - SOS (avec adresse) : alerte **toujours active**, créée d'office sur le
    serveur pour chaque compte parent à l'ouverture de l'accueil ;
  - batterie faible ;
  - « je suis bien arrivé·e » ;
  - excès de vitesse ;
  - pas de nouvelles depuis 1 h ;
  - **retour près d'un parent** (proximité calculée par le serveur).
- **Appli ouverte** : une alerte reçue met à jour tout de suite l'accueil
  (bandeau SOS, cloche, fiches), sans fermer ni rouvrir l'appli.
- **Écran des notifications** : lignes compactes regroupées par jour, SOS en
  bandeau rouge avec « Voir sur la carte » ; le bouton carte n'apparaît que
  lorsqu'il mène quelque part (pas pour « batterie faible », simple information).
- **Pastille sur la cloche** de l'accueil : nombre de notifications arrivées
  depuis la dernière ouverture de l'écran des notifications (« 99+ » au-delà).

### Historique
- Jour par jour, du plus récent au plus ancien : arrêts et trajets (marche /
  voiture) avec distance, durée, vitesses moyenne et maximale ; le trajet choisi
  est tracé en gras sur la carte.
- **Relecture d'une journée** : lecture, pause et curseur horaire.
- **Couleurs de batterie** : vert avec l'éclair en charge, bleu en temps
  normal, orange clair ≤ 40 %, orange foncé ≤ 30 %, rouge ≤ 15 %.
- **Courbe de batterie** : toucher le bouton batterie (« 71 % », en bas à droite
  du bloc position) d'une carte membre ouvre une fenêtre avec la charge et la décharge de 0 h à 24 h
  (périodes en charge en vert), minimum et maximum, décharge moyenne (% par
  heure) et nombre / durée des recharges ; flèches pour les jours précédents
  (30 jours).
- Bilan de la semaine : pas, objectif, distance, temps passé dans chaque lieu.

### Sécurité de l'enfant
- **SOS** par appui long (3 s) : part immédiatement, même sans réseau (file
  d'attente persistante).
- **SMS de secours** : au moment d'un SOS, le téléphone de l'enfant envoie aussi
  un SMS à chaque parent qui a renseigné son numéro (Paramètres → Compte), avec
  un lien Google Maps, l'heure, la précision et la batterie. Il passe par le
  réseau téléphonique, **sans Internet** ; si la position est absente ou
  ancienne, un 2e SMS suit avec la position fraîche. Autorisation « SMS » à
  accorder sur le téléphone de l'enfant (proposée à la liaison et dans le
  check-up).
- **Faire sonner** le téléphone à distance et **demander sa position** tout de
  suite. La sonnerie passe même en silencieux ; en « Ne pas déranger » aussi si
  l'autorisation « Accès Ne pas déranger » est accordée (réglages enfant →
  Suivi). Elle s'arrête par une **touche de volume** ou le bouton « Arrêter »
  sur le téléphone, ou **à distance** : la fenêtre « Faire sonner » du parent
  montre le compte à rebours (1 min) et un bouton « Arrêter la sonnerie ».
- **Écoute en direct visible** : un parent peut écouter le micro du téléphone
  de l'enfant. L'enfant voit un écran « Un parent t'écoute » et une
  notification permanente ; rien n'est enregistré (voir
  [docs/ECOUTE-DIRECTE.md](docs/ECOUTE-DIRECTE.md)).

### Côté parent
- Connexion par **e-mail et mot de passe** : les adresses du serveur sont
  découvertes automatiquement, sinon elles se saisissent dans les réglages avancés.
- **Carte de chaque membre** sur l'accueil, en trois blocs : en-tête (état en
  ligne, puis ancienneté de la position sur sa propre ligne, batterie), position actuelle (lieu ou
  précision, Wi-Fi, sonnerie), activité du jour (pas, à pied, en voiture,
  objectif). Code couleur : vert = normal, orange = position approximative ou
  batterie faible, rouge = position ancienne ou batterie critique, gris = hors
  ligne ; la couleur du membre sert à l'objectif et au bouton « Voir sur la carte ».
- Ajout d'un enfant (Paramètres → Famille) : l'application crée l'appareil puis propose, au choix, un
  **QR code** à scanner sur place ou un **code d'invitation** de 8 chiffres à
  transmettre à distance.
- **Ajout d'un autre parent, à distance** (Paramètres → Famille) : saisir son
  prénom suffit. L'application crée son compte (non administrateur), lui partage
  toute la famille et affiche un **code d'invitation** de 8 chiffres. Sur son
  téléphone : « J'ai un code d'invitation », les 8 chiffres, et c'est tout (ni
  adresse, ni e-mail, ni mot de passe). Chaque ajout suivant (enfant, lieu) lui
  est partagé automatiquement.
- **Codes d'invitation** : valables 1 h, à usage unique, essais limités ; à ne
  transmettre qu'à la bonne personne.
- Partage de sa propre position (optionnel) et **mode fantôme** (invisible des
  enfants et des autres parents).
- Couleurs de profil (palette ou sélecteur libre), photos, objectifs de pas,
  limite de vitesse.
- **Ordre d'affichage des membres** par glisser-déposer (Paramètres → Famille),
  partagé par toute la famille : liste, carte, historique.
- **Désactiver un membre** provisoirement (Paramètres → Famille → Membres
  affichés) : il disparaît de l'accueil et de la carte pour toute la famille.
  Son suivi et ses alertes continuent ; un SOS le fait réapparaître.
- **Check-up du téléphone** (Paramètres, en tête) : connexion au serveur,
  notifications autorisées, téléphone inscrit aux alertes, alerte SOS active,
  et, si la position est partagée, suivi actif et autorisations.
- Verrouillage par **PIN** ou **empreinte**, délai de reverrouillage réglable
  (1 min → jamais). **Modifier le code PIN parent** : Paramètres → Sécurité.
- **Mise à jour depuis l'application** : notification quand une version sort,
  téléchargement, vérification SHA-256, installation ; recherche à la demande
  dans Paramètres → À propos.
- Paramètres rangés **par catégories**, comme sur Android : Ma position,
  Alertes, Lieux, Famille, Sécurité, Compte, À propos (chaque ligne résume
  l'état actuel).

### Côté enfant
- Écran simple : état du suivi, pas du jour, SOS, « je suis bien arrivé·e ».
- **Mises à jour** : notification et bandeau sur l'accueil de l'enfant aussi
  (téléchargement et installation en deux touches).
- Carte de la famille : l'enfant s'y voit, avec ses frères et sœurs et les
  parents qui partagent leur position.
- **Localisation éteinte** : bandeau rouge sur l'accueil (enfant, et parent qui
  partage sa position) avec un bouton « Activer la localisation » qui ouvre le
  réglage Android.
- **Check-up du téléphone** (réglages, en tête) : tout ce qu'il faut pour un
  suivi fiable, au vert ou à corriger d'un bouton — localisation du téléphone
  allumée, liaison, suivi actif,
  dernier envoi, accès famille, localisation « Toujours », activité,
  notifications, batterie, et en facultatif micro / affichage par-dessus /
  « Ne pas déranger ».
- **Code d'invitation en 8 cases** : un collage les remplit d'un coup, la
  liaison part dès le 8e chiffre (bouton « Coller le code »).
- **Lecture du QR code** en portrait, avec un cadre de visée au centre, une
  lampe et une lecture automatique.
- **Liaison sans configuration** : « Enfant », code PIN parent, puis QR code ou
  code d'invitation ; les autorisations s'enchaînent ensuite avec un seul
  bouton. Aucune adresse ni clé n'est affichée, ni côté enfant ni dans le QR
  code côté parent.
- Réglages protégés par le PIN parent, rangés par catégories : Suivi (état,
  « Relier de nouveau », démarrer / arrêter), Écoute en direct, Sécurité
  (modifier le code PIN parent, changer de mode), À propos.

### Garder la position du parent privée

Pour suivre les enfants sans être suivi, laisse **Partager ma position**
désactivé. Couper le partage arrête les envois **et** retire aux enfants l'accès
à ta dernière position.

Le **mode fantôme** garde le partage actif (utile pour l'alerte « retour près de
toi » et ton propre historique) mais **personne ne voit ta position** : ni les
enfants, ni les autres parents. Ton appareil est masqué dans leur application,
retiré des comptes parents non administrateurs et détaché des lieux (pas de
notification « vient d'arriver » à ton sujet). Limite : un compte
**administrateur** Traccar garde accès à tout depuis l'interface web ;
l'application prévient dans ce cas.

Les alertes des lieux et l'historique des enfants fonctionnent dans tous les cas.

---

## Architecture

```
 Téléphone enfant ──HTTPS (protocole OsmAnd)──┐
 Téléphone parent (partage optionnel) ────────┤
                                              ▼
                                  ┌────────────────────────┐
                                  │  Traccar 6.17           │  ← alertes : lieux (avec
                                  │  :5055 réception        │    calendrier), SOS, batterie,
                                  │  :8082 API + WebSocket  │    vitesse, proximité…
                                  └───────────┬────────────┘
                                              │ JDBC
                                  ┌───────────▼────────────┐
                                  │ TimescaleDB (PG 17)     │  rétention 30 jours
                                  └────────────────────────┘
 Téléphone parent ──REST + WebSocket──▶ Traccar
                  ◀──notifications──── Firebase / Telegram (envoyées par Traccar)

 config-api  : dépôt d'APK (mises à jour), découverte des adresses du serveur,
               codes de configuration
 audio-relay : relais de l'écoute en direct (ne stocke rien)
```

### Pourquoi Traccar plutôt qu'un serveur maison

Traccar fournit déjà, sans code, de quoi tenir le serveur :
- la réception GPS et le stockage des positions ;
- les zones avec calendrier, les événements et la proximité entre appareils ;
- les notifications, dont Firebase direct vers une application maison ;
- les rapports de trajets.

On n'écrit que ce qui manque : l'application Android et deux petits services.

### Services tiers utilisés

Les positions et les événements sont stockés sur ton serveur. Trois services
extérieurs interviennent, en option ou pour l'affichage :
- **Firebase** achemine les notifications et les commandes à distance (réveil,
  sonnerie, écoute) ;
- **Telegram** est un canal d'alerte facultatif côté serveur ;
- le **fond de carte** utilise des tuiles distantes (OpenFreeMap / OpenStreetMap).

---

## Contenu du dépôt

| Chemin | Rôle |
|---|---|
| `android/` | Application Kotlin / Compose, un seul APK pour les deux modes (détails : [android/README.md](android/README.md)) |
| `docker-compose.yml` | Traccar 6.17, TimescaleDB 2.32 (PG 17) et relais audio ; volumes nommés fixes |
| `ops/retention.sql` | Rétention TimescaleDB (positions 30 jours, événements 90 jours), idempotente |
| `server/traccar/templates/fr/` | Textes des notifications en français, montés dans le conteneur Traccar |
| `server/config-api/` | Dépôt d'APK, `server.json` (découverte), codes de configuration |
| `server/audio-relay/` | Relais WebSocket de l'écoute en direct |
| `.github/workflows/android.yml` | Compilation, tests, signature et publication de l'APK |
| `docs/` | Documentation détaillée (voir plus bas) |
| `AGENTS.md`, `CLAUDE.md` | Consignes pour les assistants de code |

---

## Installer le serveur

### En local (essai)

Copie `.env.example` dans `.env` et définis un mot de passe de base fort. Le
Compose du dépôt ne publie aucun port. Pour accéder à Traccar depuis la machine
de développement, ajoute un fichier `compose.local.yml` :

```yaml
services:
  tracker:
    ports:
      - "127.0.0.1:8082:8082"
      - "127.0.0.1:5055:5055"
```

```sh
docker compose -f docker-compose.yml -f compose.local.yml up -d
docker compose logs -f tracker
```

Interface web Traccar : http://localhost:8082

### Premier compte = administrateur

**Traccar 6 n'a plus de compte `admin` / `admin`.** Le premier compte créé
devient administrateur, puis l'inscription publique se referme. Donc :

- créer ce compte **immédiatement** après le premier démarrage, par un accès
  privé, **avant** de publier l'API (sinon le premier visiteur devient
  administrateur) ;
- pour un second parent plus tard : rouvrir temporairement l'inscription
  depuis l'interface, créer le compte, puis la refermer.

```bash
curl -s -X POST http://localhost:8082/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"Parent","email":"parent@exemple.fr","password":"<mot de passe fort>"}'
# → administrator: true sur le premier compte uniquement
```

### Rétention (une seule fois, après l'initialisation de Traccar)

Depuis la racine du dépôt :

```sh
docker compose exec -T tracker-db psql -v ON_ERROR_STOP=1 -U tracker -d tracker < ops/retention.sql
```

### Déploiement Dokploy

1. Service de type **Compose**, mode Git, `composePath: ./docker-compose.yml`.
   Définir les variables d'environnement (voir plus bas).
2. Créer le premier compte administrateur par un accès privé, avant de publier
   l'API.
3. Déclarer les domaines (HTTPS Let's Encrypt). Dokploy injecte les routes
   Traefik : ne pas mettre de labels dans le YAML.

   | Domaine | Service | Port | Usage |
   |---|---|---|---|
   | `track.<domaine>` | `tracker` | 5055 | réception des positions |
   | `api.<domaine>` | `tracker` | 8082 | interface, API REST, WebSocket |
   | `<domaine-famille>` chemin `/audio` | `audio-relay` | 8765 | écoute en direct |

4. Appliquer la rétention.

Le service `config-api` (dépôt d'APK) se déploie à part, sur `<domaine-famille>`
chemin `/apk`. Définis un code d'accès propre à ton instance (`KW_APK_CODE`).

### Variables d'environnement

| Variable | Où | Rôle |
|---|---|---|
| `TRACKER_DB_PASSWORD` | compose | mot de passe de la base |
| `FIREBASE_SERVICE_ACCOUNT` | compose | compte de service Firebase (notifications, réveil des téléphones) |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` | compose | notifications Telegram (optionnel) |
| `KW_TRACCAR_API_URL`, `KW_TRACCAR_TRACK_URL` | config-api | adresses annoncées à l'application (`server.json`) |
| `KW_AUDIO_RELAY_URL` | config-api | adresse `wss://` du relais audio |
| `KW_TRACCAR_INTERNAL_URL` | config-api | facultatif : adresse interne de Traccar pour vérifier la connexion des parents qui créent un code d'invitation (sinon `KW_TRACCAR_API_URL`) |
| `KW_APK_CODE`, `KW_ADMIN_TOKEN` | config-api | code d'accès au dépôt d'APK (à changer), jeton de dépôt |
| `KW_APK_BASE_URL`, `KW_BASE_URL` | config-api | adresses publiques du dépôt et des codes (facultatif : déduites des requêtes sinon) |

Les secrets restent dans l'environnement Dokploy, **jamais dans le dépôt**.

---

## Compiler et distribuer l'application

La compilation tourne sur GitHub Actions (JDK 21 ; le SDK Android ne fournit
`aapt2` qu'en x86-64). À chaque push :

1. tests unitaires puis APK signé ;
2. vérification du certificat attendu et de l'identifiant `fr.kinwaypoint.app` ;
3. publication dans la release `test-latest` (avec `version.json`) ;
4. dépôt sur le dépôt d'APK, d'où l'application se met à jour.

En local, depuis `android/` (avec `KW_FAMILY_HUB` défini, voir ci-dessous) :

```sh
./gradlew testReleaseUnitTest assembleRelease
```

Secrets GitHub nécessaires :
- `KW_KEYSTORE_BASE64`, `KW_KEYSTORE_PASSWORD`, `KW_KEY_ALIAS`, `KW_KEY_PASSWORD` :
  clé de signature ;
- `KW_FAMILY_HUB` : adresse du serveur familial (`https://…`), compilée dans
  l'application. Elle n'est écrite nulle part dans le dépôt, et sans elle la CI
  refuse de produire l'APK ;
- `KW_HUB_TOKEN` : jeton de dépôt des APK.

> **Garder une copie de la clé de signature et augmenter `versionCode` à chaque
> livraison.** Android n'accepte une mise à jour que signée par la même clé ;
> la perdre oblige à désinstaller l'application sur tous les téléphones.

---

## Mise en route d'une famille

1. **Téléphone parent** : installer l'APK, choisir « Parent », créer le PIN, se
   connecter avec l'e-mail et le mot de passe Traccar.
2. **Ajouter un enfant** : Paramètres → Famille → « Ajouter un enfant » →
   prénom → un QR code (ou un code d'invitation) s'affiche.
   Le compte Traccar du parent doit pouvoir créer des appareils.
3. **Téléphone enfant** : installer l'APK, choisir « Enfant », créer le code
   PIN parent, puis **« Scanner le QR code »** (ou, à distance, taper les 8
   chiffres du code d'invitation). Aucune adresse ni clé à voir ou à saisir :
   le téléphone se relie tout seul et le suivi est activé.
4. L'appli enchaîne ensuite les autorisations avec un seul bouton :
   localisation, puis **« Toujours autoriser »**, activité physique,
   notifications, batterie sans restriction ; et, en facultatif, le micro et
   l'affichage par-dessus (écoute en direct) et « Ne pas déranger ».

   Vérifier qu'une position arrive écran éteint et remonte après une coupure
   réseau.
5. Créer les lieux (Paramètres → **Lieux**) et activer les alertes voulues
   (Paramètres → **Alertes**).
6. **Deuxième parent** : Paramètres → Famille → « Ajouter un parent » (prénom)
   → transmettre le code d'invitation. Sur son téléphone : installer l'APK,
   « J'ai un code d'invitation », taper les 8 chiffres, créer son PIN.

Le QR code contient le secret d'envoi de l'appareil : le garder comme un mot de
passe. Voir aussi [Configuration à distance](docs/CONFIG-DISTANTE.md) et
[Dépôt et mises à jour d'APK](docs/DEPOT-APK.md).

---

## Règles de sécurité

- **L'identifiant d'appareil est le mot de passe** : le protocole OsmAnd
  n'authentifie rien. Identifiants longs et aléatoires (28 caractères, générés
  par l'application), `registerUnknown` désactivé.
- Réception (5055) : seule route publique d'écriture, HTTPS terminé par
  Traefik ; limiter le débit côté Traefik.
- Interface / API (8082) : jamais exposée sans authentification. L'application
  ne conserve qu'un jeton (valable 1 an), jamais le mot de passe.
- Les enfants voient la famille via un compte **en lecture seule** dédié.
- Codes d'invitation : 8 chiffres, 1 h, usage unique, 10 essais ratés par
  connexion et 200 au total par tranche de 10 min ; création réservée à un
  parent connecté (administrateur pour inviter un parent). Pendant 1 h, le code
  vaut accès à la famille : le transmettre en privé.
- PIN stocké sous forme d'empreinte (PBKDF2 salé), blocage progressif après
  5 essais.
- Écoute en direct : toujours visible sur le téléphone de l'enfant, sans
  enregistrement, salle à usage unique, 10 minutes au maximum.
- Données de mineurs : hébergement en Europe, accès restreint, rétention courte,
  aucun SDK d'analyse tiers.
- Aucune donnée personnelle dans ce dépôt (ni nom réel, ni adresse réelle) :
  utiliser des valeurs d'exemple (`<domaine>`, `exemple.fr`).

---

## Feuille de route

Les priorités suivent l'usage familial réel. Les fiches de trajet, les lieux
fréquents et les cercles de Findmykids servent de référence fonctionnelle.

| Statut | Fonction |
|---|---|
| ✅ Fait | Fiche par lieu : visites, dernier passage, temps cumulé (7 ou 30 jours) |
| ✅ Fait | Ancienneté et précision de la position visibles (couleurs, cercle de précision) |
| ✅ Fait | Couper le partage parent retire aussi l'accès des enfants |
| ✅ Fait | Relecture d'un trajet avec lecture, pause et curseur horaire |
| ✅ Fait | Horaires et personnes concernés par lieu, suggestion des lieux fréquents |
| ✅ Fait | Fréquence de suivi réglable à distance, état du Wi-Fi et Wi-Fi des lieux |
| ✅ Fait | Sonnerie arrêtée par une touche de volume, forcée en « Ne pas déranger » |

| Difficulté | À ajouter ou à renforcer | Point de départ |
|---|---|---|
| **Facile à moyenne** | Rechercher une adresse pour créer un lieu | Géocodage (de préférence auto-hébergé) dans l'écran des lieux |
| **Moyenne** | Afficher les coupures GPS pendant la relecture d'un trajet | Repérer les trous entre points horodatés |
| **Complexe** | Améliorer l'heure d'arrivée : moteur d'itinéraires, mode de déplacement, positions périmées | Faire évoluer l'estimation actuelle, puis mesurer les écarts sur des trajets réels |
| **Complexe** | Plusieurs cercles, rôles et révocation d'un parent | L'ajout d'un parent existe (un seul cercle) ; reste la séparation des cercles et le retrait d'un compte depuis l'appli |

En continu : mesurer la consommation de batterie et la fiabilité du suivi et
des notifications sur les modèles Android réellement utilisés.

La messagerie familiale, les montres GPS et les contrôles d'applications sont
des chantiers distincts, hors du périmètre actuel.

[Rapports de trajet Findmykids](https://findmykids.org/blog/en/driving-reports-findmykids)
· [Lieux fréquents](https://findmykids.org/blog/en/findmykids-places-section)
· [Cercles familiaux](https://findmykids.org/blog/en/findmykids-app-update-family-circles)

---

## Pièges connus

1. **C'est Android qui coupe le suivi, pas le serveur.** Service au premier
   plan, localisation « toujours », exemption batterie, et un test réel sur
   chaque modèle de téléphone (certaines surcouches ajoutent leur propre
   « démarrage automatique » à autoriser).
2. **Volumes Docker** : sans `name:` fixe, Dokploy préfixe le volume et la base
   est perdue au redéploiement suivant.
3. **Alias `db`** déjà pris sur le réseau Dokploy : d'où `tracker-db`.
4. **Mémoire Traccar** : l'image n'a pas d'entrypoint shell, `JAVA_OPTS` est
   ignoré ; le heap se règle en surchargeant `entrypoint` + `command`.
5. **`tc_positions.attributes` est un `varchar`**, pas du `jsonb` :
   `attributes::jsonb->>'batteryLevel'` pour lire la batterie en SQL.
6. **Traccar refuse les mises à jour partielles** (PUT) : relire l'objet
   complet, le modifier, le renvoyer.
7. **Nom du Wi-Fi** : Android ne le donne qu'avec la localisation accordée et
   activée ; sinon seul l'état (activé / coupé) remonte.

---

## Documentation

| Document | Contenu |
|---|---|
| [android/README.md](android/README.md) | Application : modes, PIN, suivi, adresse du serveur, signature |
| [docs/DEPOT-APK.md](docs/DEPOT-APK.md) | Dépôt d'APK, mises à jour, `server.json` |
| [docs/CONFIG-DISTANTE.md](docs/CONFIG-DISTANTE.md) | Codes de configuration (lien ou QR) et format JSON |
| [docs/ECOUTE-DIRECTE.md](docs/ECOUTE-DIRECTE.md) | Écoute en direct : fonctionnement, déploiement, cadre |
| [docs/PLAN.md](docs/PLAN.md) | Décisions de départ et feuille de route initiale |
| [docs/P0-validation.md](docs/P0-validation.md) | Validation du socle serveur |
| [AGENTS.md](AGENTS.md) | Consignes pour les assistants de code (à tenir à jour avec ce README) |
