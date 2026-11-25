# P0 — Validation du kit serveur (25/11/2026)

Test réel du `docker-compose.yml` et de `ops/retention.sql` sur une instance
jetable, avant tout déploiement. Images : `traccar/traccar:6.17.0-alpine` +
`timescale/timescaledb:2.32.1-pg17`.

## Ce qui a été vérifié, avec le résultat obtenu

| Vérification | Résultat |
|---|---|
| `docker compose config -q` | OK, services `tracker` / `tracker-db`, volumes `tracker-pgdata` / `tracker-logs` |
| Démarrage des deux conteneurs | `healthy` tous les deux |
| `GET /api/health` | `200 OK` |
| Conversion automatique en hypertables | `tc_positions`(2), `tc_events`(2), `tc_actions`(1) |
| Ingestion OsmAnd avec batterie | `200`, position stockée avec `batteryLevel: 42.0`, `charge: false` |
| Device inconnu (`registerUnknown=false`) | `400` — rejeté, pas créé |
| Géofence `CIRCLE (48.8566 2.3522, 200)` | `geofenceEnter` à l'entrée, `geofenceExit` à la sortie, `deviceMoving` |
| `ops/retention.sql` | policies créées (`tc_positions` 30 j, `tc_events` 90 j), **idempotent** au 2ᵉ passage |

Le pipeline complet est donc prouvé : position → stockage → hypertable →
événement de zone.

## Corrections apportées à la doc après le test

1. **Pas de compte `admin` / `admin`.** Le premier `POST /api/users` crée un
   administrateur et l'inscription se referme ensuite (`server.registration`
   vaut `false` par défaut, vérifié en base : `tc_servers.registration = f`).
   Donc : enregistrer l'admin avant d'exposer le domaine.
2. `tc_positions.attributes` est un `character varying` (et non `jsonb`) :
   `->>` échoue, il faut caster.
   ```sql
   SELECT attributes::jsonb->>'batteryLevel' AS batt
   FROM tc_positions ORDER BY fixtime DESC LIMIT 1;
   ```
3. `JAVA_OPTS` n'existe pas dans l'image : le heap se règle via
   `entrypoint:` + `command:` (vérifié dans `docker/Dockerfile.alpine`).

## Particularité de la machine de test (pas un défaut du compose)

Sur un hôte sans contrôleur de mémoire cgroup (`/sys/fs/cgroup/memory.max`
absent), le script d'init `timescaledb-tune` de l'image TimescaleDB panique
(`bytes must be at least 1 byte (got 0)`) au tout premier démarrage : le
conteneur redémarre une fois puis monte correctement. Sur une VM Docker
standard (cas Dokploy) le limiteur mémoire existe et le problème ne se produit
pas. Si ça arrive malgré tout, relancer simplement `up -d` : la base est
initialisée entre-temps.
