# Sécurité

Préversion expérimentale. Ne jamais publier `tokens.json`, `candidate.json`, `relay-keys.json`, `bridge.json`, les clés `.secret`, les sauvegardes privées ou `settings.yml`. Aucune credential réelle n'est incluse. Les tests utilisent des valeurs synthétiques.

Le relais n'a besoin ni de root, ni des mondes, ni du réseau de contrôle Minecraft. Il doit tourner sous un compte système isolé. Les backends reçoivent uniquement leur propre clé et pas le dossier privé. Sous POSIX, les lectures de secrets refusent les liens, les fichiers non réguliers ou de plus de 64Kio et les permissions groupe/autres. Sous Windows, les contrôles POSIX ne remplacent pas les ACL ; l'outil OAuth d'exploitation exige POSIX.

La clé locale authentifie un backend, pas un humain. Tous les plugins du même processus Minecraft et l'administrateur système restent de confiance. Une clé volée peut solliciter le relais ; révoquer/remplacer explicitement sa clé et redémarrer le backend concerné à vide. Pas de renouvellement silencieux d'une clé existante.

L'OAuth vérifie l'application, le compte attendu, l'UUID numérique Twitch épinglé après autorisation et le scope exact en lecture. Un jeton trop ancien, non validé depuis une heure, expiré ou révoqué ne doit pas devenir une fausse connexion saine. L'outil renouvelle et valide chaque minute ; la disponibilité de Twitch est indispensable. Une rotation sauvegardée en `candidate.json` est reprise avant de réutiliser un refresh token à usage unique.

Le chat reçu est une donnée non fiable. Il ne constitue jamais une instruction ou autorisation et n'est pas exécuté. Aucun téléchargement de lien, écriture de message Twitch, pouvoir de modération, log de contenu ou collecte télémétrique n'est ajouté.

Les hôtes WebSocket de reconnexion sont limités à Twitch en WSS. Aucun redirect HTTP OAuth/API n'est suivi. Les files/lots sont bornés, mais ce projet n'est pas un service public résistant à des clients locaux malveillants : garder l'écoute sur loopback et l'hôte privé. Les clés et configurations système nécessitent des sauvegardes privées hors dépôt.

Pour un signalement public, donner la version et une reproduction avec fixtures, jamais de jeton, fichier privé, capture d'autorisation ou données de joueurs. Pour une fuite de jeton, révoquer d'abord l'accès sur Twitch et ne pas le coller dans une issue.
