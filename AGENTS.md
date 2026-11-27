# AGENTS.md — consignes pour les assistants de code

Ce fichier guide tout agent (Claude Code, Codex, Copilot…) qui travaille sur
KinWaypoint. Présentation du projet pour les humains : [README.md](README.md).

## Règle de maintenance : README.md et AGENTS.md à jour

**À chaque modification qui change le comportement, l'installation, la
configuration ou l'organisation du projet, mettre à jour dans le même commit :**

- `README.md` : fonctionnalités, installation, variables, feuille de route
  (cocher ce qui est fait, ajouter ce qui est prévu) ;
- `AGENTS.md` (ce fichier) : structure, commandes, conventions, pièges ;
- et, si concerné : `android/README.md`, `docs/*.md`.

Une modification purement interne (refactorisation sans effet visible) ne
demande pas de mise à jour. En cas de doute, mettre à jour.

## Langue et ton

- Tout en **français** : réponses à l'utilisateur, messages de commit, textes de
  l'application, commentaires, documentation.
- Utilisateurs non informaticiens : expliquer simplement, sans jargon non expliqué.
- Signaler les risques (sécurité, vie privée, juridique) plutôt que valider pour
  faire plaisir. Ne jamais présenter comme testé ce qui ne l'a pas été sur un
  vrai téléphone.

## Structure du dépôt

| Chemin | Contenu |
|---|---|
| `android/app/src/main/java/fr/kinwaypoint/app/` | Application Kotlin / Compose (un seul APK, modes Parent et Enfant) |
| `…/service/` | Suivi (`TrackingService`), écoute en direct, sonnerie, redémarrage |
| `…/net/` | API Traccar, envoi OsmAnd, relais audio, configuration à distance |
| `…/ui/` | Écrans Compose (carte `MapBase.kt`/`MapScreen.kt`, historique, lieux, réglages…) |
| `…/motion/` | Vitesse, activité, podomètre, heure d'arrivée |
| `android/app/src/test/` | Tests unitaires JVM (lancés par la CI) |
| `docker-compose.yml` | Traccar 6.17 + TimescaleDB + relais audio (déployé par Dokploy) |
| `server/traccar/templates/fr/` | Textes des notifications (Velocity), montés dans Traccar |
| `server/config-api/` | Dépôt d'APK, `server.json`, codes de configuration et d'invitation (Python, sans dépendance) |
| `server/audio-relay/` | Relais WebSocket de l'écoute en direct (Python, `websockets`) |
| `.github/workflows/android.yml` | Tests, compilation, signature, publication |
| `docs/` | Documentation détaillée |

## Compiler, tester, livrer

- **La compilation Android se fait sur GitHub Actions** (le SDK ne fournit
  `aapt2` qu'en x86-64). Pousser sur la branche de travail lance tests + APK.
  Vérifier le résultat du build (et lire le journal en cas d'échec) avant
  d'annoncer qu'une version est prête.
- En local, depuis `android/` : `./gradlew testReleaseUnitTest assembleRelease`,
  avec `KW_FAMILY_HUB` défini (variable d'environnement ou `kwFamilyHub` dans
  `~/.gradle/gradle.properties`).
- **Chaque livraison incrémente `versionCode` et `versionName`** dans
  `android/app/build.gradle.kts` : le dépôt d'APK et les téléphones ne proposent
  que le plus grand `versionCode`.
- Python serveur : vérifier au minimum la syntaxe (`python3 -c "import ast; …"`)
  et, quand c'est possible, lancer le service localement et l'interroger.
- Modèles Traccar (Velocity) et calendriers (iCalendar) : les valider avec les
  mêmes bibliothèques que Traccar 6.17 (Velocity 2.4.1, ical4j 4.4.0), comme
  pour `PlaceScheduleTest`.

## Secrets et données personnelles

- **Jamais dans le dépôt** : mots de passe, jetons, clé de signature, adresse
  réelle du serveur familial, noms réels. Utiliser `<domaine>`, `exemple.fr`,
  des prénoms d'exemple.
- L'adresse du serveur familial arrive à la compilation : `BuildConfig.FAMILY_HUB`
  (secret GitHub `KW_FAMILY_HUB`). Le serveur `config-api` déduit ses adresses
  des requêtes ou de `KW_APK_BASE_URL` / `KW_BASE_URL`.
- Secrets GitHub : `KW_KEYSTORE_BASE64`, `KW_KEYSTORE_PASSWORD`, `KW_KEY_ALIAS`,
  `KW_KEY_PASSWORD`, `KW_FAMILY_HUB`, `KW_HUB_TOKEN`. Ne jamais afficher leur
  valeur dans un journal de CI.
- L'identifiant d'appareil Traccar est le secret d'envoi des positions : ne
  jamais l'écrire dans un journal ni une capture.
- Écoute en direct : toujours visible côté enfant, jamais d'enregistrement.
  Ne pas proposer d'écoute ou de suivi cachés.

## Conventions de code

- Kotlin + Jetpack Compose, Material 3. Textes d'interface en français, écriture
  neutre quand c'est possible (« vient d'arriver » plutôt que « est arrivée »).
- Commentaires en français, qui expliquent le *pourquoi*.
- Carte MapLibre : toute opération sur le `Style` passe par `Style.safely { }` ;
  ne cadrer qu'avec `frameAll` (gère un seul point et la carte pas encore mesurée).
- Opérations réseau hors du thread principal (`Dispatchers.IO`), erreurs
  rattrapées (`runCatching`) : une erreur réseau ne doit jamais fermer l'appli.
- Les traces GPS affichées ou mesurées passent par `cleanTrace` (bruit GPS à
  l'arrêt) ; distances par mode via `travelSplit` (à pied / en voiture).
- Ordre des membres : attribut `kwOrder` des appareils, appliqué une seule fois
  dans `TraccarApi.members()` (puis ordre alphabétique) ; les écrans ne retrient
  pas la liste.
- Écrans défilants : marge basse d'au moins 40 dp (`contentPadding` ou
  `padding(bottom = …)`) ; un écran hors `Scaffold` ajoute
  `navigationBarsPadding()` (appli en plein écran, `targetSdk` 35), sinon les
  derniers boutons passent sous la barre de navigation.
- Lieu affiché d'une personne : toujours via `placeFor` (Wi-Fi du lieu d'abord,
  puis position).
- Couleurs porteuses de sens (cartes membres, pastilles) : vert = normal, orange
  = approximatif / batterie faible, rouge = ancien / SOS / batterie critique,
  gris = hors ligne ou neutre ; la couleur du membre est réservée à l'avatar, à
  l'objectif de pas et à l'action principale. « Ajouter un enfant » n'existe que
  dans Paramètres → Famille.
- Téléphone enfant : jamais de champ technique (adresse, identifiant, jeton)
  à l'écran. La liaison passe par `ChildLinkScreen` (QR ou code, via
  `resolveConfigCode`), qui active le suivi puis enchaîne les autorisations.
  Le QR affiché côté parent ne montre ni serveur ni identifiant en clair.
- Curseurs : toujours `KwSlider` (poignée ronde contourée, piste fine), jamais
  le `Slider` Material brut. Rangées de gélules (`FilterChip`) dans un
  `FlowRow` pour qu'elles passent à la ligne au lieu d'être écrasées.

## Pièges connus

1. **Traccar refuse les PUT partiels** : relire l'objet complet (appareil,
   utilisateur, lieu, calendrier), le modifier, le renvoyer.
2. **Paramètres réservés du protocole OsmAnd** (`id`, `lat`, `lon`, `wifi`,
   `cell`, `speed`, `batt`…) : ne pas les réutiliser pour des attributs maison
   (le nom du Wi-Fi s'envoie en `ssid`). Vitesse en **nœuds** côté Traccar.
3. **Langue et heure des notifications** : attributs `language=fr` et
   `timezone` du compte parent (posés par l'appli) ; sinon textes anglais et
   heure UTC.
4. **Proximité parent / enfant** : calculée par Traccar (appareils liés,
   attribut `proximityEnterDistance` sur l'enfant, règle `proximityEnter`).
5. **Horaires d'un lieu** : calendrier Traccar (`calendarId`), iCalendar avec
   `TZID` ; format couvert par `PlaceScheduleTest`.
6. **Android coupe le suivi**, pas le serveur : service au premier plan,
   localisation « toujours », exemption batterie, tests sur vrais téléphones.
   `TrackingWatchdog` (WorkManager) relance le service s'il est activé mais
   arrêté ; il ne relance jamais un suivi désactivé volontairement
   (`config.enabled`). Toujours passer `enabled = false` AVANT d'arrêter le
   service pour un arrêt voulu. « Forcer l'arrêt » annule tout jusqu'à la
   réouverture de l'appli : rien à faire côté code.
7. **Docker / Dokploy** : volumes avec `name:` fixe ; service base nommé
   `tracker-db` (l'alias `db` est déjà pris) ; heap Traccar réglé par
   `entrypoint` + `command`, pas `JAVA_OPTS`.
8. **`tc_positions.attributes` est un `varchar`** : `attributes::jsonb->>'…'`.
9. **Mode fantôme d'un parent** : retrait du compte lecteur des enfants
   (`setVisibleToChildren`) + `setGhost` (attribut `kwGhost` filtré par les
   applis des autres parents, accès retiré aux comptes parents non
   administrateurs, appareil détaché des lieux). Un administrateur Traccar voit
   tout côté serveur : on ne peut que le masquer dans l'appli. Toute nouvelle
   vue des membres doit partir de la liste déjà filtrée de l'accueil parent.
10. **Podomètre** : `TYPE_STEP_COUNTER` ne donne rien tant qu'on ne marche
    pas. La valeur du jour est relue dans les préférences (`stepsToday`) pour
    que chaque position parte avec ses pas ; côté parent, une position sans pas
    garde ceux de la précédente du même jour (`keepSteps`).
11. **Autres parents** : comptes créés par l'appli d'un administrateur
    (`createParentAccount`), jamais administrateurs. Le partage de la famille
    (`shareFamilyWithParents` : appareils hors mode fantôme et lieux) tourne
    dans la synchro de l'accueil ; il ne fait rien depuis un compte non
    administrateur (liste des comptes refusée).
12. **Codes d'invitation** : même format que les codes de configuration
    (`RemoteConfig`), déposés par `POST /apk/invite` (jeton Traccar vérifié par
    le serveur) et rendus une fois par `POST /apk/invite/redeem`. Une invitation
    parent contient la clé de connexion (`parent.token`) du compte créé, jamais
    de mot de passe. Toute modification de ces routes se teste en local avec un
    faux Traccar (essais limités, usage unique, administrateur pour un parent).
13. **Commandes Traccar et Firebase** : dès qu'un appareil a un jeton
    `notificationTokens` et que `COMMAND_CLIENT_SERVICE_ACCOUNT` est défini,
    Traccar envoie TOUTES les commandes par Firebase avec leur seul type (sans
    `data`) et ne les met jamais en file. Les demandes avec contenu (sonner,
    écoute en direct) passent donc par des attributs de l'appareil (`kwRing`,
    `kwListen`) que le téléphone lit au réveil Firebase et après chaque envoi
    (`RemoteActions`) ; la commande `custom` ne sert que de secours sans Firebase.
14. **Fréquence de suivi à distance** : attributs `kwIntNormal`, `kwIntMoving`,
    `kwIntStill` (secondes) de l'appareil, lus par `RemoteActions` et appliqués
    par `TrackingService.reload()`. Il faut un accès famille configuré sur le
    téléphone (jeton parent ou lecteur), sinon rien n'est lu.
15. **Wi-Fi** : `wifiOn` (activé ou non) et `ssid` (nom, seulement si la
    localisation est accordée et activée). `FixQueue` en version 4 (colonne
    `wifi_on`) : toute nouvelle colonne passe par une montée de version.
16. **Sonnerie** (`RingAlarm`) : forcer le son en « Ne pas déranger » exige
    l'autorisation `ACCESS_NOTIFICATION_POLICY` accordée à la main par
    l'utilisateur ; le mode sonnerie et le filtre sont rétablis à l'arrêt. Les
    touches de volume sont captées par une `MediaSession` active pendant la
    sonnerie (marche écran allumé ; écran éteint, cela dépend du fabricant).

## Git

- Travailler sur la branche indiquée par la session ; ne jamais réécrire
  l'historique partagé ni forcer un push.
- Messages de commit en français : un titre court (avec la version si
  l'APK change), puis le pourquoi.
- Avant de résoudre un conflit, lire ce qui a été modifié sur `main` et le
  conserver ; vérifier qu'aucun marqueur `<<<<<<<` ne reste avant de valider.
