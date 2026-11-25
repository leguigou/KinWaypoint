# Tracker Famille

Suivi de localisation familial auto-hébergé. Deux applications Android natives
(un seul APK, deux modes : Parent / Enfant) adossées à un serveur **Traccar**
qui fait office de moteur de collecte, de stockage et de géorepérage.

> Objectif : aucune dépendance à un service tiers, données hébergées en Europe,
> historique limité dans le temps par construction.

## Architecture

```
[APK mode Enfant]  ──HTTPS POST (protocole OsmAnd)──┐
[APK mode Enfant]  ─────────────────────────────────┤
                                                    ▼
                                        ┌───────────────────────┐
                                        │  Traccar (Traefik)    │
                                        │  :5055 ingestion      │
                                        │  :8082 API + WS       │
                                        └───────────┬───────────┘
                                                    │ JDBC
                                        ┌───────────▼───────────┐
                                        │ TimescaleDB (PG 17)   │
                                        │ hypertables + rétention│
                                        └───────────────────────┘
                                                    ▲
[APK mode Parent] ──REST + WebSocket───────────────┘
                  └─ push FCM (géofences, SOS) ─── Traccar notificator
```

## Pourquoi Traccar et pas un backend maison

Traccar apporte déjà, sans code : l'ingestion GPS, la table de positions, les
géofences horodatées, les événements, les notifications (dont **Firebase direct
vers une app maison**), les rapports de trajets et le partage de device.
On n'écrit que ce qui manque : les applis Android et une fine couche métier.

## Composants

| Composant | Rôle |
|---|---|
| `docker-compose.yml` | Traccar 6.17 + TimescaleDB 2.32 (PG 17), volumes nommés fixes |
| `ops/retention.sql` | Politique de rétention Timescale (positions 30 j, événements 90 j) |
| `android/` (à venir) | APK Kotlin/Compose, deux modes, MapLibre + OSM |
| `family-api/` (phase 3) | Seuil batterie, partage temporaire, provisioning QR, fan-out SOS |

## Démarrage local

```bash
cp .env.example .env      # puis renseigner un mot de passe fort
docker compose up -d
docker compose logs -f tracker
```

Interface web : http://localhost:8082.

**Traccar 6 n'a plus de compte `admin` / `admin`.** Le premier compte créé devient
administrateur, et l'inscription publique se referme automatiquement ensuite
(`server.registration = false` par défaut). Deux conséquences :

- créer le compte admin **immédiatement** après le premier démarrage, avant
  d'exposer le domaine — sinon le premier visiteur devient administrateur ;
- pour ajouter un second parent plus tard, réactiver temporairement
  `server.registration` depuis l'interface, créer le compte, puis le refermer.

En ligne de commande, sur une instance neuve :

```bash
curl -s -X POST http://localhost:8082/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"Parent","email":"parent@exemple.fr","password":"<mot de passe fort>"}'
# → administrator: true sur le premier compte uniquement
```

Puis appliquer la rétention (une seule fois, après le premier démarrage) :

```bash
docker exec -i "$(docker compose ps -q tracker-db)" \
  psql -U tracker -d tracker < ops/retention.sql
```

## Déploiement Dokploy

Service de type **Compose**, mode Git, `composePath: ./docker-compose.yml`.
Deux domaines pointent sur le *même* service, sur deux ports différents :

| Domaine | Service | Port |
|---|---|---|
| `track.<domaine>` | `tracker` | 5055 (ingestion OsmAnd) |
| `api.<domaine>` | `tracker` | 8082 (web + API + WS) |

Ne pas mettre de labels Traefik dans le YAML : Dokploy les injecte.

## Provisionner un appareil enfant

1. Créer le device dans Traccar (nom = prénom, identifiant **long et aléatoire**).
2. Dans l'app enfant, coller l'identifiant ou scanner le QR code généré.
3. Vérifier la réception : `SELECT * FROM tc_positions ORDER BY fixtime DESC LIMIT 1;`

L'identifiant de device **est** le secret d'authentification : le protocole
OsmAnd n'a pas d'authentification. Un identifiant court ou devinable permet à
n'importe qui de publier une position sur l'appareil d'un enfant.

## Règles de sécurité

- Ingestion (5055) : seule route publique d'écriture, HTTPS terminé par Traefik,
  rate limit à poser côté Traefik.
- Interface/API (8082) : jamais exposée sans authentification.
- `database.registerUnknown=false` : un identifiant inconnu est rejeté, pas créé.
- Rotation du mot de passe base : `docker compose down`, mise à jour de `.env`
  et de `tracker_pgdata` via `ALTER USER tracker PASSWORD …`, puis `up -d`.
- Aucune donnée personnelle dans ce dépôt (ni nom réel, ni URL réelle).
