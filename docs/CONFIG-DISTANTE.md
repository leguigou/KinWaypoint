# Configuration à distance (code de configuration)

But : éviter la saisie manuelle. On colle dans l'appli un **lien HTTPS
contenant un jeton**, ou on scanne un **QR code qui contient ce lien**.
L'appli appelle le lien (`GET`) et reçoit un **JSON** qui la remplit.

Où l'utiliser dans l'appli :

| Écran | Bouton | Effet |
|---|---|---|
| Premier lancement | « J'ai un code de configuration » | Choisit le mode (`mode`), fait créer le PIN, puis remplit les réglages |
| Enfant → Réglages parent (PIN) | « J'ai un code de configuration », ou « Scanner le QR code » | Remplit serveur, identifiant, intervalle |
| Parent → Connexion | « J'ai un code de configuration » | Remplit les adresses et l'e-mail ; **le mot de passe reste à saisir** |

Dans tous les cas, l'appli **ne démarre pas le suivi toute seule** : on vérifie
puis on appuie sur « Enregistrer et démarrer ».

## Le lien

```
https://config.exemple.fr/kw/7f3c9a1e-…?token=Qm9uam91ciE…
```

N'importe quelle URL `https://` convient : le format du jeton (dans le chemin
ou en paramètre) ne regarde que le serveur. L'appli envoie :

```
GET <le lien>
Accept: application/json
```

## Réponse attendue

`200 OK`, `Content-Type: application/json`, 64 Ko maximum.

### Téléphone enfant

```json
{
  "version": 1,
  "mode": "child",
  "label": "Téléphone de Léa",
  "expiresAt": "2026-12-03T18:00:00Z",
  "child": {
    "serverUrl": "https://track.exemple.fr",
    "deviceId": "kw7h3kq9x2mzp4rt8vbn6cye5wd3",
    "intervalSeconds": 60
  }
}
```

### Téléphone parent

```json
{
  "version": 1,
  "mode": "parent",
  "label": "Téléphone de Guillaume",
  "parent": {
    "apiUrl": "https://api.exemple.fr",
    "trackUrl": "https://track.exemple.fr",
    "email": "parent@exemple.fr"
  }
}
```

### Description des champs

| Champ | Obligatoire | Description |
|---|---|---|
| `version` | non (défaut `1`) | Version du format. Une version supérieure à celle connue de l'appli est refusée (« mets l'appli à jour ») |
| `mode` | non | `"child"` ou `"parent"`. Au premier lancement, il choisit le mode. Absent : déduit de la section présente (ou l'utilisateur choisit si les deux sont là) |
| `label` | non | Texte affiché à la réception, ex. « Téléphone de Léa » |
| `expiresAt` | non | Date ISO 8601. Passée : le code est refusé (« a expiré ») |
| `child.serverUrl` | oui (section enfant) | Adresse d'envoi des positions (port 5055 derrière Traefik), **https obligatoire** |
| `child.deviceId` | oui (section enfant) | Identifiant de l'appareil dans Traccar, **12 caractères minimum** (c'est le secret d'envoi) |
| `child.intervalSeconds` | non | Intervalle en mouvement, ramené entre 15 et 3600 |
| `parent.apiUrl` | oui (section parent) | Adresse de l'interface / API Traccar (port 8082), **https obligatoire** |
| `parent.trackUrl` | non | Adresse d'envoi, utilisée dans les QR codes « Ajouter un enfant » |
| `parent.email` | non | Pré-remplit l'e-mail de connexion |

Il faut au moins une section, `child` ou `parent`. Les champs inconnus sont
ignorés : on peut en ajouter côté serveur sans casser l'appli.

### Erreurs

| Réponse du serveur | Message affiché |
|---|---|
| `401`, `403`, `404`, `410` | « Code invalide, expiré ou déjà utilisé » |
| autre code d'erreur | « Serveur de configuration en erreur (HTTP xxx) » |
| JSON invalide, champ manquant, `http://` | message précis (ex. « L'adresse « child.serverUrl » doit être en https:// ») |

## Sécurité côté serveur (recommandations)

La réponse contient `deviceId`, **le secret qui permet d'envoyer des positions
au nom de l'enfant**. Le lien doit donc être traité comme un mot de passe.

- **HTTPS uniquement** : l'appli refuse `http://` et les redirections vers `http://`.
- **Jeton aléatoire long** (≥ 128 bits), **à usage unique** : invalider le lien
  après le premier `200` (répondre `410` ensuite).
- **Durée de vie courte** (quelques heures), en plus de `expiresAt`.
- **Jamais de mot de passe** dans la réponse. Le parent saisit le sien à la
  connexion ; l'appli n'en garde qu'un jeton Traccar.
- Journaliser les appels (date, IP) pour repérer un lien qui aurait fuité.
- Ne pas envoyer le lien par un canal public, ne pas le photographier.

## Exemple minimal de serveur (test)

Un fichier statique servi derrière un chemin imprévisible suffit pour un test,
mais il **n'est ni à usage unique ni expirant** : à supprimer après usage.

```bash
TOKEN=$(openssl rand -hex 24)
mkdir -p "/srv/kw-config/$TOKEN"
cat > "/srv/kw-config/$TOKEN/config.json" <<'JSON'
{ "version": 1, "mode": "child", "label": "Téléphone de test",
  "child": { "serverUrl": "https://track.exemple.fr", "deviceId": "kw…", "intervalSeconds": 60 } }
JSON
echo "https://config.exemple.fr/$TOKEN/config.json"
```

Pour la production, une petite route dans la future `family-api` (phase 3)
pourra générer le jeton, créer l'appareil Traccar et invalider le lien au
premier usage.

## Implémentation du serveur (déployée)

Le serveur décrit ci-dessus est implémenté dans `server/config-api/` et tourne
sur `https://config.deloffre.fr` (image `kinwaypoint-config:1.0`, volume
`kwconfig_data` pour la persistance, 128 Mo de RAM max).

| Route | Rôle |
|---|---|
| `GET /kw/<token>` | renvoie la configuration, puis marque le jeton utilisé (`410` au 2ᵉ appel) |
| `POST /api/codes` | création d'un code, protégée par l'en-tête `X-Admin-Token` |
| `GET /health` | sonde |

Créer un code :

```bash
curl -s -X POST https://config.deloffre.fr/api/codes \
  -H "X-Admin-Token: $KW_ADMIN_TOKEN" -H 'content-type: application/json' \
  -d '{"mode":"child","label":"Téléphone de Léa",
       "child":{"serverUrl":"https://ingest.deloffre.fr",
                "deviceId":"<identifiant Traccar>","intervalSeconds":60}}'
```

Le jeton fait 256 bits (`secrets.token_urlsafe(32)`), la durée de vie est de
48 h par défaut (`KW_TTL_HOURS`) et chaque code est à usage unique.
