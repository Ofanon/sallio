# Confidentialité

Sallio n'a **aucun serveur**. Personne d'autre que l'élève et son
établissement n'a jamais accès à ses données.

## Ce que l'app enregistre, et où

| Donnée | Où | Protection |
| --- | --- | --- |
| Jeton d'appareil Pronote, identifiant Pronote, adresse du Pronote | téléphone, stockage privé de l'app | chiffré (AES-256-GCM, clé dans le Keystore Android, qui ne sort jamais du téléphone) |
| Emploi du temps et devoirs des deux prochaines semaines, notes de la période, actualités, messages, vie scolaire de l'année (absences, retards, punitions, dispenses), avatar choisi | téléphone, stockage privé de l'app | chiffré, même clé |
| Si la cantine est reliée : e-mail et **mot de passe Turboself**, numéro de carte de cantine, solde, réservations, derniers passages | téléphone, stockage privé de l'app | chiffré, même clé. Turboself ne donne pas de connexion durable : sans le mot de passe, l'app ne pourrait pas se mettre à jour seule. C'est le seul mot de passe que Sallio garde, et seulement si l'élève relie Turboself |
| Devoirs cochés en attente d'envoi, notes déjà vues | téléphone | clés techniques (date, matière, empreinte de la consigne) |
| Matières cochées dans le cartable (jours à venir seulement) | téléphone | jamais envoyées, pas même à Pronote |
| Notes perso sur un cours (« amener sa blouse »), gardées une semaine après le cours | téléphone | chiffrées (même clé), jamais envoyées |
| Heure de la dernière mise à jour, dernier message d'erreur | téléphone | aucune donnée personnelle |

Le **mot de passe** de l'élève n'est jamais vu par l'app : il est tapé dans la
page de l'ENT ou de Pronote, dans le navigateur intégré. Seul le jeton que
Pronote délivre à l'appareil est conservé.

Cookies, cache et formulaires du navigateur intégré sont effacés au début et à
la fin de chaque connexion.

## À qui l'app parle

- **Le Pronote de l'établissement**, et l'ENT pendant la connexion. C'est tout
  pour l'emploi du temps.
- **Turboself** (`api-rest-prod.incb.fr`, la société INCB), seulement si l'élève relie sa cantine : le mot de passe Turboself ne part que là, pour ouvrir une session.
- **Index Éducation** (l'éditeur de Pronote), seulement si l'élève touche
  « Chercher autour de moi » : la position, arrondie à environ 1 km, est
  envoyée au service de recherche d'établissements qu'utilise l'app officielle.
  Rien n'est envoyé sans ce geste.
- **PostHog** (`eu.i.posthog.com`, hébergé à Francfort), **seulement si l'élève
  a dit oui** à « Tu aides à tester Sallio ? ». La question est posée une fois,
  après la connexion ; la réponse se change dans Réglages › Compte et données
  (« Statistiques anonymes »). Voir « Statistiques de test » plus bas.
- **GitHub**, à chaque ouverture de l'app (ou sur demande dans les réglages), pour savoir
  s'il existe une nouvelle version. GitHub voit l'adresse IP du téléphone, et
  rien d'autre : aucune donnée de compte ni d'emploi du temps n'est envoyée.

## Ce que l'app ne fait pas

- Pas de publicité ni de bibliothèque tierce : l'APK ne contient que le code
  de l'app. Les statistiques de test passent par l'API web de PostHog,
  appelée par le code de Sallio, sans leur SDK.
- Pas de sauvegarde : la sauvegarde Google et le transfert vers un nouveau
  téléphone sont désactivés. Un nouveau téléphone demande une nouvelle connexion.
- Pas de HTTP en clair : toutes les connexions sont en HTTPS, et seules les
  autorités de certification livrées avec Android sont acceptées (un
  certificat installé par un tiers ne permet pas d'espionner le trafic).
- Les statistiques d'usage du navigateur intégré (WebView) sont désactivées.

## Statistiques de test

Pendant la phase de test, et seulement avec l'accord de l'élève, l'app envoie
des événements anonymes pour savoir quelles fonctions servent et ce qui plante.

**Ce qui part** : le nom de l'événement (onglet ouvert, fiche ouverte, devoir
coché, « J'ai vu », réservation de cantine, synchro, mise à jour, erreur,
plantage), quelques mots ou nombres fixes qui le décrivent (« depuis le
widget », « solde insuffisant », le nombre de widgets posés, oui/non pour les
alertes), les versions de Sallio et d'Android. Une erreur ne part que par son
type (`Unreachable`, `SocketTimeoutException`…). Un plantage part avec son type
et les lignes du code de Sallio qu'il a traversées, jamais son message.

**Ce qui ne part jamais** : nom, classe, établissement, adresse du Pronote,
cours, salles, professeurs, notes, devoirs, actualités, messages, vie
scolaire, cantine (solde, carte, réservations), jetons et mots de passe,
position.

**Pas de suivi** : chaque lancement de l'app tire un identifiant au hasard,
gardé nulle part ; deux lancements ne peuvent pas être reliés. Les événements
sont envoyés sans profil de personne (`$process_person_profile: false`) et
sans localisation (`$geoip_disable: true`), et le projet PostHog supprime
l'adresse IP à la réception.

**Sans clé, rien** : la clé du projet PostHog n'est pas dans le dépôt. Un APK
compilé sans elle n'envoie rien et ne pose pas la question.

**Rapport de test** : « Envoyer un rapport de test » (Compte et données) prépare
un texte que l'élève relit et envoie où il veut : versions, état de la
synchro, types des dernières erreurs. Rien ne part sans ce geste.

## Tout effacer

« Se déconnecter et tout effacer », dans l'app, supprime le jeton,
l'emploi du temps, le compte Turboself et la clé de chiffrement.
« Déconnecter Turboself » (Réglages, Cantine) n'efface que la cantine. Désinstaller l'app a le même effet.

## Ce qui reste visible

Les widgets affichent salles, matières et noms de professeurs sur l'écran
d'accueil : toute personne qui voit l'écran les voit aussi. Le widget
« Moyenne générale », si l'élève le pose, y montre aussi sa moyenne, celle de
la classe et sa dernière note.

Les alertes de changement sont des notifications Android ordinaires. Sur
l'écran verrouillé, une nouvelle note, un message ou un événement de vie
scolaire n'affichent qu'un titre neutre (« Nouvelle note en Maths »,
« Nouveau message », « Vie scolaire ») : la note, le texte et le motif
n'apparaissent qu'une fois le téléphone déverrouillé. Les changements
d'emploi du temps, eux, s'affichent en entier. Chaque type d'alerte se coupe
dans les réglages Android de l'app.
