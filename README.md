# TwitchChatRelay

Afficher les messages Twitch dans Minecraft, pour tous les joueurs d'un serveur ou uniquement pour des UUID choisis. Projet autonome de Gpxkiller, licence MIT, préversion **0.1.2**. Messages jaunes avec balise `[Twitch]` violette.

Chaque message commence par **[Twitch] en violet**. Le texte est littéral : pas de commande, lien cliquable ou formatage injecté. Aucun compte Twitch, destinataire ou administrateur n'est préconfiguré. Désactivé par défaut.

## Architecture

- Plugin Paper **26.2+ / Java 25+**, sans autre plugin requis. Cette première version ne revendique pas la compatibilité avec Paper 1.21.x, Spigot ou Folia.
- Relais Java central séparé, sur le **même hôte**, écoute exclusivement `127.0.0.1:28765`. Une connexion EventSub partagée, maximum huit chaînes différentes et 64 backends autorisés.
- Le relais seul lit les jetons Twitch. Chaque backend dispose d'une clé locale distincte ; elle ne donne aucun accès à la console ou à l'OAuth.
- Outil opérateur Python **3.10+ sur Linux/POSIX** : autorisation officielle Device Code et renouvellement. Pas de page publique, de callback externe ou de service cloud du projet.
- Bibliothèque Gson embarquée et isolée dans le JAR. API Paper fournie par le serveur. Aucun plugin GPX requis.

Ce n'est ni un outil de modération Twitch ni un relais Minecraft vers Twitch. Les messages perdus pendant une coupure ne sont pas rejoués. Le premier poll et un changement d'audience ne rejouent pas l'historique. Rétention mémoire bornée à deux minutes/2000 messages, lots de 50, déduplication bornée. Les audiences sont locales au backend, jamais globales au proxy.

## Installation manuelle

Ne pas utiliser les jetons, comptes ou fichiers d'un autre serveur. Créer sa propre application Twitch de type **Public** puis autoriser son propre compte avec le seul scope `user:read:chat`. Les API et conditions Twitch restent applicables : [Device Code](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow), [EventSub WebSocket](https://dev.twitch.tv/docs/eventsub/handling-websocket-events), [réception du chat](https://dev.twitch.tv/docs/chat/send-receive-messages/).

1. Construire avec `mvn -B clean package` ou télécharger le JAR de la release. Python n'a aucune dépendance externe.
2. Sur l'hôte Linux, créer un compte système dédié au relais, sans accès aux mondes, et `/var/lib/twitch-chat-relay` privé en **0700**. Copier `bridge.example.json` vers `bridge.json` dans ce dossier, en **0600**. Renseigner le `clientId` de sa propre application et le `login` Twitch attendu en minuscules. Garder `enabled: false` pendant la préparation. Ne jamais publier ce dossier.
3. Exécuter sous ce compte :

```sh
python3 /opt/twitch-chat-relay/tools/auth.py --directory /var/lib/twitch-chat-relay authorize
```

Ouvrir soi-même `https://www.twitch.tv/activate` et saisir le code temporaire affiché. Vérifier l'application et le compte avant validation. Le mot de passe et les jetons ne sont jamais demandés dans Minecraft. Les jetons sont stockés uniquement sur l'hôte, en 0600. Le renouvellement conserve aussi une rotation en attente si la validation réseau échoue.

4. Créer une clé par serveur, par exemple :

```sh
python3 /opt/twitch-chat-relay/tools/auth.py --directory /var/lib/twitch-chat-relay --mode survival key
```

Cela crée `relay-keys.json` et `survival.secret` privés sans afficher la clé. Copier ce dernier, **sans modifier son format JSON**, dans `plugins/TwitchChatRelay/bridge.secret` sur le backend et attribuer sa lecture uniquement au compte système de ce backend (0600). Le backend ne doit jamais pouvoir lire les jetons ou la clé des autres serveurs. Les backends doivent être fiables : une clé compromise permet de demander des chaînes au relais, dans les limites globales.

5. Copier le JAR dans `plugins/` du backend. Au premier démarrage, sa configuration reste désactivée. Configurer `mode-id: survival`, correspondant exactement à la clé créée, puis `enabled: true`. Attribuer explicitement la permission **`twitchchatrelay.admin`** aux administrateurs voulus via votre gestionnaire de permissions ; elle n'est accordée automatiquement à personne, même OP. La console peut utiliser les commandes.
6. Mettre `enabled: true` dans le `bridge.json` privé. Démarrer le relais avec :

```sh
java -cp /opt/twitch-chat-relay/twitch-chat-relay-0.1.2.jar fr.gpxkiller.twitch.TwitchBridge /var/lib/twitch-chat-relay
```

7. Prévoir le renouvellement **chaque minute**, sous le même compte : `python3 .../tools/auth.py --directory /var/lib/twitch-chat-relay refresh`. Des exemples systemd sont fournis dans `deploy/`. Adapter les chemins puis activer les unités avec les outils habituels de l'hôte. Aucun installateur n'agit automatiquement.
8. Redémarrer officiellement le backend quand il est vide pour activer le plugin ; aucun `/reload` ni déconnexion forcée. Pas besoin de modifier le proxy. Tester d'abord sur un serveur de laboratoire.

Sous Windows, les tests de politique sont possibles mais la première version de l'outil OAuth d'exploitation est volontairement POSIX : il impose des fichiers privés et un verrou local. Ne pas exposer le relais par un proxy public, port-forwarding ou Docker ; le transport local est du HTTP protégé par clé, pas un protocole d'administration distant chiffré.

## Commandes Minecraft

- `/twitch aide` et `/twitch statut` : aide et chaînes/audiences locales.
- `/twitch activer examplechannel tous` : afficher cette chaîne pour tout le backend.
- `/twitch activer examplechannel Pseudo` : audience privée, ajouter ce joueur. Si l'audience était publique, elle devient privée.
- `/twitch masquer examplechannel Pseudo` : retirer ce joueur d'une audience privée ; le dernier retrait désactive la chaîne.
- `/twitch desactiver examplechannel` : désactiver cette chaîne sur ce backend.

Pseudos et UUID des joueurs hors ligne déjà connus, autocomplétion et sélecteurs Minecraft natifs `@a`, `@p`, `@r`, `@s` acceptés. Les sélecteurs ne portent que sur ce backend et les joueurs invisibles à l'expéditeur sont exclus. Les audiences privées utilisent les UUID, pas les surnoms ou déguisements. Les réglages persistent dans `settings.yml` ; ne pas diffuser ce fichier contenant des identités.

## Validation et limites

Voir [VALIDATION.md](VALIDATION.md) et [SECURITY.md](SECURITY.md). Tests OAuth avec réponses inventées, HTTP réel sur loopback, reconnexion WebSocket instrumentée et politique d'audiences testés séparément. Aucun compte Twitch réel utilisé pour tester cette extraction, aucune preuve de partie humaine Java/Bedrock annoncée. Le texte est envoyé par le serveur et peut être traduit par Geyser, mais aucun test graphique Bedrock n'est revendiqué.

## English

MIT-licensed standalone Twitch-to-Paper chat relay. Separate local Java EventSub bridge, per-backend keys and UUID audiences, read-only public-client Device OAuth, Python operator renewal, literal purple `[Twitch]` prefix. Disabled by default; no bundled account, token, server configuration, telemetry or hosted service. Experimental, same-host only. Own Twitch application and explicit permissions required.
