# Dépôt d'APK

Chaque APK produit s'archive sur `https://<domaine-famille>/apk/` (exemple :
`https://famille.exemple.fr/apk/`), du plus récent au plus ancien, avec sa
version, sa taille et son empreinte SHA-256.

L'adresse du serveur familial n'est écrite nulle part dans le dépôt : l'appli la
reçoit à la compilation (secret GitHub `KW_FAMILY_HUB`, voir le README), et le
serveur déduit ses propres adresses des requêtes reçues (ou de `KW_APK_BASE_URL`
et `KW_BASE_URL` s'ils sont définis).

L'accès se fait par un **code** saisi dans un champ unique, à définir dans
`KW_APK_CODE` (ne pas garder la valeur par défaut du code). Une fois validé, le serveur dépose un cookie signé
(HMAC, 30 jours, `HttpOnly`, `Secure`) : plus rien à retaper ensuite.
`/apk/logout` efface le cookie.

## Consultation

```bash
# le code peut aussi passer en query, pratique pour un script
curl "https://famille.exemple.fr/apk/index.json?code=<code>"
```

`GET /apk/index.json` renvoie la liste en JSON. `GET /apk/<fichier>` sert le
fichier avec le bon type MIME.

Garde-fous du code d'accès :

- **10 essais par 15 minutes et par adresse IP**. L'IP réelle est lue en **fin**
  de chaîne `X-Forwarded-For` (celle que Traefik ajoute) : envoyer soi-même un
  en-tête falsifié ne contourne donc pas le verrou.
- **Fail-closed** : sans `KW_APK_CODE`, le dépôt répond `503` « non configuré »
  au lieu de retomber sur une valeur par défaut connue. Les routes publiques
  `latest.json` / `latest.apk` restent servies pour ne pas casser les mises à
  jour de l'appli.
- Au démarrage, le serveur journalise l'état des six variables critiques
  (`OK` / `MANQUE` avec la conséquence), jamais leur valeur.

## Mise à jour depuis l'appli (routes publiques)

Deux routes sont accessibles **sans code**, et ne concernent que la dernière
version (le plus grand `versionCode`, lu dans « 0.14.1 (15) ») :

| Route | Contenu |
|---|---|
| `GET /apk/latest.json` | `{"versionCode": 15, "versionName": "0.14.1", "sha256": "…", "apkUrl": "…/apk/latest.apk", …}` |
| `GET /apk/latest.apk` | le dernier APK |

L'appli consulte `latest.json` au lancement et toutes les 4 h (tâche Android
planifiée, même appli fermée : une notification par nouvelle version), télécharge l'APK, **vérifie son SHA-256**, puis lance l'installeur
Android (confirmation obligatoire). GitHub (`test-latest/version.json`) sert de
secours si le dépôt ne répond pas. La liste complète et les anciennes versions
restent derrière le code.

**Le serveur retient toujours le plus grand numéro de version.** Penser à
incrémenter `versionCode` à chaque livraison, quelle que soit la branche qui
compile.

## Connexion simplifiée (route publique)

`GET /apk/server.json` donne à l'appli les adresses du serveur Traccar, pour
que l'écran de connexion parent ne demande qu'un **e-mail** et un **mot de
passe** :

```json
{"version": 1, "apiUrl": "https://api.exemple.fr", "trackUrl": "https://track.exemple.fr"}
```

À configurer sur le conteneur `config-api` :

| Variable | Valeur |
|---|---|
| `KW_TRACCAR_API_URL` | adresse de l'interface / API Traccar (port 8082) |
| `KW_TRACCAR_TRACK_URL` | adresse d'envoi des positions (port 5055) |

Sans `KW_TRACCAR_API_URL`, la route répond `404` et l'appli propose de saisir
l'adresse dans « Réglages avancés ».

## Dépôt automatique

À chaque build, le workflow envoie l'APK au dépôt (étape « Déposer l'APK dans
le dépôt »), à condition que le secret `KW_HUB_TOKEN` existe. Sans ce secret,
l'étape s'ignore et le build reste vert.

Le fichier est nommé `kinwaypoint-<version>.apk` — le commit court figure dans
la note affichée sous la version, pas dans le nom. Un rebuild de la même version
écrase donc l'ancien fichier, ce qui est le comportement voulu.

## Dépôt manuel

```bash
curl -X POST --data-binary @app-release.apk \
  -H "X-Admin-Token: $KW_ADMIN_TOKEN" \
  -H "X-Filename: kinwaypoint-1.0.0.apk" \
  -H "X-Version: 1.0.0 (20)" \
  -H "X-Note: 62d0217 · première version stable" \
  https://famille.exemple.fr/apk/upload
```

Un fichier déposé directement dans le volume (`/data/apk/`) apparaît aussi dans
l'index : le disque fait foi, l'index JSON n'est qu'un complément.

## Limites

- 200 Mo maximum par fichier
- pas de suppression depuis l'interface : retirer le fichier du volume et son
  entrée de `/data/apk.json`
