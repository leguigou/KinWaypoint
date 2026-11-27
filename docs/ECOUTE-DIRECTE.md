# Écoute en direct (visible)

Un parent peut écouter le micro du téléphone d'un enfant **en direct**.
L'enfant en est toujours informé : écran « Un parent t'écoute », notification
permanente, et point vert d'Android. **Rien n'est enregistré**, ni sur les
téléphones ni sur le serveur.

## Fonctionnement

1. Le parent choisit « Écouter en direct » dans le menu d'un enfant.
2. L'appli du parent ouvre une salle à usage unique sur le relais audio
   (`server/audio-relay`) avec un secret aléatoire.
3. Elle envoie à Traccar une commande `listen:<salle>:<secret>:<horodatage>`
   et réveille le téléphone de l'enfant par Firebase (comme « Faire sonner »).
4. Le téléphone de l'enfant reçoit la commande, ouvre l'écran « Un parent
   t'écoute », puis diffuse son micro (PCM 16 kHz mono, ~256 kbit/s).
5. Quand le parent raccroche, le relais coupe aussitôt le micro de l'enfant.
   Au plus tard après 10 minutes, tout s'arrête.

Une commande plus vieille que 2 minutes est ignorée : un téléphone resté hors
réseau ne se met pas à diffuser plus tard.

## Déploiement du relais (Dokploy)

Le service `audio-relay` est dans `docker-compose.yml` (image construite depuis
`server/audio-relay`, port interne 8765, 64 Mo de mémoire, aucun volume).

1. Redéployer le compose dans Dokploy.
2. Ajouter un **domaine** au service `audio-relay` :
   - hôte : `famille.exemple.fr` (ou un sous-domaine dédié),
   - chemin : `/audio`,
   - port du conteneur : `8765`,
   - HTTPS activé (le relais doit être joint en `wss://`).
   Le relais lit l'identifiant de salle dans le dernier segment du chemin : il
   fonctionne que Dokploy retire le préfixe `/audio` ou non.
3. Dans l'application `config-api` (dépôt d'APK), ajouter la variable :

   ```
   KW_AUDIO_RELAY_URL=wss://famille.exemple.fr/audio
   ```

   puis redémarrer. Vérification : `https://famille.exemple.fr/apk/server.json`
   doit contenir `"audioRelayUrl": "wss://famille.exemple.fr/audio"`.
4. Santé du relais : `https://famille.exemple.fr/audio/health` répond `OK`.

## Réglages sur le téléphone de l'enfant

Dans les réglages (cadenas + PIN) → **Écoute en direct** :

- interrupteur (activé par défaut) ;
- **Autoriser le micro** (obligatoire) ;
- **Afficher par-dessus les autres applis** (recommandé) : sans cette
  autorisation, Android interdit d'ouvrir l'écran depuis l'arrière-plan et
  l'enfant doit toucher une notification pour que l'écoute démarre.

## Sécurité

- Seul le téléphone du parent peut créer une salle ; l'enfant ne fait que la rejoindre.
- Secret de 128 bits par écoute, comparé en temps constant ; 20 salles et
  3 auditeurs par salle au maximum.
- Le relais ne journalise que les 6 premiers caractères des salles.

## Cadre légal (rappel, pas un avis juridique)

L'écoute est **visible** et sans enregistrement : l'enfant sait quand son micro
est partagé. N'enregistrez pas ce que vous entendez, et gardez à l'esprit que le
micro capte aussi les personnes autour de l'enfant. En cas de doute sur un usage
précis, demandez conseil à un professionnel du droit.
