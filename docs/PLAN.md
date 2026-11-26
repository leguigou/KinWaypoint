# Plan — KinWaypoint

Décisions validées avec Guillaume (25/11/2026) :

| Sujet | Décision |
|---|---|
| Socle serveur | Traccar 6.17 (moteur) + TimescaleDB 2.32 / PG 17 |
| Apps Android | **Un seul APK**, deux modes (Parent / Enfant), Kotlin + Compose |
| Distribution | **Sideload** (lien privé). Pas de Play Store : évite la politique Families et la review |
| Cartographie | MapLibre + OSM (pas de clé API, pas de quota, offline possible) |
| Couche `family-api` | **Phase 3**, après validation du P0 |

## Couverture fonctionnelle

Chaque demande est rattachée au composant qui la porte. « Traccar » = fourni par
le serveur, aucune ligne de code de notre côté.

| Fonction | Portée par |
|---|---|
| Carte familiale, dernière position de chacun | App parent — `GET /api/positions?deviceId=…` |
| Actualisation automatique | App parent — WebSocket `/api/socket` (push serveur) |
| Niveau de batterie | Position OsmAnd, attribut `batt` (+ `charge`) |
| Historique limité dans le temps | Rapport `route` + rétention Timescale 30 j |
| Zones maison / collège / lycée | Géofences Traccar, par device, avec calendrier horaire |
| Notification entrée / sortie de zone | Événements `geofenceEnter` / `geofenceExit` → notificator Firebase |
| Bouton SOS | App enfant : `alarm=SOS` dans la position → événement `alarm` → push prioritaire |
| Alerte batterie faible | `family-api` (seuil par enfant, hystérésis 1 notif/h) |
| Dernière connexion | `device.lastUpdate` + statut online/offline |
| Partage de position temporaire | `family-api` : JWT signé, expiration 4 h, page web lecture seule |

Trois choses seulement ne sont pas couvertes par Traccar : seuil de batterie,
partage expirant, et provisioning par QR code. D'où la `family-api`.

## Phases

### P0 — Socle serveur

- [x] Choix de la stack et du dimensionnement
- [x] `docker-compose.yml` Dokploy (volumes nommés, limites mémoire, pas de labels Traefik)
- [x] `ops/retention.sql` (idempotent)
- [x] **Pipeline validé en local** sur une instance jetable — voir `docs/P0-validation.md`
- [x] Service Compose créé sur Dokploy + 2 domaines (5055 et 8082), HTTPS Let's Encrypt
- [x] Premier démarrage, création du compte admin (premier compte = administrateur)
- [x] Rétention appliquée et vérifiée dans `timescaledb_information.jobs`
- [ ] Re-test de bout en bout avec l'**APK Traccar Client officiel** : position reçue,
      batterie reçue, géofence déclenchée (nécessite un téléphone réel)

Critère de sortie : une position arrive en base et un événement de géofence
produit une notification. Tant que ce n'est pas vrai, on n'écrit pas de Kotlin.

### P1 — App enfant

- Fork de `traccar/traccar-client-android` (déjà en Kotlin) ou service GPS écrit
  à la main, au choix selon l'état du fork
- Foreground service `location`, démarrage au boot, écran verrouillé
- Fréquence adaptative : 1 min en mouvement, 10-15 min à l'arrêt, réveil par
  geofence natif Android
- Provisioning par QR code (identifiant + URL serveur), pas de formulaire
- Bouton SOS avec appui long 3 s, `alarm=SOS`, acquittement visuel
- Écran minimal : « suivi actif », état du dernier envoi, batterie

État au 26/11/2026 (v0.2) : P1 livré sauf fréquence adaptative et QR code ;
P2 démarré (liste des membres, batterie, SOS 24 h, PIN parent) sans carte
intégrée ni historique. Détail dans `android/README.md`.

### P2 — App parent

- MapLibre, marqueurs famille, dernière position + horodatage
- WebSocket `/api/socket`, repli sur polling 30 s
- Batterie dans le marqueur, code couleur < 20 %
- Historique par jour, slider de fenêtre (aujourd'hui / 7 j)
- Écran géofences : création par recherche d'adresse, assignation par enfant, horaires

### P3 — family-api

- Seuil batterie par enfant + anti-spam
- Partage temporaire (JWT 4 h, révocation, page web sans compte)
- Fan-out SOS vers tous les parents + notification sonore prioritaire
- Provisioning QR signé

## Pièges identifiés (à ne pas réapprendre à ses dépens)

1. **L'OS Android, pas le serveur.** Le risque n°1 c'est le fabricant qui tue le
   process écran éteint. Il faut : foreground service type `location`, permission
   « Autoriser tout le temps », exemption d'optimisation batterie, et un test réel
   sur chaque modèle d'appareil avant de crier victoire.
2. **Batterie.** GPS continu = 10-15 %/h. Sans fréquence adaptative et sans
   réveil par geofence, l'appareil est mort à 16 h et l'enfant la désinstalle.
3. **L'identifiant de device est le mot de passe.** OsmAnd n'authentifie rien.
   Identifiant long et aléatoire, `registerUnknown` désactivé, rate limit Traefik.
4. **Volumes Docker préfixés.** Sans `name:` fixe, Dokploy préfixe le volume et
   on perd la base au redéploiement suivant.
5. **Alias `db` sur dokploy-network.** Déjà pris par d'autres projets : d'où
   `tracker-db`. Sinon on écrit dans la base d'un autre service.
6. **`JAVA_OPTS` inutile.** L'image Traccar n'a pas d'entrypoint shell :
   le heap se règle en surchargeant `entrypoint` + `command`.
7. **RGPD.** Localisation de mineurs = données sensibles. Hébergement UE, accès
   restreint, rétention courte et documentée, pas de SDK analytique tiers.
8. **Premier compte = administrateur.** Traccar 6 n'a plus de `admin`/`admin` :
   `POST /api/users` sur une base vide crée un admin, et l'inscription se referme
   ensuite. Une instance exposée avant l'enregistrement est à prendre.
9. **`tc_positions.attributes` est un `varchar`**, pas du `jsonb` :
   `attributes::jsonb->>'batteryLevel'` pour lire la batterie en SQL.
