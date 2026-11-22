# AGENTS.md — Samchat : feuille de route de correction

Source : rapport d'audit du 14 novembre 2026 (7 erreurs prouvées par le code).
Dépôt analysé : `wwrcgv-web/MeshChat`, commit `1451985`.

## Règles de travail

1. **Une seule erreur à la fois**, dans l'ordre 1 → 7. Pas de correction groupée.
2. **Vérifier avant de corriger** : relire le code concerné et confirmer que l'erreur existe
   encore dans la révision courante. Le rapport peut être en retard sur le code.
3. **Aucune modification de code sans feu vert explicite.** On annonce d'abord les fichiers
   touchés et l'approche, on attend, puis on écrit.
4. **Chaque correction a un test de vérification** (décrit ci-dessous) et un commit dédié.
5. **Mettre à jour ce fichier** après chaque correction : statut + date + commit.
6. Si le rapport et le code divergent, le signaler et corriger la fiche, pas le code.

## Ordre imposé

| Ordre | Erreur | Statut rapport | Statut vérifié le 22/11/2026 |
|---|---|---|---|
| 1 | Envoi Internet sur le fil principal | Corrigé, à tester | **Déjà corrigé** dans `MainActivity.kt` (threads) |
| 2 | Trois relèves sur la même boîte | À faire (étape 2) | **Déjà corrigé + durci le 22/11/2026** (voir fiche 2) |
| 3 | Messages relevés seulement au retour dans l'app | À faire (étape 3) | **Déjà corrigé + relève immédiate le 22/11/2026** (option A, voir fiche 3) |
| 4 | Envoi refusé quand Samchat est arrêté | À faire | **Corrigé (lots A+B+C, 22/11/2026)** : envoi et relève indépendants du BLE |
| 5 | Jeton obligatoire pour les non-scolarisés | À faire (Worker requis) | **Réel, terrain préparé** : `DeviceKey` prêt ; aucun champ jeton dans l'UI existante |
| 6 | Faux accusés de réception acceptés | À faire | **Réel** : `handleAck` supprime avant d'authentifier |
| 7 | APK de debug : mise à jour impossible | À faire | **Réel** : `assembleDebug` seulement, pas de signature fixe |

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
- **Terrain préparé le 22/11/2026** : `DeviceKey.kt` crée une clé EC P-256 de signature dans le
  Keystore Android (alias `samchat_device_key`), expose `publicKeyHex()`, `deviceId()` et
  `sign(data)`. **Volontairement non branché** : le Worker ne sait pas encore vérifier les
  signatures, et `RelayClient` continue d'envoyer `X-App-Token`.
- Prochaine étape (avec le code du Worker) : `/enregistrer` avec `publicKeyHex`, puis en-têtes
  `X-Device-Id` + `X-Device-Signature` sur `/deposer` et `/relever`, et suppression du jeton.
- **Verdict : réel**, en attente du Worker.

### 6. Faux accusés — `BleMeshManager.handleAck()`
- Ordre actuel : `seenSecret.add` → `deliveredIds.add` → `store.remove` →
  `removeHolding` → **puis** `openAck()`.
- Conséquence : un accusé non authentifié fait disparaître la copie gardée.
- **Verdict : réel, confirmé.**

### 7. APK de debug — `.github/workflows/main.yml`
- `gradle assembleDebug`, `upload-artifact` du dossier `apk/debug`.
- **Verdict : réel.**

## À traiter ensuite (non bloquant, hors ordre)

- Base64 double sur les fichiers du relais (+78 %) et découpe mémoire dans `RelayChunks`.
- Clé privée en clair (`my_priv`) : déjà migrée vers le Keystore par `SecureKeys`.
- Réserve (`MeshStore`, 500 entrées) : accepte tout message d'inconnu.
- Scan BLE en `LOW_LATENCY` permanent.
- Photo 1600 px / qualité 90 vs limite BLE 250 Ko.
- Nom de paquet `com.example.meshchat` refusé par le Play Store.
