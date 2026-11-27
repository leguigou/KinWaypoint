# KinWaypoint

> **Kin** — les siens. **Waypoint** — le point de route.
> Le point de ralliement de la famille sur une carte.

Suivi de localisation familial **auto-hébergé**. Une seule application Android
(Kotlin / Jetpack Compose), deux modes — **Parent** et **Enfant** — protégés par
un code PIN, adossée à un serveur **Traccar** qui collecte, stocke et déclenche
les alertes.

- Aucune dépendance à un service commercial de géolocalisation : les données
  restent sur votre serveur.
- Historique limité dans le temps par construction (30 jours).
- Distribution hors Play Store (APK signé, mise à jour depuis l'application).

---

## Fonctionnalités

### Carte et suivi
- Carte familiale (MapLibre + OpenStreetMap, sans clé d'API) : avatar photo ou
  initiale de chacun, mise à jour **en direct** (WebSocket).
- Fiches glissantes par personne : lieu actuel et durée (« 🏠 Maison · 2 h 26 »),
  batterie, sonnerie (silencieux / vibreur), Wi-Fi, distance, heure d'arrivée
  estimée vers un lieu.
- **Ancienneté et précision** de chaque position en couleur (vert / orange /
  rouge) et cercle de précision sur la carte.
- Pastilles sur l'avatar : 🚗 / 🚶 avec la vitesse, 🔇 silencieux, 🪫 batterie
  faible, SOS.
- Distance du jour **à pied** et **en voiture**, podomètre et objectif de pas.
- Fréquence adaptative pour préserver la batterie :
  - une position toutes les **5 min** en temps normal ;
  - toutes les **15 s** dès qu'un déplacement est détecté (pas, vitesse) ;
  - toutes les **10 min** à l'arrêt, GPS coupé. L'arrêt est détecté par le
    podomètre et l'accéléromètre, et la position est figée pour ne pas
    enregistrer le « tremblement » du GPS.

### Lieux et alertes
- Lieux (maison, école…) avec icône, couleur, rayon, **personnes concernées**
  et **horaires** (ex. lun–ven 08:00–17:00, créneaux passant minuit).
- **Fiche par lieu** : dernier passage, nombre de visites, temps cumulé, par
  personne, sur 7 ou 30 jours.
- **Lieux fréquents suggérés** à partir des 14 derniers jours (« Maison ? »,
  « École ? »).
- Notifications **même application fermée** (Firebase, et Telegram en option),
  rédigées en français :
  - arrivée / départ d'un lieu ;
  - SOS (avec adresse) ;
  - batterie faible ;
  - « je suis bien arrivé·e » ;
  - excès de vitesse ;
  - pas de nouvelles depuis 1 h ;
  - **retour près d'un parent** (proximité calculée par le serveur).

### Historique
- Jour par jour, du plus récent au plus ancien : arrêts et trajets (marche /
  voiture) avec distance, durée, vitesses moyenne et maximale.
- **Relecture d'une journée** avec curseur horaire.
- Bilan de la semaine : pas, objectif, distance, temps passé dans chaque lieu.

### Sécurité de l'enfant
- **SOS** par appui long (3 s) : part immédiatement, même sans réseau (file
  d'attente persistante).
- **Faire sonner** le téléphone à distance (même en silencieux) et **demander
  sa position** tout de suite.
- **Écoute en direct visible** : un parent peut écouter le micro du téléphone
  de l'enfant. L'enfant voit un écran « Un parent t'écoute » et une
  notification permanente ; rien n'est enregistré (voir
  [docs/ECOUTE-DIRECTE.md](docs/ECOUTE-DIRECTE.md)).

### Côté parent
- Connexion par **e-mail et mot de passe** uniquement (les adresses du serveur
  sont découvertes automatiquement).
- Ajout d'un enfant : l'application crée l'appareil et affiche un **QR code** à
  scanner sur le téléphone de l'enfant.
- Partage de sa propre position (optionnel) et **mode fantôme** (invisible des
  enfants).
- Couleurs de profil (palette ou sélecteur libre), photos, objectifs de pas,
  limite de vitesse.
- Verrouillage par **PIN** ou **empreinte**, délai de reverrouillage réglable
  (1 min → jamais).
- **Mise à jour depuis l'application** : notification quand une version sort,
  téléchargement, vérification SHA-256, installation.

### Côté enfant
- Écran simple : état du suivi, pas du jour, SOS, « je suis bien arrivé·e ».
- Carte de la famille : l'enfant s'y voit, avec ses frères et sœurs et les
  parents qui partagent leur position.
- Réglages protégés par le PIN parent.

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

Traccar fournit déjà, sans code : la réception GPS, le stockage des positions,
les zones avec calendrier, les événements, les notifications (dont Firebase
direct vers une application maison), la proximité entre appareils et les
rapports de trajets. On n'écrit que ce qui manque : l'application Android et
deux petits services.

---

## Contenu du dépôt

| Chemin | Rôle |
|---|---|
| `android/` | Application Kotlin / Compose, un seul APK pour les deux modes (détails : [android/README.md](android/README.md)) |
| `docker-compose.yml` | Traccar 6.17, TimescaleDB 2.32 (PG 17) et relais audio ; volumes nommés fixes |
| `ops/retention.sql` | Rétention TimescaleDB (positions 30 jours), idempotente |
| `server/traccar/templates/fr/` | Textes des notifications en français, montés dans le conteneur Traccar |
| `server/config-api/` | Dépôt d'APK, `server.json` (découverte), codes de configuration |
| `server/audio-relay/` | Relais WebSocket de l'écoute en direct |
| `.github/workflows/android.yml` | Compilation, tests, signature et publication de l'APK |
| `docs/` | Documentation détaillée (voir plus bas) |

---

## Installer le serveur

### En local (essai)

```bash
cp .env.example .env      # puis renseigner un mot de passe fort
docker compose up -d
docker compose logs -f tracker
```

Interface web Traccar : http://localhost:8082

### Premier compte = administrateur

**Traccar 6 n'a plus de compte `admin` / `admin`.** Le premier compte créé
devient administrateur, puis l'inscription publique se referme. Donc :

- créer ce compte **immédiatement** après le premier démarrage, **avant**
  d'exposer le domaine (sinon le premier visiteur devient administrateur) ;
- pour un second parent plus tard : rouvrir temporairement l'inscription
  depuis l'interface, créer le compte, puis la refermer.

```bash
curl -s -X POST http://localhost:8082/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"Parent","email":"parent@exemple.fr","password":"<mot de passe fort>"}'
# → administrator: true sur le premier compte uniquement
```

### Rétention (une seule fois, après le premier démarrage)

```bash
docker exec -i "$(docker compose ps -q tracker-db)" \
  psql -U tracker -d tracker < ops/retention.sql
```

### Déploiement Dokploy

Service de type **Compose**, mode Git, `composePath: ./docker-compose.yml`.
Domaines à déclarer (HTTPS Let's Encrypt) :

| Domaine | Service | Port | Usage |
|---|---|---|---|
| `track.<domaine>` | `tracker` | 5055 | réception des positions |
| `api.<domaine>` | `tracker` | 8082 | interface, API, WebSocket |
| `<domaine-famille>` chemin `/audio` | `audio-relay` | 8765 | écoute en direct |

Le service `config-api` (dépôt d'APK) se déploie à part, sur `<domaine-famille>`
chemin `/apk`. Ne pas mettre de labels Traefik dans le YAML : Dokploy les
injecte.

### Variables d'environnement

| Variable | Où | Rôle |
|---|---|---|
| `TRACKER_DB_PASSWORD` | compose | mot de passe de la base |
| `FIREBASE_SERVICE_ACCOUNT` | compose | compte de service Firebase (notifications, réveil des téléphones) |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` | compose | notifications Telegram (optionnel) |
| `KW_TRACCAR_API_URL`, `KW_TRACCAR_TRACK_URL` | config-api | adresses annoncées à l'application (`server.json`) |
| `KW_AUDIO_RELAY_URL` | config-api | adresse `wss://` du relais audio |
| `KW_APK_CODE`, `KW_ADMIN_TOKEN` | config-api | code d'accès au dépôt d'APK, jeton de dépôt |

Les secrets restent dans l'environnement Dokploy, **jamais dans le dépôt**.

---

## Compiler et distribuer l'application

La compilation tourne sur GitHub Actions (le SDK Android ne fournit `aapt2`
qu'en x86-64). À chaque push :

1. tests unitaires puis APK signé ;
2. publication dans la release `test-latest` (avec `version.json`) ;
3. dépôt sur le dépôt d'APK, d'où l'application se met à jour.

Secrets GitHub nécessaires : `KW_KEYSTORE_BASE64`, `KW_KEYSTORE_PASSWORD`,
`KW_KEY_ALIAS`, `KW_KEY_PASSWORD` (clé de signature) et `KW_HUB_TOKEN` (dépôt).

> **Garder une copie de la clé de signature.** Android n'accepte une mise à
> jour que signée par la même clé : la perdre oblige à désinstaller
> l'application sur tous les téléphones.

---

## Mise en route d'une famille

1. **Téléphone parent** : installer l'APK, choisir « Parent », créer le PIN, se
   connecter avec l'e-mail et le mot de passe Traccar.
2. **Ajouter un enfant** : « Ajouter un enfant » → prénom → un QR code s'affiche.
3. **Téléphone enfant** : installer l'APK, choisir « Enfant » (même PIN), puis
   Réglages (cadenas) → « Scanner le QR code » → démarrer le suivi.
4. Sur le téléphone de l'enfant, accorder :
   - la localisation **« Toujours autoriser »** ;
   - l'activité physique (podomètre) ;
   - les notifications ;
   - l'exemption d'optimisation de la batterie ;
   - et, pour l'écoute en direct, le micro et l'affichage par-dessus les
     autres applications.
5. Créer les lieux (Paramètres → Gérer les lieux) et activer les alertes
   voulues (Paramètres → Alertes).

---

## Règles de sécurité

- **L'identifiant d'appareil est le mot de passe** : le protocole OsmAnd
  n'authentifie rien. Identifiants longs et aléatoires (28 caractères, générés
  par l'application), `registerUnknown` désactivé. Ne pas photographier ni
  envoyer les QR codes.
- Réception (5055) : seule route publique d'écriture, HTTPS terminé par
  Traefik ; limiter le débit côté Traefik.
- Interface / API (8082) : jamais exposée sans authentification. L'application
  ne conserve qu'un jeton (valable 1 an), jamais le mot de passe.
- Les enfants voient la famille via un compte **en lecture seule** dédié.
- PIN stocké sous forme d'empreinte (PBKDF2 salé), blocage progressif après
  5 essais.
- Écoute en direct : toujours visible sur le téléphone de l'enfant, sans
  enregistrement, salle à usage unique, 10 minutes au maximum.
- Données de mineurs : hébergement en Europe, accès restreint, rétention courte,
  aucun SDK d'analyse tiers.
- Aucune donnée personnelle dans ce dépôt (ni nom réel, ni adresse réelle) :
  utiliser des valeurs d'exemple (`<domaine>`, `exemple.fr`).

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

---

## Documentation

| Document | Contenu |
|---|---|
| [android/README.md](android/README.md) | Application : modes, PIN, suivi, signature |
| [docs/DEPOT-APK.md](docs/DEPOT-APK.md) | Dépôt d'APK, mises à jour, `server.json` |
| [docs/CONFIG-DISTANTE.md](docs/CONFIG-DISTANTE.md) | Codes de configuration (lien ou QR) et format JSON |
| [docs/ECOUTE-DIRECTE.md](docs/ECOUTE-DIRECTE.md) | Écoute en direct : fonctionnement, déploiement, cadre |
| [docs/PLAN.md](docs/PLAN.md) | Décisions de départ et feuille de route initiale |
| [docs/P0-validation.md](docs/P0-validation.md) | Validation du socle serveur |
