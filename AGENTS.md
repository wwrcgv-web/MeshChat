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
| 3 | Messages relevés seulement au retour dans l'app | À faire (étape 3) | **Déjà corrigé** : relève toutes les 5 s dans `MeshService` |
| 4 | Envoi refusé quand Samchat est arrêté | À faire | **Partiellement corrigé** : envoi OK, relève dépend du service |
| 5 | Jeton obligatoire pour les non-scolarisés | À faire (Worker requis) | **Réel** : `BuildConfig.RELAY_TOKEN`, aucun écran de saisie |
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

**Correction appliquée le 22/11/2026** (commit : à faire) — `RelayPoll.kt`, `RelayClient.kt` :
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

### 4. Envoi refusé quand le service est arrêté — `MainActivity.kt`
- Branche Internet de `send()` : ne teste pas `BleMeshManager.instance` → part sans service.
- La **relève** exige le service (elle y tourne).
- **Verdict : partiellement corrigé** ; à repréciser avant de clore.

### 5. Jeton obligatoire — `RelayClient.kt`, `build.gradle.kts`
- `token()` = `BuildConfig.RELAY_TOKEN` (variable d'env. à la compilation).
- `saveToken()` et `setEnabled()` sont vides ; l'interrupteur « Avec Internet » n'a aucun effet.
- Jeton vide → `deposer()` échoue silencieusement, repli BLE.
- **Verdict : réel.** Nécessite le code du Worker Cloudflare.

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
