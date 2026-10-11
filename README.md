# Ma Salle

À la fin d'une récré, tout le lycée ouvre Pronote en même temps pour savoir
dans quelle salle aller, et Pronote ne charge plus. Ma Salle met la réponse
sur l'écran d'accueil **avant** la sonnerie : la salle du prochain cours, les
profs absents, les cours annulés, sans rien demander aux serveurs à ce
moment-là.

## Comment ça marche

- **L'emploi du temps est téléchargé à l'avance**, environ toutes les heures
  quand le téléphone a du réseau (JobScheduler). Les widgets lisent la copie
  enregistrée sur le téléphone : ils s'affichent instantanément, même quand
  Pronote est saturé ou qu'il n'y a pas de réseau.
- Deux widgets : **Prochaine salle** (la salle en gros, l'alerte « Prof absent »
  ou « Cours annulé », puis le cours suivant) et **Ma journée** (tous les cours
  du jour avec leur salle).
- Les widgets se redessinent d'eux-mêmes au début et à la fin de chaque cours.
  Ils affichent toujours l'heure à côté de la salle, donc un retard de
  quelques minutes du système n'induit jamais en erreur.

## Connexion : les ENT et l'API interne de Pronote

Pronote n'a pas d'API publique, et les ENT (monlycee.net, etc.) bloquent les
connexions automatiques : monlycee.net renvoie `403 X-Blocked` à tout ce qui
n'est pas un navigateur.

Ma Salle fait comme l'application officielle Pronote :

1. elle ouvre la vraie page de connexion de l'établissement dans une WebView
   (un vrai navigateur, que les ENT ne distinguent pas de Chrome) ;
2. l'élève se connecte lui-même, sur son ENT ou sur Pronote ;
3. Pronote remet alors à ce téléphone un **jeton d'appareil**
   (`window.loginState`), exactement comme à son app mobile ;
4. les mises à jour suivantes se font avec ce jeton, directement auprès de
   Pronote, sans repasser par l'ENT. Le jeton change à chaque connexion et
   l'app enregistre toujours le nouveau.

Ça marche donc avec n'importe quel ENT, sans code spécifique à chacun. Si le
compte a la double authentification, l'app sait déclarer l'appareil et
demander le code PIN.

Le protocole Pronote (chiffrement AES/RSA, compression, numéros d'ordre) est
réécrit en Kotlin à partir des deux bibliothèques encore maintenues en 2026 :

- [Pawnote-LTS](https://www.npmjs.com/package/@blockshub/pawnote-lts) 1.6.4
  (septembre 2026), utilisée par l'app [Papillon](https://github.com/PapillonApp/Papillon) ;
- [pronotepy](https://github.com/bain3/pronotepy) 2.15.7 (septembre 2026),
  compatible Pronote 2026.2.5.

Aucune de ces bibliothèques n'est embarquée : l'app n'a **aucune dépendance**
à l'exécution, tout repose sur Android (HTTP, AES, RSA, deflate, JSON,
widgets).

## Données des élèves

Voir [PRIVACY.md](PRIVACY.md). En résumé : pas de serveur, pas de traqueur,
pas de sauvegarde cloud, jeton chiffré par le Keystore Android, HTTPS
obligatoire avec les seules autorités de certification du système.

## Compiler

```bash
./gradlew assembleDebug
```

JDK 17 et SDK Android 36. Créer `local.properties` avec `sdk.dir=…`.

Tests :

```bash
./gradlew testDebugUnitTest
```

Le test contre le serveur de démonstration d'Index Éducation (faux élèves,
identifiants publics) n'est lancé que sur demande, pour que la compilation
ne dépende jamais du réseau :

```bash
PRONOTE_DEMO=1 ./gradlew testDebugUnitTest
```

La démo est en lecture seule : elle ne peut pas conserver de jeton d'appareil.
La reconnexion par jeton ne peut donc être vérifiée que sur un vrai
établissement.

Dans une version de debug, taper `demo` à la place de l'adresse Pronote charge
le compte de démonstration, pratique pour essayer les widgets.

## Organisation

- `pronote/` : le protocole, en Kotlin pur (sans Android, testé sur la JVM).
  `Session` (chiffrement, ordre des messages), `Login` (jeton, identifiants,
  double authentification), `Timetable`, `Schools` (trouver l'adresse Pronote).
- `app/` : `LoginActivity` (WebView), `Repo` (état, synchronisation), `Vault`
  (fichiers chiffrés), `SyncJob`, `Widgets`, `Agenda` (quel cours afficher).

## Licence

GPL-3.0, comme Pawnote dont le protocole est porté.
