# Confidentialité

Sallio n'a **aucun serveur**. Personne d'autre que l'élève et son
établissement n'a jamais accès à ses données.

## Ce que l'app enregistre, et où

| Donnée | Où | Protection |
| --- | --- | --- |
| Jeton d'appareil Pronote, identifiant Pronote, adresse du Pronote | téléphone, stockage privé de l'app | chiffré (AES-256-GCM, clé dans le Keystore Android, qui ne sort jamais du téléphone) |
| Emploi du temps et devoirs des deux prochaines semaines, notes de la période, actualités, messages, vie scolaire de l'année (absences, retards, punitions, dispenses), avatar choisi | téléphone, stockage privé de l'app | chiffré, même clé |
| Devoirs cochés en attente d'envoi, notes déjà vues | téléphone | clés techniques (date, matière, empreinte de la consigne) |
| Heure de la dernière mise à jour, dernier message d'erreur | téléphone | aucune donnée personnelle |

Le **mot de passe** de l'élève n'est jamais vu par l'app : il est tapé dans la
page de l'ENT ou de Pronote, dans le navigateur intégré. Seul le jeton que
Pronote délivre à l'appareil est conservé.

Cookies, cache et formulaires du navigateur intégré sont effacés au début et à
la fin de chaque connexion.

## À qui l'app parle

- **Le Pronote de l'établissement**, et l'ENT pendant la connexion. C'est tout
  pour l'emploi du temps.
- **Index Éducation** (l'éditeur de Pronote), seulement si l'élève touche
  « Chercher autour de moi » : la position, arrondie à environ 1 km, est
  envoyée au service de recherche d'établissements qu'utilise l'app officielle.
  Rien n'est envoyé sans ce geste.
- **GitHub**, au plus une fois par heure quand l'app est ouverte (ou sur demande dans les réglages), pour savoir
  s'il existe une nouvelle version. GitHub voit l'adresse IP du téléphone, et
  rien d'autre : aucune donnée de compte ni d'emploi du temps n'est envoyée.

## Ce que l'app ne fait pas

- Pas de publicité, de statistiques, de rapport de plantage, de bibliothèque
  tierce : l'APK ne contient que le code de l'app.
- Pas de sauvegarde : la sauvegarde Google et le transfert vers un nouveau
  téléphone sont désactivés. Un nouveau téléphone demande une nouvelle connexion.
- Pas de HTTP en clair : toutes les connexions sont en HTTPS, et seules les
  autorités de certification livrées avec Android sont acceptées (un
  certificat installé par un tiers ne permet pas d'espionner le trafic).
- Les statistiques d'usage du navigateur intégré (WebView) sont désactivées.

## Tout effacer

« Se déconnecter et tout effacer », dans l'app, supprime le jeton,
l'emploi du temps et la clé de chiffrement. Désinstaller l'app a le même effet.

## Ce qui reste visible

Les widgets affichent salles, matières et noms de professeurs sur l'écran
d'accueil : toute personne qui voit l'écran les voit aussi.

Les alertes de changement sont des notifications Android ordinaires. Sur
l'écran verrouillé, une nouvelle note, un message ou un événement de vie
scolaire n'affichent qu'un titre neutre (« Nouvelle note en Maths »,
« Nouveau message », « Vie scolaire ») : la note, le texte et le motif
n'apparaissent qu'une fois le téléphone déverrouillé. Les changements
d'emploi du temps, eux, s'affichent en entier. Chaque type d'alerte se coupe
dans les réglages Android de l'app.
