# Dépôt d'APK

Chaque APK produit s'archive sur `https://famille.deloffre.fr/apk/`, du plus
récent au plus ancien, avec sa version, sa taille et son empreinte SHA-256.

L'accès se fait par un **code** saisi dans un champ unique (`KW_APK_CODE`,
`azerty` par défaut). Une fois validé, le serveur dépose un cookie signé
(HMAC, 30 jours, `HttpOnly`, `Secure`) : plus rien à retaper ensuite.
`/apk/logout` efface le cookie.

## Consultation

```bash
# le code peut aussi passer en query, pratique pour un script
curl "https://famille.deloffre.fr/apk/index.json?code=azerty"
```

`GET /apk/index.json` renvoie la liste en JSON. `GET /apk/<fichier>` sert le
fichier avec le bon type MIME.

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
  https://famille.deloffre.fr/apk/upload
```

Un fichier déposé directement dans le volume (`/data/apk/`) apparaît aussi dans
l'index : le disque fait foi, l'index JSON n'est qu'un complément.

## Limites

- 200 Mo maximum par fichier
- pas de suppression depuis l'interface : retirer le fichier du volume et son
  entrée de `/data/apk.json`
