# Sallio

À la fin d'une récré, tout le lycée ouvre Pronote en même temps pour savoir
dans quelle salle aller, et Pronote ne charge plus. Sallio met la réponse
sur l'écran d'accueil **avant** la sonnerie : la salle du prochain cours, les
profs absents, les cours annulés, sans rien demander aux serveurs à ce
moment-là.

## L'app

Quatre onglets, pour l'essentiel de Pronote au quotidien sans le charger :

- **Accueil** : le prochain cours en grand (salle, prof, horaires, alerte),
  ce qui a changé aujourd'hui et demain, les devoirs du prochain jour de cours
  et les dernières notes.
- **Cours** : deux semaines d'emploi du temps, jour par jour ; un point rouge
  signale les jours avec un prof absent ou un cours modifié.
- **Devoirs** : le cahier de textes des deux prochaines semaines. Cocher un
  devoir le coche aussi sur Pronote (`SaisieTAFFaitEleve`, comme l'app
  officielle) ; sans réseau, la coche attend et part à la synchro suivante.
- **Notes** : moyenne générale et de classe, dernières notes, moyennes par
  matière ; les notes arrivées depuis la dernière visite sont signalées. Une
  note touchée ouvre sa fiche : barème, coefficient, commentaire, et sa place
  dans la classe (note la plus basse, moyenne, plus haute).

Pour tout le reste (messagerie, vie scolaire, bulletins), « Ouvrir Pronote ».

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

Sallio fait comme l'application officielle Pronote :

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

## Publier une mise à jour

L'app vérifie au plus toutes les 6 heures, à l'ouverture, la dernière release
GitHub de `Ofanon/sallio`. Si son APK (`sallio-<versionCode>.apk`) est plus
récent, elle propose « Mettre à jour » ; Android demande confirmation à
l'élève. Avant de l'installer, l'app vérifie que l'APK est bien Sallio, du
bon numéro de version, et signé avec la même clé.

1. Augmenter `versionCode` **et** `versionName` dans `app/build.gradle.kts`, commiter.
2. `./release.sh "Ce qui change, pour les élèves."`

Le script refuse de publier s'il reste des changements non commités, si la
version existe déjà ou si l'APK n'est pas signé avec la clé Sallio.

**La clé `sallio-release.jks` et `keystore.properties` n'existent que sur ce
PC** (exclus de git). Clé perdue : plus aucune mise à jour possible, il faudrait
que chacun désinstalle et réinstalle. Clé volée : quelqu'un pourrait publier
une fausse mise à jour. À sauvegarder hors ligne (clé USB), jamais en ligne.

Si l'app passe un jour sur le Play Store, la mise à jour intégrée devra être
retirée de cette version : Google interdit qu'une app se mette à jour seule.

## Organisation

- `pronote/` : le protocole, en Kotlin pur (sans Android, testé sur la JVM).
  `Session` (chiffrement, ordre des messages), `Login` (jeton, identifiants,
  double authentification), `Timetable`, `Schools` (trouver l'adresse Pronote).
- `app/` : `LoginActivity` (WebView), `Repo` (état, synchronisation), `Vault`
  (fichiers chiffrés), `SyncJob`, `Widgets`, `Agenda` (quel cours afficher),
  `Updater` (mises à jour depuis GitHub).

## Licence

GPL-3.0, comme Pawnote dont le protocole est porté.

Polices Nunito et Fredoka : SIL Open Font License 1.1 (textes dans
`app/src/main/assets/fonts/`). Icônes : Tabler Icons, licence MIT.
Maquettes de l'interface : `design/`.
