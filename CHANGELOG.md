# Changements

## 0.1.2 — version recommandée

- Auteur et contenu des messages en jaune ; balise `[Twitch]` violette conservée. Texte toujours littéral, sans liens cliquables ni commandes injectées.
- Test de régression des couleurs ajouté. Aucun changement d’audience ou d’autorisation.

## 0.1.1

- Dernier contrôle contre la documentation officielle Twitch : le champ `scopes` est aussi transmis lors de l'échange du Device Code contre des jetons. Test du contenu réel de cette requête ajouté. Utiliser cette version plutôt que 0.1.0 pour une nouvelle autorisation.
- La 0.1.0 a validé les transports et politiques isolées mais pas une nouvelle autorisation Twitch réelle. Aucun compte réel n'a été utilisé pour corriger ce champ.

## 0.1.0

- Première extraction autonome : plugin Paper 26.2+/Java25 et relais EventSub local partagé.
- Audiences publiques ou privées par UUID, commandes et autocomplétion, données hors ligne connues et sélecteurs natifs.
- Préfixe `[Twitch]` violet, texte littéral, files bornées et déduplication, contrôle des anciennes connexions/révocations.
- Outil Device OAuth en lecture seule avec validation d'identité/application/scope et récupération des rotations interrompues.
- Aucun compte, permission admin, jeton, audience ou serveur de production préconfiguré ; désactivé par défaut.
- Licence MIT, dépendance Gson embarquée/isolée et installation manuelle seulement. Préversion expérimentale.
