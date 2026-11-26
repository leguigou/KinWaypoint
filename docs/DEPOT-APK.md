# Dépôt d'APK

Chaque APK produit s'archive sur `https://famille.deloffre.fr/config/apk/`,
du plus récent au plus ancien, avec sa version, sa taille et son empreinte
SHA-256.

L'index est protégé par une authentification HTTP basique ; les identifiants
sont dans le gestionnaire de mots de passe du projet (variables `KW_APK_USER` /
`KW_APK_PASS` côté serveur).

## Consultation

```bash
curl -u "$USER:$PASS" https://famille.deloffre.fr/config/apk/
```

`GET /apk/index.json` renvoie la même liste en JSON pour un script.

## Dépôt automatique

À chaque build, le workflow envoie l'APK au dépôt (étape « Déposer l'APK dans
le dépôt »), à condition que le secret `KW_HUB_TOKEN` existe. Sans ce secret,
l'étape s'ignore et le build reste vert.

Le fichier est nommé `kinwaypoint-<commit court>-v<version>.apk`, ce qui rend
l'origine traçable sans ouvrir l'APK.

## Dépôt manuel

```bash
curl -X POST --data-binary @app-release.apk \
  -H "X-Admin-Token: $KW_ADMIN_TOKEN" \
  -H "X-Filename: kinwaypoint-v1.0.0.apk" \
  -H "X-Version: 1.0.0 (20)" \
  -H "X-Note: première version stable" \
  https://famille.deloffre.fr/config/apk/upload
```

Un fichier déposé directement dans le volume (`/data/apk/`) apparaît aussi dans
l'index : le disque fait foi, l'index JSON n'est qu'un complément.

## Limites

- 200 Mo maximum par fichier
- pas de suppression depuis l'interface : retirer le fichier du volume et son
  entrée de `/data/apk.json`
