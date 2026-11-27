# KinWaypoint

KinWaypoint est une application Android de localisation familiale auto-hébergée. Un seul APK Kotlin et Jetpack Compose propose deux modes. Le mode **Parent** affiche la carte, l’historique et les alertes. Le mode **Enfant** transmet sa position au serveur familial et affiche l’état du suivi.

[APK de test sur GitHub](https://github.com/leguigou/KinWaypoint/releases/tag/test-latest) · Android 8.0 ou plus. Le dépôt GitHub privé nécessite un compte autorisé.

## Fonctionnalités disponibles

| Fonction | Ce que fait KinWaypoint |
| --- | --- |
| Carte et position en direct | Carte MapLibre, dernière position des membres, âge de la mesure, batterie et état de charge. Le mode parent reçoit les mises à jour par WebSocket avec actualisation périodique en secours. |
| Collecte Android | Suivi en arrière-plan avec notification visible, cadence adaptée au mouvement, file locale en cas de coupure réseau et reprise après redémarrage si Android autorise le suivi. |
| Historique et bilan | Carte et chronologie par jour, trajets et arrêts, distances et vitesses estimées, bilan hebdomadaire des pas et du temps passé dans les lieux déjà enregistrés. |
| Lieux et alertes | Repères circulaires avec nom, icône, couleur et rayon. Alertes d’arrivée et de départ par Traccar. La proximité du téléphone parent est un réglage distinct qui utilise sa position. |
| Sécurité familiale | SOS par appui prolongé, alertes de batterie faible, d’appareil injoignable et d’excès de vitesse. Certaines alertes et les commandes à distance dépendent de la configuration Firebase et des permissions Android. |
| Actions à distance | Demande de position et sonnerie du téléphone. L’écoute en direct est une option visible côté enfant, avec permission micro et relais audio configuré. |
| Accès et association | PIN local, biométrie facultative en mode Parent, association par QR code ou lien de configuration et compte enfant en lecture seule pour la carte partagée. |

La carte sait déjà afficher une **heure d’arrivée estimée** vers un lieu enregistré lorsque le déplacement se rapproche de celui-ci. Le calcul utilise la distance et la vitesse observée. Une estimation fondée sur le réseau routier fait partie des évolutions possibles.

### Garder la position du parent privée

Pour consulter la carte et l’historique des enfants, laisse **Partager ma position** désactivé sur ton téléphone. Si tu l’avais déjà activé, active d’abord **Mode fantôme** pour retirer l’accès du compte enfant à ton appareil, puis coupe **Partager ma position** pour arrêter les nouveaux envois. Les alertes d’arrivée et de départ autour d’un lieu fixe restent utilisables dans cette configuration.

## Installation sur les téléphones

1. Installe le même APK sur chaque téléphone. Une mise à jour Android doit être signée par la même clé que la version déjà installée.
2. Sur le téléphone parent, choisis **Parent**, définis le PIN et connecte-toi à Traccar. Si la découverte automatique est indisponible, renseigne les URL HTTPS de l’API et de l’ingestion dans les réglages avancés.
3. Ajoute les profils enfant depuis l’app parent. Sur chaque téléphone enfant, choisis **Enfant**, ouvre les réglages protégés par PIN et scanne le QR correspondant. Le compte Traccar utilisé par le parent doit pouvoir créer et associer les appareils.
4. Accorde les autorisations Android demandées, puis démarre le suivi. Vérifie sur les téléphones concernés qu’une position arrive écran éteint et remonte après une coupure réseau.

Le QR contient le secret d’envoi de l’appareil. Conserve-le comme un mot de passe. [Configuration à distance](docs/CONFIG-DISTANTE.md) · [Dépôt et mises à jour d’APK](docs/DEPOT-APK.md)

## Serveur

| Composant | Rôle |
| --- | --- |
| [`docker-compose.yml`](docker-compose.yml) | Traccar 6.17 pour l’ingestion OsmAnd, l’API, les événements et les géofences. TimescaleDB 2.32 sur PostgreSQL 17 pour le stockage. |
| [`ops/retention.sql`](ops/retention.sql) | Rétention de 30 jours pour les positions et de 90 jours pour les événements, après application du script. |
| [`server/config-api/`](server/config-api/) | Service séparé pour les liens de configuration, la découverte du serveur et le dépôt d’APK. |
| [`server/audio-relay/`](server/audio-relay/) | Relais WebSocket optionnel pour l’écoute en direct. [Guide de déploiement](docs/ECOUTE-DIRECTE.md) |

### Démarrer en local

Copie `.env.example` dans `.env` et définis un mot de passe de base fort. Avec le Compose du dépôt, les ports restent internes. Pour accéder à l’interface depuis la machine de développement, ajoute ce fichier `compose.local.yml`.

```yaml
services:
  tracker:
    ports:
      - "127.0.0.1:8082:8082"
      - "127.0.0.1:5055:5055"
```

```sh
docker compose -f docker-compose.yml -f compose.local.yml up -d
```

Ouvre `http://localhost:8082` pour créer le premier compte. L’accès depuis un téléphone se configure ensuite via les entrées HTTPS.

### Déployer avec Dokploy

1. Crée un service **Compose** depuis le dépôt avec `composePath: ./docker-compose.yml`. Définis `TRACKER_DB_PASSWORD` dans l’environnement du service.
2. Initialise le premier compte administrateur Traccar via un accès privé, avant de publier l’API. Le premier compte d’une instance neuve reçoit les droits administrateur.
3. Configure deux entrées HTTPS vers le service `tracker`. Le port **8082** sert l’interface, l’API REST et le WebSocket. Le port **5055** reçoit les positions OsmAnd. Dokploy injecte les routes Traefik. Le Compose expose les ports aux conteneurs et au proxy.
4. Après l’initialisation de Traccar, applique la rétention depuis la racine du dépôt.

```sh
docker compose exec -T tracker-db psql -v ON_ERROR_STOP=1 -U tracker -d tracker < ops/retention.sql
```

Garde l’administration Traccar derrière son authentification et limite le débit sur l’ingestion. Les identifiants OsmAnd doivent être longs et aléatoires. L’enregistrement automatique des appareils inconnus est désactivé dans le Compose. Les positions et événements sont hébergés sur ton serveur. Firebase peut acheminer les notifications et les commandes, Telegram est un canal serveur facultatif et le fond de carte utilise des tuiles distantes. Si tu déploies le dépôt d’APK, définis un code d’accès propre à ton instance.

### Compiler l’APK

Le workflow Android utilise le JDK 21. Depuis `android/`, lance les tests unitaires et la compilation.

```sh
./gradlew testReleaseUnitTest assembleRelease
```

Le workflow vérifie le certificat attendu et l’identifiant `fr.kinwaypoint.app` avant la publication. Pour les futures mises à jour, conserve la clé de signature et augmente le `versionCode`. [Workflow Android](.github/workflows/android.yml)

## Feuille de route par difficulté

Les priorités suivent l’usage familial actuel. Les fiches de trajet, les lieux fréquents et les cercles de Findmykids servent de référence fonctionnelle.

| Difficulté | À ajouter ou à renforcer | Point de départ |
| --- | --- | --- |
| **Facile** | Ajouter une fiche par lieu avec nombre de visites, dernier passage et temps cumulé sur 7 jours. | Réutiliser les arrêts de l’historique et le calcul du temps sur place du bilan hebdomadaire. |
| **Facile** | Rendre la fraîcheur explicite sur la carte avec les états « position récente », « dernière position ancienne » et « précision faible ». | Les horodatages et la précision remontent déjà du serveur. Ajouter des liens vers l’état de suivi et des permissions. |
| **Facile à moyenne · priorité** | Faire que la désactivation du partage parent arrête aussi la visibilité côté enfant et les règles de proximité associées. | Le service Android et les droits du compte lecteur ont déjà des opérations distinctes. Tester les anciens et nouveaux QR et la dernière position connue. |
| **Moyenne** | Rejouer un trajet avec lecture, pause et curseur horaire. | L’historique fournit les points horodatés et la carte sait déjà mettre un point en évidence. Garder les coupures GPS visibles. |
| **Moyenne** | Régler horaires et enfants concernés par zone, rechercher une adresse et suggérer les lieux fréquents à partir des arrêts. | Étendre l’écran des lieux et les associations Traccar. Pour les suggestions, confirmer la zone avant d’activer ses alertes. |
| **Complexe** | Améliorer l’heure d’arrivée avec un moteur d’itinéraires, le mode de déplacement et une gestion des positions périmées. | Faire évoluer l’estimation actuelle puis mesurer les écarts sur des trajets réels. |
| **Complexe** | Gérer plusieurs cercles et des invitations avec rôles et révocation. | Repenser les permissions serveur et la séparation des cercles. Le mode parent privé doit rester disponible. |

La messagerie familiale, les montres GPS et les contrôles d’applications sont des chantiers distincts. La fiabilité du suivi et des notifications sur les modèles Android réellement utilisés passe avant ces extensions.

[Rapports de trajet Findmykids](https://findmykids.org/blog/en/driving-reports-findmykids) · [Lieux fréquents](https://findmykids.org/blog/en/findmykids-places-section) · [Cercles familiaux](https://findmykids.org/blog/en/findmykids-app-update-family-circles)
