# AGENTS.md — Samchat : feuille de route de correction

Source : rapport d'audit du 14 novembre 2026 (7 erreurs prouvées par le code).
Dépôt analysé : `wwrcgv-web/MeshChat`, commit `1451985`.

## Règles de travail

1. **Une seule erreur à la fois**, dans l'ordre 1 → 7. Pas de correction groupée.
2. **Vérifier avant de corriger** : relire le code concerné et confirmer que l'erreur existe
   encore dans la révision courante. Le rapport peut être en retard sur le code.
3. **Aucune modification de code sans feu vert explicite.** On annonce d'abord les fichiers
   touchés et l'approche, on attend, puis on écrit.
4. **Cohérence avant livraison** : relire le plan, le code et les tests ensemble ; s'ils se
   contredisent, corriger avant de livrer.
5. **Recalculer les chiffres** : poser le calcul (unité, fréquence, total) avant de l'annoncer.
6. **Dire ce qui n'a pas été testé** : distinguer « compilé », « testé sur téléphone » et
   « jamais exécuté ». Ne jamais écrire « fait » pour du code non vérifié.
7. **Rien de destructeur sans accord** : SQL qui modifie ou supprime, déploiement, push, retrait
   d'un secret → demander d'abord, avec un retour arrière écrit.
8. **Secrets** : ne jamais afficher, copier ou stocker un jeton ou une clé. Après chaque push,
   rappeler de révoquer le jeton.
9. **Terminer chaque réponse** par : ce qui est fait, ce qui reste non vérifié, ce que
   l'utilisateur a à faire — dans cet ordre.

## Ordre imposé

| Ordre | Erreur | Statut rapport | Statut vérifié le 22/11/2026 |
|---|---|---|---|
| 1 | Envoi Internet sur le fil principal | Corrigé, à tester | **Déjà corrigé** dans `MainActivity.kt` (threads) |
| 2 | Trois relèves sur la même boîte | À faire (étape 2) | **Déjà corrigé + durci le 22/11/2026** (voir fiche 2) |
| 3 | Messages relevés seulement au retour dans l'app | À faire (étape 3) | **Déjà corrigé + relève immédiate le 22/11/2026** (option A, voir fiche 3) |
| 4 | Envoi refusé quand Samchat est arrêté | À faire | **Corrigé (lots A+B+C, 22/11/2026)** : envoi et relève indépendants du BLE |
| 5 | Jeton obligatoire pour les non-scolarisés | À faire (Worker requis) | **Branché le 22/11/2026** : appareil signé ; le jeton reste requis pour déposer |
| 6 | Faux accusés de réception acceptés | À faire | **Corrigé le 22/11/2026** : authentification avant suppression |
| 7 | APK de debug : mise à jour impossible | À faire | **Fait et vérifié le 22/11/2026** : release signée, mise à jour par-dessus sans perte |

## Constat préalable (important)

Le rapport cite des fichiers **absents de ce dépôt** : `RelayReceive.kt`,
`RelayFileReceive.kt`, `RelayAck.kt`, `DeviceKey.kt`, ainsi que `SettingsHub.kt:573`
(le fichier fait 534 lignes). Le zip `MeshChat_prototype_android.zip` ne contient
aucun code de relais.

→ Le rapport a été écrit sur **une autre révision** du projet. Les numéros de ligne
et les noms de fichiers ne sont donc pas fiables ; seule la description du défaut
l'est. Chaque fiche ci-dessous a été re-vérifiée sur le code réel.

## Fiches

### 1. Envoi Internet sur le fil principal — `MainActivity.kt`
- `send()` : `Thread { RelayDispatch.envoyerParRelais(...) }` puis retour UI via `Handler`.
- Photo : idem. Vocal : idem.
- Seul code réseau de l'app : `RelayClient.ouvrir()`, appelé depuis ces threads, depuis
  `RelayPoll` (`Dispatchers.IO`) et depuis `depositAck` (`Dispatchers.IO`).
- **Verdict : corrigé.** Reste à tester sur téléphone (Logcat sans `NetworkOnMainThreadException`).
- Reste un défaut de fond (non bloquant) : `RelayFile` enchaîne les morceaux
  **séquentiellement** avec 2 essais et 10 s de délai chacun → un fichier lent bloque
  longtemps le thread d'envoi.

### 2. Relèves multiples — `RelayPoll.kt`
- Un seul `RelayClient.relever()` (ligne 71), un seul boucleur (5 s, `MeshService`).
- Tri déjà fait par préfixe : `ID:` (texte), `CH:` (fichier), `REÇU:` (accusé).
- **Verdict : structurellement corrigé.** Risque résiduel : `processedIds` est en RAM
  (5 000 entrées) → doublons après redémarrage du service ; et un contenu indéchiffrable
  n'est jamais marqué traité → il est re-relevé à chaque tour.

**Correction appliquée le 22/11/2026** (commit `0ff975c`) — `RelayPoll.kt`, `RelayClient.kt` :
1. `processedIds` sauvegardés dans `filesDir/relay_processed.json` (écriture après chaque relève,
   `processedDirty` ; en cas d'échec d'écriture la liste reste sale et sera réessayée).
2. Contenu indéchiffrable : empreinte SHA-256 gardée 30 min (`opaqueSeen`, 500 max) →
   plus de re-relevé à chaque tour de 5 s.
3. `classer(texte)` : fonction pure retournant `Kind.TEXTE / FICHIER / ACCUSE / INCONNU`,
   utilisée par `processClear` (comportement identique, testable seule).
4. `RelayClient.lastReleveCode` + messages `lastError` précis (« HTTP 401 », « réseau »,
   « jeton absent », « réponse illisible ») : les échecs de relève ne sont plus muets.

- **Reste à tester sur téléphone** : texte + photo + vocal depuis A vers B (une seule fois
  chacun), puis redémarrage du service de B en cours de route (aucun doublon).
- Non compilé localement : ni JDK ni Android SDK sur le téléphone ; la compilation se fait
  par le workflow GitHub.

### 3. Relève seulement au retour dans l'app — `service/MeshService.kt`
- `RelayPoll.start(relayScope, ...)` à `onCreate` : relève permanente, écran éteint ou non.
- `onResume()` ne fait que recharger la langue.
- **Verdict : corrigé.**

**Correction appliquée le 22/11/2026 (option A)** — `RelayPoll.kt`, `MainActivity.kt` :
- `RelayPoll.requestNow()` : relève immédiate, sans attendre le tour de 5 s. Sans effet si le
  service ne fait pas tourner la relève.
- Garde `AtomicBoolean polling` dans `pollOnce` : deux relèves ne se superposent jamais,
  même si l'écran et la boucle se déclenchent en même temps.
- `MainActivity.onStart()` appelle `requestNow()` : un message déjà sur le relais s'affiche
  dès le retour à l'écran, sans intervention de l'utilisateur.
- Le sondeur reste **unique et dans le service** : pas de second boucleur dans l'Activity.
- Cadence adaptative : faite au Lot B (ci-dessous). Long-poll côté Worker : toujours à décider.

### 4. Envoi refusé quand le service est arrêté — `MainActivity.kt`
- Branche Internet de `send()` : ne teste pas `BleMeshManager.instance` → part sans service.
- La **relève** exige le service (elle y tourne).

**Plan validé en 3 lots** (A → B → C) :
- **Lot A (appliqué 22/11/2026)** : `MeshState.attach()` idempotent, appelé depuis
  `MainActivity.onCreate` ; `refresh()` retombe sur `LocalIdentity.knownContacts()` et sur
  les préférences quand `BleMeshManager.instance` est absent. → conversations et historique
  disponibles service Bluetooth coupé ; l'envoi Internet n'est plus bloqué par l'annuaire vide.
- **Lot B (appliqué 22/11/2026)** : `service/RelayService.kt` (foreground, `dataSync`) possède
  désormais `RelayPoll` ; `MeshService` ne garde que le BLE/Wi-Fi. Manifeste :
  `FOREGROUND_SERVICE_DATA_SYNC` + déclaration du service. `MainActivity.onCreate` démarre
  `RelayService` (indépendant du bouton BLE). Cadence adaptative : 5 s écran ouvert, 30 s en
  arrière-plan, jusqu'à 60 s après échecs (`RelayPoll.setAppVisible`/`nextDelay`).
  ⚠️ Android 15 plafonne les services `dataSync` à ~6 h/jour : à surveiller si on veut une
  relève permanente ; alternative `specialUse` ou `connectedDevice` à décider plus tard.
- **Lot C (appliqué 22/11/2026)** : `RelayClient.isEnabled/setEnabled` lisent/écrivent la
  préférence `relay_on` (vrai par défaut) ; le sélecteur « Sans Internet » coupe vraiment le
  relais et arrête `RelayService` ; `MainActivity` ne démarre le relais que si le mode est actif.
  Notifications : groupe commun `samchat_services` posé sur les deux notifications. Pour un
  vrai regroupement (une ligne au lieu de deux), il faudra ajouter une notification résumé
  explicite : non fait, volontairement, pour ne pas complexifier maintenant.
- **Verdict après A + B + C** : l'envoi, la réception et les accusés ne dépendent plus du
  service Bluetooth, et le mode « Sans Internet » coupe réellement le relais.
  Reste à valider sur téléphone ; pas de compilation locale possible.

### 5. Jeton obligatoire — `RelayClient.kt`, `build.gradle.kts`, `DeviceKey.kt`
- `token()` = `BuildConfig.RELAY_TOKEN` (variable d'env. à la compilation).
- `saveToken()` reste vide ; **aucun écran ne demande de jeton** dans cette révision (le
  rapport citait `SettingsHub.kt:573`, or ce fichier fait 534 lignes et ne parle que des
  réglages de relais BLE). Il n'y a donc rien à masquer : le seul point d'entrée est le secret
  de compilation.
- **Branché le 22/11/2026** (voir « Relais v3 » ci-dessous) : `DeviceKey` signe, `RelayClient`
  s'inscrit auprès du Worker, `/relever` et `/confirmer` sont authentifiés par signature.
- Il reste à activer `DEPOT_SIGNATURE=true` côté Worker, observer quelques jours, puis retirer
  `APP_TOKEN` (et rebâtir l'app une dernière fois sans secret de compilation).

### 6. Faux accusés — `BleMeshManager.handleAck()`
- Défaut : `confirmedId` circule en clair ; `deliveredIds.add`, `store.remove` et
  `removeHolding` s'exécutaient **avant** `openAck()`. Une trame forgée supprimait la copie
  gardée, bloquait tout ré-envoi (`parcelsFor`), polluait `deliveredIds` et se relayait aux voisins.

**Correction appliquée le 22/11/2026** — `BleMeshManager.kt` :
1. Authentification d'abord : `openAck(cipher)` déchiffre avec la clé partagée du contact.
   Échec → trame ignorée localement, simplement relayée (`relayAckIfNeeded`) : un relais n'a pas
   la clé, mais il ne supprime plus rien.
2. L'identifiant en clair doit être égal à celui contenu dans le chiffré (`opened.second`) :
   bloque le rejeu détourné.
3. Type d'accusé limité à `KIND_RECEIVED` / `KIND_READ`.
4. Anti-rejeu (`seenSecret`) déplacé après authentification.
5. `isLocallyKnown()` (défense en profondeur) : n'agit que si le message est chez nous
   (`ownSent`, `holdings`, `store`).
6. Le relais aveugle est conservé pour le multi-sauts ; les copies des relais expirent par
   `keepHours` au lieu d'être effacées sur un faux accusé.
- **À tester** : faux accusé (copie conservée), vrai accusé (statut + suppression), rejeu détourné
  (ignoré).

### 7. APK de debug — `.github/workflows/main.yml`
- Défaut : `gradle assembleDebug` + upload de `apk/debug` → nouvelle clé de debug à chaque
  build, mise à jour impossible sans désinstaller (perte de l'identité et de l'historique).

**Appliqué et poussé le 22/11/2026** :
- Keystore `samchat-release.p12` (RSA 4096, PKCS12, alias `samchat`, valide jusqu'en 2056)
  généré hors du dépôt (`/root/workspace/.keys/`, copie dans `/sdcard/Download/DSH-Go/keystore/`).
- `.gitignore` : `*.p12`, `*.jks`, `*.keystore`, `*.b64`, `*.apk`, `build/`, `.gradle/`.
- `overlay/app/build.gradle.kts` : `signingConfigs.release` lisant `RELEASE_KEYSTORE`,
  `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` ; `versionCode`
  dérivé de `GITHUB_RUN_NUMBER`, `versionName = 0.2.<run>`.
- Workflow : décodage du secret `KEYSTORE_BASE64` dans `$RUNNER_TEMP`, `gradle assembleRelease`,
  échec explicite si l'APK sort non signée, upload de `apk/release`.
- Secrets GitHub créés par l'utilisateur ; build 45031434375 verte.
- **Vérifié sur téléphone le 22/11/2026** : installation de la release signée **par-dessus** la
  version précédente, sans perte de données ; `versionCode` dérivé du run GitHub.
- ⚠️ Le mot de passe et le keystore ont transité par la conversation : à sauvegarder hors ligne,
  et à considérer comme exposés.


## Hors audit — conformité (22/11/2026)

### Écran d'acceptation des CGU au premier lancement — `TermsScreen.kt`, `MainActivity.kt`
- `TermsScreen` : plein écran, texte défilant en 6 clauses (chiffrement de bout en bout, réseau
  maillé, mode Internet et identifiant haché, clé privée non sauvegardée = perte du compte,
  données minimales, absence de garantie), case à cocher obligatoire, bouton « Commencer »
  désactivé tant que la case n'est pas cochée.
- `MainActivity` : `cgv_accepted` (booléen) + `cgv_accepted_at` (horodatage) dans les
  préférences `meshchat`. Tant que ce n'est pas accepté : **aucun service, aucune permission,
  aucun attachement de `MeshState`**. `startAfterTerms()` regroupe ces démarrages.
- Textes traduits FR/EN (clés `terms_*` dans `Strings.kt`) ; les 4 autres langues affichent
  l'anglais pour ces clés.
- **Vérifié sur téléphone le 22/11/2026** : bouton grisé tant que la case n'est pas cochée,
  permissions demandées seulement après acceptation, écran non réaffiché au lancement suivant.
- ⚠️ Juridiquement, ceci n'est qu'un écran d'information : un vrai texte de CGU/politique de
  confidentialité doit être relu et hébergé (URL publique), et l'acceptation ne vaut pas
  consentement RGPD pour un traitement de données.

## Relais v3 — appareil signé (22/11/2026)

Worker v2.1 déployé (Supabase : `lu_le`, `expediteur_appareil`, table `appareils`, cron 4×/jour).
Côté app :

- `RelayDispatch.boiteDe(clePubliqueHex)` : boîte = `SHA-256("samchat-relais-v2:" + clé d'identité)`,
  identique au Worker. L'ancienne boîte (`"samchat-relais:" + myId`) disparaît : ses messages
  expirent en 24 h, aucun rattrapage n'est codé (décision assumée, testeur unique).
- `RelayClient.assurerEnregistrement()` : inscrit l'appareil (`cle_identite`, `cle_publique`,
  `boite`, `horodatage`, `signature_identite` = ECDSA de `boite\ncle_publique\nhorodatage`),
  stocke `relay_device_id`. Lancé en tâche de fond au démarrage.
- Requêtes signées (`X-Device-Id`, `X-Timestamp`, `X-Signature` en base64 de 64 octets r||s)
  sur `/relever` (sans query) et `/confirmer`.
- Repli automatique sur le jeton si `/relever` répond 401 (appareil inconnu, Worker injoignable).
- `/deposer` conserve le jeton (exigence du Worker pendant la transition) et ajoute la signature
  quand elle existe, pour que le Worker compte par appareil.
- `RelayPoll` : lecture → traitement → `confirmer(ids)`. Un message non confirmé est relivré,
  le dédoublonnage local évite l'affichage double : au pire un doublon, jamais une perte.
- `DeviceKey.signRaw()` + `CryptoEngine.derToRaw()` : conversion DER → 64 octets pour WebCrypto.
- `LocalIdentity.autoTestSignature()` au démarrage : journalise « Signature d'identité : OK »
  ou le repli jeton (vérifie que le provider du téléphone signe bien avec la clé ECDH).

## Hors audit — interface (22/11/2026)

### Écran Discussions — `HomeUi.kt`, `Strings.kt`
- Barre de recherche et rangée de filtres (« Toutes » / « À portée ») supprimées : la liste
  remonte directement sous le sélecteur de mode.
- « Tous » renommé « Groupe » dans les 6 langues (clé `all_group`) ; identifiant interne
  `public` et diffusion à tous les pairs inchangés.
- Clés devenues orphelines retirées : `search`, `all`, `in_range`.
- `onScan` et `onShowQr`, jusqu'ici inutilisés, sont désormais branchés.

### Icône Bluetooth — `ModeSelector.kt`, `res/drawable/ic_bluetooth.xml`
- Icône verte (17 dp) devant « Sans Internet », teintée `SamchatColors.Green`.

### Écran Réseau (compte et QR) — `NetworkScreen.kt`, `HomeUi.kt`, `MainActivity.kt`
- Nouvel onglet Contacts : fond clair (`#F4F8F5`), cartes blanches, texte foncé — lisible en
  plein soleil. Carte « Mon compte » cliquable ; section Contacts nettement séparée.
- Icône QR (`ic_qr.xml`) ajoutée en haut à droite de la barre d'accueil sur cet onglet.
- Écran QR dédié (`QrScreen.kt`) en deux onglets : « Mon QR code » (phrase verte, QR 270 dp sur
  blanc, pseudo en gras) et « Scanner » (caméra plein écran avec cadre, démarrée seulement sur
  cet onglet, `BarcodeView` créée à l'entrée et mise en pause à la sortie). Titre « Contacts » +
  croix de fermeture.
- L'écran est rendu dans un `Dialog` plein écran (`usePlatformDefaultWidth = false`,
  `decorFitsSystemWindows = false`) : sans cela, il était composé derrière le `Surface` de
  l'accueil et restait invisible. **Vérifié sur téléphone le 22/11/2026.**
- Page « Contacts » des réglages supprimée (boutons QR/scan + liste doublon) ; pages
  suivantes renumérotées. La liste des contacts reste dans l'onglet du bas.
- Carte « Mon compte » cliquable, sans bouton ; l'icône QR de la barre ouvre le même écran.
- Note : `QrShare.kt` ne contient aucune UI (uniquement la génération/lecture du QR) et
  `AccountScreen.kt` reste la section « sauvegarde du compte » des réglages : non modifiés.

## À traiter ensuite (non bloquant, hors ordre)

- ~~Base64 double sur les fichiers du relais.~~ **Fait le 22/11/2026** : format `CH2:` =
  en-tête ASCII + octets bruts, un seul Base64 (celui du transport). ~25 % de données en moins
  par morceau. L'ancien `CH:` (Base64 interne) reste décodé pour les morceaux déjà en vol.
  `lireMorceau()` et `reassembler()`, inutilisés, ont été supprimés.
- ~~Clé privée en clair (`my_priv`).~~ Périmé : `SecureKeys` migre déjà vers le Keystore.
- Réserve (`MeshStore`, 500 entrées) : accepte tout message d'inconnu.
- ~~Scan BLE en `LOW_LATENCY` permanent.~~ **Fait le 22/11/2026** : scan adaptatif.
  Écran éteint → `LOW_POWER` ; retour au premier plan ou allumage → 30 s de `LOW_LATENCY` ;
  écran allumé sans pair → `LOW_LATENCY` ; sinon `BALANCED`. Signaux fournis par
  `MeshService` (`ACTION_SCREEN_ON/OFF`) et `MainActivity.onStart/onStop`. Redémarrages du scan
  espacés de 20 s (limite Android), le `sweeper` réévalue toutes les 5 s ; le scan n'est pas
  relancé pendant un envoi si `PREF_SCAN_SEND` est faux. **À mesurer** : `dumpsys batterystats`
  et délai de découverte dans les trois états.
- Photo 1600 px / qualité 90 vs limite BLE 250 Ko.
- ~~Nom de paquet `com.example.meshchat` refusé par le Play Store.~~ **Fait le 22/11/2026** :
  `applicationId = io.github.wwrcgv.samchat` (namespace inchangé). Changement à pousser après
  l'export QR de l'utilisateur : le nouvel identifiant crée une application distincte sur le
  téléphone (données non transférées).
