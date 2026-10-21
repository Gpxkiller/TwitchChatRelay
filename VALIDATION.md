# Validation 0.1.0

Extraction autonome, sans données de production, sans accès au VPS et sans nouveau compte Twitch autorisé.

Trois revues distinctes :

1. Confidentialité/dépendances : retrait des comptes, UUID, domaines, client OAuth et modes spécifiques ; aucune dépendance GPX, aucune audience ni permission préattribuée, configuration désactivée.
2. Transport/cycle : HTTP loopback avec clés propres, refus des requêtes inconnues/surdimensionnées, reconnexion WSS bornée à Twitch, révocation, déduplication, lots, absence de replay au premier poll, changement d'époque. OAuth Device et rotation conservée avant validation réseau.
3. Livraison/audience : UUID immuables, texte littéral, préfixe violet, permission explicite, état local, générations de réglages invalidant les réponses en vol, clés privées et Gson embarquée/isolée.

Les tests Java exercent de vraies sockets HTTP loopback mais les notifications WebSocket de cycle sont instrumentées. Les tests Python simulent Twitch, le temps, les coupures pendant validation, les mauvais comptes/scopes/applications, le refresh et le slow_down. Aucun appel OAuth réel ni chat d'un compte privé n'est effectué pendant ces tests.

Les contrôles POSIX sont rejoués en CI Linux ; les tests qui ne s'appliquent pas à Windows sont explicitement ignorés. La CI doit être verte avant publication du JAR. Une préversion ne prouve ni compatibilité universelle ni test humain en jeu. Les différences historiques de protocole et les limites Twitch restent celles de l'API officielle.

Contrôle local du JAR autonome : relais réellement lancé sans dépendance Paper ni jeton, 50 requêtes HTTP simultanées bornées entre deux identités de backend distinctes, puis arrêt du processus de test. Paper 26.3 isolé charge le plugin sans dépendance GPX. Un essai console a reproduit un filtre trop strict dans le descripteur de commande ; suppression de ce filtre redondant, le contrôle effectif restant dans l'exécuteur (console ou permission explicite). Régression dédiée au descripteur ajoutée. Pas d'autorisation Twitch réelle utilisée pour cette extraction.

Le 21 octobre 2026, la [CI Linux initiale](https://github.com/Gpxkiller/TwitchChatRelay/actions/runs/39562023016) valide 37 tests Java, 17 tests Python (permissions POSIX incluses), puis les 50 polls HTTP du JAR empaqueté. Sur Paper 26.3 de laboratoire : chargement activé, statut indisponible honnête sans relais, activation/désactivation persistante d'une chaîne par console et refus d'un sélecteur sans joueur vérifiés, arrêt console officiel. Aucun joueur ni compte réel utilisé. Sous Windows, un test Java et un test Python POSIX sont ignorés explicitement.
