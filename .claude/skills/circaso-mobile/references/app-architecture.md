# Circaso app architecture

Decided with Pasaka (2026-10-25). Draft until the first feature is built on it; change this file when a decision changes.

## Decisions
| Topic | Decision |
| --- | --- |
| Modules | One Gradle module, `shared`, packages by feature. Split out `core` pieces only if build times hurt. |
| UI | Compose Multiplatform, own design system on Foundation (see `design-system.md`). |
| Navigation | Navigation 3 (`org.jetbrains.androidx.navigation3`) in `commonMain`, typed `@Serializable` keys, one back stack per top-level flow. |
| State | MVVM: one ViewModel per screen, one `StateFlow<UiState>`, one-off events on a separate channel (navigate, toast). |
| DI | Koin (`koin-core`, `koin-compose`, `koin-compose-viewmodel`). |
| Network | Ktor client only (OkHttp on Android, Darwin on iOS), kotlinx.serialization. No second HTTP library. |
| Device key, tokens | Own thin `expect/actual` wrappers over Android Keystore and iOS Keychain/Secure Enclave. No generic secure-storage library. |
| Architecture | Clean architecture, three layers per feature: `presentation`, `domain`, `data`. |
| Local database | Room KMP (SQLite, bundled driver) for cached and offline data. |
| Key-value storage | DataStore (Preferences) for non-sensitive settings only. Tokens and the device key never go in DataStore (it is not encrypted): they stay in the Keystore/Keychain wrappers. |
| Offline | Offline-first is a product requirement: see `offline-first.md`. Room is the read source, writes go through an outbox in `core/sync`. |
| Backend contract | `mobile-integration.md` wins; features the backend lacks stay behind a feature flag. |

## Package layout (`shared/src/commonMain/kotlin/com/circaso/`)
```
App.kt                      root composable: theme + navigation host
di/                         Koin modules (core, per feature, platform)
designsystem/               theme, components, motion, platform style (exists)
gallery/                    debug-only component gallery (exists)
core/
  database/                 CircasoDatabase (Room KMP), platform database builders, migrations
  datastore/                DataStore factory (expect/actual path), typed preference keys
  sync/                     Outbox (Room table + worker), reconnect orchestration, connectivity state
  network/                  CircasoApi (HttpClient), Envelope<T>, ApiError(code, requestId),
                            interceptors: X-Client-Version, Idempotency-Key, Authorization,
                            SSE reader (Asha), RealtimeClient (WebSocket)
  session/                  SessionStore, AuthManager (the `next` state machine), single-flight refresh
  platform/                 expect/actual seams: DeviceKeyStore, SecureStorage, SocialSignIn,
                            PushTokenProvider, ContactsReader, AudioRecorder/Player, ClientInfo, Haptics,
                            ConnectivityMonitor
  navigation/               route keys (NavKey), AppNavigation, back-stack holders per flow
  flags/                    FeatureFlags for MISSING backend features (chats, money, calls ...)
  common/                   AppError mapping (error code -> UiText), dispatchers, small utils
feature/<name>/
  presentation/             Screen composables, ViewModel, UiState, UiEvent. Nothing else.
  domain/                   Models, use cases, repository contracts (interfaces). Pure Kotlin.
  data/
    remote/                 API service, DTOs (@Serializable, named as in the guide), DTO mappers
    local/                  Room entities + DAOs, DataStore wrappers, entity mappers
    repository/             Repository implementations (the only place remote + local meet)
```

Features, in build order: `auth` (sign-in, phone, device), `profile`, `contacts` (+connections, invites, blocks), `assistant` (Asha), `account` (security, recovery). Later: chats, wallet, calls (flagged off until the backend exists).

`androidMain` / `iosMain` mirror `core/platform` for the actuals only. `iosApp` and `androidApp` stay thin shells.

## Rules
1. **Layers:** `presentation -> domain <- data`. Presentation and data depend on domain; domain depends on nothing (no Ktor, Room, Compose, Android or iOS types).
2. **Presentation** holds UI, state and ViewModels only. A ViewModel exposes `uiState: StateFlow<UiState>` and `events: Flow<UiEvent>` and calls **use cases**, never repositories, DTOs or DAOs directly. No platform types in it.
3. **Domain** holds models, use cases and repository contracts. One use case per user action (`VerifyPhoneUseCase`, `SyncContactsUseCase`); a use case may be a thin pass-through, the layer boundary is the point. Contracts return domain models and a domain result type, not `Envelope` or entities.
4. **Data** owns three sub-areas and the mappers between them: `remote` (DTO <-> domain), `local` (entity <-> domain), `repository` (implements the domain contract; decides cache vs network). DTOs and entities never leave `data`.
5. **Offline strategy:** Room is the source for lists the user reads (contacts, profile, Asha history, security log); repositories expose a `Flow` from Room and refresh from the network. Screens show cached data first. Cursor values from the API are stored as given, never built.
6. **What goes where:** Room = user data we can refetch (contacts, connections, profile cache, chat history, security events). DataStore = non-sensitive preferences and small markers (theme override, onboarding step, sync ids and timestamps, last-seen `seq` per realtime module, feature-flag overrides). Keystore/Keychain wrappers = refresh/access tokens, `deviceId` secret material, the device key. Phone-book names never leave the phone and are not stored in Room either.
7. **Errors:** the API layer turns an envelope failure into `ApiError(code, requestId, retryAfter, fields)`; repositories map it to a domain error; one mapper turns the error `code` into user text. UI text is keyed on `code`, never `message`.
8. **Session is core, not a feature.** `AuthManager` owns tokens and refresh (single-flight, store the new refresh token before using it). Features read its state through a domain contract.
9. **Navigation by auth state.** Top-level flows: `Launch -> SignedOut | Onboarding (VERIFY_PHONE, REGISTER_DEVICE) | Main (tabs)`. A `session.revoked` frame or `identity.session_revoked` resets to `SignedOut` and clears Room user data and tokens.
10. **Idempotency:** the interceptor mints a key per call; a retried write reuses it, so repositories pass the key down for retries.
11. **Never log** tokens, codes, keys or phone numbers.
12. **Tests:** `commonTest` with fake repositories for use cases and ViewModels, fake data sources for repositories. A Ktor mock engine will be proposed before it is added. `DeviceKeyStore` gets a sign-and-verify test per platform (guide section 5).

## Build order (matches the integration guide, section 10)
1. Skeleton: Koin, `core/database` (Room) and `core/datastore`, `core/network` (client, envelope, interceptors, base URL setting), `core/navigation`, force-update screen.
2. `core/platform` DeviceKeyStore + SecureStorage, with tests.
3. `auth` feature: Google sign-in, phone verification, device registration, refresh, sign-out.
4. `profile`, then `contacts`, then realtime, then `assistant`, then `account`.

## Encrypted chats: libsignal and the crypto bridge (2026-10-28)
Decided while starting integration guide §10 step 11 (plan: `~/.claude/plans/mighty-stirring-pebble.md`).
- **Library:** libsignal **0.106.1** on both platforms, pinned together (`libsignal` in `libs.versions.toml`, the Swift package and the XCFramework in `iosApp`). The two sides must be the same release or iOS fails to link on `signal_ffi_version_X_Y_Z`.
- **Android:** `org.signal:libsignal-android` + `org.signal:libsignal-client` from Signal's own Maven (`build-artifacts.signal.org`, added in `settings.gradle.kts`; Maven Central stopped at 0.86). The client jar is Java 21 with records, so `androidApp` compiles at **Java 17** with **core library desugaring** (`desugar_jdk_libs` 2.1.5); AGP 9 only desugars records in debug builds above language level 14. `libsignal_jni_testing.so` is excluded from the APK. NDK **r30 (30.0.16248370)** is installed at `~/Library/Android/sdk/ndk/` (fetched from `dl.google.com/android/repository`, no `sdkmanager` on this Mac) and pinned as `ndk` in the version catalog so AGP strips the natives: `libsignal_jni.so` is 9.4 MB on arm64 (124 MB unstripped) and the universal debug APK about 65 MB. Release builds go out as per-ABI AABs.
- **iOS:** the `LibSignalClient` Swift package (source) plus Signal's prebuilt `LibSignalFfi` XCFramework, declared in the local package `iosApp/Packages/LibSignalFfi` (binary target, URL + checksum). No Rust toolchain needed.
- **Bridge (`core/crypto/SignalCrypto`):** one stateless interface in `commonMain`. Each call takes the records it needs (own identity, the peer's session, prekeys by id, a sender-key record) and returns the result plus the changed records; the messaging layer stores them sealed in Room in one transaction per operation. Android implements it on the Java API (`AndroidSignalCrypto`); iOS through `SignalBridgeApi`, implemented in `iosApp/iosApp/SignalCryptoImpl.swift` and installed in `iOSApp.swift` (`SignalBridge.shared.api`). Swift failures cross as an `NSError` whose description is libsignal's error case name; both sides map to `SignalException` (`Duplicate`, `NoSession`, `UntrustedIdentity`, `MissingKey`, `Invalid`).
- **Wire format (`core/crypto/WireFormat`):** the server's `ciphertext` is base64 of `[version 1][type][libsignal bytes]`; types 1 PreKey, 2 Whisper, 3 SenderKey message, 4 SenderKey distribution. Inside is a `ChatPayload` JSON (`v`, `type` text/senderKey/metaKey/control, …) padded to 160-byte buckets.
- **PQXDH is mandatory.** libsignal 0.106 refuses a prekey bundle without a Kyber (ML-KEM 1024) prekey; X3DH-only sessions no longer exist. The backend gained `kyberPreKey { keyId, publicKey, signature }` (a signed last-resort Kyber prekey, rotated like the signed prekey, required on `PUT /v1/keys`, returned by `GET /v1/keys/{userId}`) on 2026-10-28, on branch `kyber-prekeys` of `circaso-backend` (migration V2; bundles published before it were removed). It reaches the sandbox when that branch is merged to `main`. The guide's "Changed on 2026-10-28, later" note describes it.
- **iOS build notes:** build the simulator with `ARCHS=arm64` (`generic/platform=iOS Simulator` alone asks for x86_64, which the KMP framework does not have). In `SignalBridgeApi`, every method is `@Throws` and its Swift implementation must be `throws`; `NSData` parameters arrive in Swift as `Data`; method names must not start with an ObjC selector family (`new`, `init`, `copy`, `alloc`, `mutableCopy`) and must not repeat a name from another exported interface with a different signature, or Kotlin/Native mangles them (`doNewIdentity`, `sha256(bytes_:)`). Check `shared/build/xcode-frameworks/.../Shared.h` when Swift says the type does not conform.
- **Watch `iosApp.xcodeproj/project.pbxproj`:** on 2026-10-28 something (IntelliJ or Android Studio was open) rewrote it once and dropped the libsignal package entries; a copy with them is kept at the scratchpad path noted in the session and the entries are: remote package `signalapp/libsignal` exact `0.106.1` (product `LibSignalClient`) and local package `Packages/LibSignalFfi` (product `LibSignalFfi`), both linked in the app target's Frameworks phase.
- **Key lifecycle (M2, built 2026-10-28):** `feature/messaging/data/local/SignalLocal.kt` holds the Signal tables (identity, EC and Kyber signed prekeys with a 30-day retired period, one-time prekeys, sessions, peer identities, sender keys, id counters; Room v7). `SealedRecordStore` seals every private record with `LocalSealer` under a `table:id` label. `KeysRepositoryImpl` publishes on first use or after a server reset (`messaging.keys_not_found`), rotates the signed and Kyber prekeys after 7 days, tops up one-time prekeys to 100 when fewer than 20 are left, saves new keys **before** sending so a lost answer never loses private halves, and deletes a batch at once when the server refused it whole. `KeyMaintainer` runs it whenever the session is active and the phone online (launch, after registration, reconnect). `peerBundle()` decodes M3 and keeps the trusted identity per person (trust on first use; a change is reported to the caller). The server does not publish registration ids, so bundles carry 0; libsignal only stores it.
- **Chats and messages (M3–M6, built 2026-10-28), `feature/messaging/`:**
  - *Data:* `ChatLocal.kt` (Room v8: `chat`, `chat_member`, `message`, `message_receipt`, `pending_inbox`, `group_meta_key`, `sender_key_delivery`). The `message` row is the source of truth for a send: `clientMessageId` (what the server dedupes on), `status` (`waiting → sending → sent → delivered → read`, or `failed`), `cachedRequest` (the encrypted request, so a retry sends identical bytes), `orderAt` (server time when known). `ChatsRemote`/`MessagesRemote` are the M4–M14, M22 routes.
  - *Crypto plumbing (`MessagingCrypto.kt`):* `SessionCipher` holds one lock per peer, starts a session from the bundle (`KeysRepository.peerBundle`) when there is none, reports an identity change instead of accepting it silently, and deletes a used one-time prekey where it is known to be used. `GroupKeys` keeps my sender key per chat (rotated with a new `distributionId` after anyone leaves), the group metadata key per `metaVersion` (sealed; the name is AES-GCM under it with a fixed AAD), and which member already holds which of my keys (`sender_key_delivery`), so each distribution goes once, as a `SENDER_KEY` message with a 1:1 copy per member.
  - *Send (`ChatSendHandler`, outbox type `chat.message`):* encrypt at send time, cache the request, `POST` with the outbox row's `Idempotency-Key`; `recipients_mismatch` reloads the chat and re-encrypts once; a refusal marks the row `failed` (design G2); network or 5xx leaves it `waiting` (G3) and pauses the queue. A retry after the 20-hour window gets a new `clientMessageId`.
  - *Receive (`InboxProcessor`):* `GET /v1/inbox` paged, and `message.new` frames with inline ciphertext; decrypt → save → ack (frame when connected, else `POST /v1/inbox/ack`); duplicates are acked without saving; unreadable messages are saved as such and acked (never redelivered); a group message without its sender key waits in `pending_inbox` unacked and opens when the key arrives (a day at most). An unknown chat is loaded before its first message is saved.
  - *Frames (`MessagingFrameHandler`, from `RealtimeDispatcher`):* receipts move my message forward only once every recipient has reached that step; typing shows for 6 s; `message.deleted` tombstones; `message.undeliverable` marks mine `failed` with `messaging.undeliverable` ("they changed phones", send again); `keys.changed` resets the session and writes "‹Name›'s security code changed" into every shared chat; `member.added/removed` and `chat.updated` go to `ChatsRepositoryImpl.on*`, which rotate sender keys, re-seal the name under a new metadata key when I removed someone (or own the group), and redistribute.
  - *Realtime client:* `send(module, type, data)` for `ack`, `read`, `typing` (100 frames/s and 64 KB guards; false when disconnected, so acks fall back to HTTP and typing is dropped), `connected` flag; `MessagingStarter` refreshes chats and pulls the inbox each time the socket connects. `KnownModules` now includes `messaging`.
  - *Presentation:* `ChatsTab` (C1) inside the home scaffold with the G1/G3b banner (`Outbox.waitingMessages`), `ThreadScreen` (C2/D2 with G2/G3 states, `/asha` in the composer, read marker on view, typing every 4 s), `NewChatScreen` (C8), `NewGroupScreen` (D1 without the wallet half), `ChatInfoScreen` (members, roles, rename, add, leave, safety number). Home opens on Chats, as C1's tab bar shows.
  - *Push (Android):* `CircasoMessagingService.onMessageReceived` pulls and decrypts the inbox within FCM's window and posts the real sender and text (`AndroidChatNotifications`, channel "Messages"); `POST_NOTIFICATIONS` is asked on the Chats tab, not at launch; the open thread suppresses its own chat. iOS shows the server's placeholder alert until the Notification Service Extension (M7).
  - *Time:* `core/platform/LocalTime` (expect/actual) formats clock, weekday and short date in the phone's locale; `chatListTime` and `threadDayLabel` decide which.
- **Tests:** `shared/src/androidHostTest/.../SignalRoundTripTest` runs real libsignal on the JVM through the bridge (PreKey then Whisper, out-of-order and replay, sender keys and rotation, fingerprints, AES-GCM, wire format). Common tests cover `WireFormat`, `Padding` and `ChatPayload`.

## Backend integration status (2026-10-25)
Built and tested (16 + 8 + 16 unit tests on Android and the iOS simulator):
- **Session and device key:** `core/session` (`AuthManager`, `SessionStore`, `DeviceIdentity`) with secure storage (Android Keystore AES-GCM, iOS Keychain) and the hardware device key (Android Keystore/StrongBox, iOS Secure Enclave; software key on the simulator only). The self-check in the gallery passes on the Redmi (all 10 checks) and the iOS simulator. Refresh is single-flight, tokens are written before use, and a network failure never signs anyone out; `401 auth.token_*` refreshes once and retries once.
- **Auth feature, data and domain** (`feature/auth`): nonce, social sign-in (with the device proof once the phone holds its key), phone start/verify/carrier, device registration, sign-out, forget phone; `SignInWithSocialUseCase` strings nonce, platform sign-in and server check together. The platform `SocialSignIn` (Google/Apple) is an interface with no implementation yet.
- **Live sandbox check (iOS simulator):** nonce received, unauthenticated call refused with `auth.token_missing`. Android was verified the same way in code but the phone dropped off USB before the run.
- **Sign-in screens** (`feature/auth/presentation`): Splash (ID-01), Welcome (ID-02: Apple on iOS, Google on both), Phone (ID-04, ID-07 number taken), Code (ID-06, resend countdown, locked state), Signing you in (ID-03). The root screen is decided by the session (`AppNavigation`): SignedOut shows Welcome, a half-finished sign-up resumes at its stored step, Active opens the app (the gallery stands in for Home). 73 Android and 79 iOS tests pass.
- **Google on Android:** Credential Manager with the Web client id (`SocialConfig.GoogleWebClientId`). **Apple on iOS:** system AuthenticationServices, nonce sent as SHA-256 hex; needs the "Sign in with Apple" capability in Xcode. Both written, compiled and unit-tested only as far as the use case; neither has run end to end yet.
- **Bundle and package id:** `com.circaso.app` on both platforms (matches the server's Apple audience). Code packages stay `com.circaso`.
- **Offline core:** `OutboxProcessor` sends queued writes in order with each item's original `Idempotency-Key`; network, 5xx and rate limits pause the run and keep everything queued; a server refusal fails only that item (with its code); items older than 20 h are failed as `outbox.stale` rather than replayed (server idempotency window is 24 h). Not yet triggered automatically on reconnect (wired with the first feature that queues writes).
- **Tests:** 20 ViewModel tests (Welcome, Phone, Code, Registering) using `kotlinx-coroutines-test` (test-only, approved), on top of the session, network, auth, outbox and data tests.
### Integration coverage by backend area (233 Android and 239 iOS tests, all passing)
| Area (guide section 7) | Data and domain | Screens | Offline behaviour |
| --- | --- | --- | --- |
| Sign in, phone, device, refresh, sign-out | `feature/auth`, `core/session` | Welcome, Phone, Code, Signing you in | online-only; the session works offline |
| Profile, handles | `feature/profile` | You tab, Profile (name, about, @handle) | edits queued and merged; handle needs the server; cached |
| Avatar upload | `feature/profile` | none (needs a platform image picker) | online-only |
| Contacts, connections, invites, blocks | `feature/contacts` | Contacts (search, connect by @handle, invite link, per-contact actions), Blocked | remove/block/unblock queued; connect/invite online-only; cached |
| Phone-book sync | `feature/contacts` + `core/platform/ContactsReader` (Android ContentResolver, iOS Contacts) | "Find Friends from Your Contacts" with the design's explainer (A9); only phone numbers are read and sent, normalised to E.164 | online-only, sends only new numbers |
| Account security, linked logins, log, delete, remove phone | `feature/account` | Account and Security, Security Activity (delete needs the word DELETE) | push token queued; overview and log cached; the rest online-only |
| Recovery request | `feature/account` | none | online-only |
| Realtime socket | `core/realtime` | n/a | connects when active and online; replay by seq; resync |
| Asha text chat | `feature/assistant` | Asha chat (streaming reply, offline-aware composer, clear conversation) | history cached; delete queued; asking online-only |
| Asha voice and speech | `feature/assistant` | none (needs audio recorder/player) | online-only |
| Push token delivery | `feature/account` | none (needs FCM/APNs) | queued |
| Queue visibility | `core/sync` | "Needs attention" on the You tab (Retry/Discard, full reason), "Back online · N changes sent" toast once after reconnecting | n/a |

Typing `/asha` in any non-secret text field opens Asha, but only while a full session exists. Chats and Wallet tabs say plainly that they are coming (no backend). Every screen is a stateless content composable plus a ViewModel, so `-preview=<name>` launches it with sample data (`you`, `profile`, `contacts`, `contacts-sheet`, `blocked`, `security`, `log`, `asha`, `asha-offline`; see `gallery/PreviewScreens.kt`). None of the data layers has been exercised against the live sandbox with a real session (that needs a real Google/Apple sign-in); only the unauthenticated calls were, so a field-name or query-parameter mismatch with the live server is possible and would show up as `AppError.Unexpected` on the first authenticated call.

**Still to do:** platform pieces (avatar picker, audio recorder/player, FCM/APNs token provider, Google on iOS; the contacts reader and its permission prompt are done but untried on a real phone book); the live check; a device run of the R8 release build; a baseline profile; the design's iOS app icon (opaque 1024 px PNG).

**Whose data is whose (done):** every queued write carries its owner's user id (from the access token's `sub`) and the outbox only ever hands out the signed-in person's writes. An explicit sign-out wipes the cache and the queue (`SessionDataCleaner`). A session the server ended keeps them, so the same person signing back in loses nothing, but a different person signing in wipes them first (the data owner id is kept in secure storage). Covered by `OwnershipTest`.

### First live check against the sandbox (Android, Redmi, 2026-10-25)
Verified on a real phone with a real Google account: nonce, then Google's account sheet (Credential Manager with the Web client id), then `POST /v1/auth/social` accepted the token (the Android OAuth client for `com.circaso.app` and the debug SHA-1 is registered, and the backend accepts the Web audience). The server recognised an existing account and answered `REVERIFY_PHONE` with the account's masked number; the app stored the onboarding session and step and resumed at "Add your number" with the masked number as a hint. This exercises the real envelope, headers, DTOs, `SocialSignInRequest`, `SignInResponse`, the onboarding token and the step persistence. **Not yet verified live:** phone start/verify (needs a real SMS; Twilio Verify was noted as unconfirmed), device registration (`POST /v1/devices` with the real hardware key), refresh with the device proof, and every route after sign-in.
Bug found and fixed by this run: the helper that tracks the visible activity (needed by Google's sheet) was created at the first tap instead of at app start, so it never saw an activity and sign-in silently failed with "Couldn't reach Google". It is now created in `initKoinAndroid`. `AndroidSocialSignIn` also logs the failure kind (never tokens or accounts) under the tag `CircasoSignIn`.
Small copy fix pending (needs a reinstall, so not done while someone is using the phone): on Android the masked number's bullets render wide and wrap; put the number on its own line.

### Findings that block or change the integration
- **Sandbox needs real Google/Apple tokens.** The server accepts `dev:` tokens only when `INTEGRATIONS_SOCIAL_PROVIDER=log`; the sandbox runs real verification (`docs/google-apple-signin.md` in the backend repo). End-to-end sign-in needs a Google account on the test phone and the right client ids.
- **Ids (Android resolved: sign-in works with `com.circaso.app` and the debug key).** Bundle/package id is now `com.circaso.app` on both platforms. Google's Android OAuth client must list package `com.circaso.app` and the signing SHA-1; the local debug key's SHA-1 is `8A:7F:7E:81:46:FD:F6:62:B4:D6:A5:30:B5:F7:64:AA:B2:6C:9D:AD`. **iOS Google sign-in** needs the GoogleSignIn SDK and the app's *iOS* OAuth client id (separate from the Web id); not provided yet, so Google is unavailable on iOS. Apple needs the capability and a provisioning profile that includes it.
- **`identity.code_invalid` tries left** is only in the English `message`; the app must not parse messages. Ask for `details.triesLeft` (or similar) on the backend; until then the screen says "That code isn't right" without a count.
- **Access to the backend source tree and the server** was blocked by the session's permission check, so types come from the guide's schema appendix (generated from the OpenAPI spec). Live calls are the check for any mismatch.
- **Design vs backend:** resolved in `SKILL.md` (sign-up follows the backend; recovery phrase and payment PIN arrive with the wallet). Open detail: ID-13 shows only a masked number for "Verify my number" but `POST /v1/auth/phone/start` needs the full number, so a returning person types it.
- **Unlinked legal text and "Lost access?":** the Welcome screen shows the terms line without links (URLs unknown) and omits "Lost access to your account?" until the recovery request flow exists.
- **SMS auto-fill** ("the code fills in by itself") is not built.
- **Cold start:** measured on the Redmi: debug 2.6 to 3.7 s, release without R8 1.1 to 1.9 s. Done since: AndroidX splash screen held until the saved session and theme are read (`StartupGate`, 1.5 s cap, mark inset to fit the system's circular mask, light and dark backgrounds), `profileinstaller` added, and R8 plus resource shrinking enabled for release (builds clean). **Not yet done:** a baseline profile (needs a macrobenchmark module run on a device) and a device run of the R8 release build: Room, Credential Manager and Ktor load some classes by reflection, so run it before any release and re-measure cold start (target well under 1 s).

## Status (2026-10-25)
Built and verified on Android (Redmi) and iOS (simulator):
- Koin (`di/CoreModule.kt`, `initKoinAndroid`, `initKoinIos`), started from `CircasoApplication` and `MainViewController`.
- `core/network`: `CircasoApi` (envelope to `AppResult`), header plugin (`X-Client-Version`, `Idempotency-Key`, bearer), `NetworkHealth`, `NetworkEvents` (sticky `426` flag). 9 unit tests.
- `core/platform` + `core/sync`: `ConnectivityMonitor` (both platforms), `ConnectionState` (OS status + real request outcomes), `ConnectionBanner` (placed in each screen's scaffold `banner` slot).
- Data layer foundation: `core/database` (Room KMP, bundled SQLite driver, schema exported to `shared/schemas`, version 1 = outbox table), `core/datastore` (`AppPreferences`: theme overrides, persisted), `core/sync` `Outbox` + `OutboxDao` (queue with per-action `Idempotency-Key`, survives the app being killed; interrupted sends are requeued at every start). 6 iOS-simulator tests (Room in-memory + DataStore) and on-device persistence check on Android. Both stores sit behind Koin; the platform modules supply the Room builder and the DataStore path.
- Backups: Android `allowBackup="false"`; on iOS the Application Support folder is excluded from backups. Data here is tied to the phone's own keys, so a restored copy would be wrong or unsafe.
- `core/navigation`: Navigation 3 host with a saved back stack; `ForceUpdateScreen` (ID-30) replaces the stack on `426`.

Known gaps in what exists:
- **Cold start is about 2.2 s** on the Redmi in a debug build (`am start -W`), unchanged by moving the stores off the main thread. Measure on a release build before launch; target well under 1 s. A system splash that holds until the first frame is ready (the AndroidX splash-screen library) is the likely fix and needs approval before adding.
- The app waits a few milliseconds for the saved theme before drawing, to avoid a wrong-theme flash; it shows a plain background meanwhile.
- The outbox stores and counts items but nothing sends them yet; the sender arrives with the first feature that queues writes.
- Base URL is the dev server constant (`ApiConfig.DEV_BASE_URL`); a build setting comes with production.
- No reachability probe route is documented, so an "offline because the server is unreachable" state ends on the next real request or "Try again". Ask Pasaka which route is safe to probe.
- (Design chrome, scaffold, sheets and toast are built; see `design-system.md`.) The design's banner says "Showing chats as of 21:05" and, with unsent messages, "2 messages waiting to send" (G3b); the time needs `kotlinx-datetime` (not added) and the count needs the outbox. `OfflineBanner` shows "Showing what is saved on this phone" until both exist. The "Back online · N sent" toast (G4) is not built.
- iOS "Update" button opens the App Store only when Info.plist has `CircasoAppStoreId`; the id is not known yet. Android opens Play via `market://`.
- Design copy still says CoinX; the app says Circaso.

## Live-verified against the sandbox (2026-10-25, Android, Redmi)

Verified on a real phone against `sandbox.circaso.com`: Google sign-in, `/v1/auth/social`, phone verification (SMS), device registration (`POST /v1/devices`, needs `keyAlgorithm` encoded), profile read, `GET /v1/account/security`, security activity log, contacts list, invite link creation, blocked list, handle availability, Asha send + SSE stream + history, voice recording and upload (the dev speech-to-text is a stub that echoes the clip's bytes as text, so the transcript looks like `ftypmp42 isom`; the real one is not built).

Also verified live (via `CircasoNet` log lines from `Diagnostics`, debug builds only): token refresh with the device proof (`refresh with device proof: ok`) and the realtime socket (`socket: open`, then `frame ping`).

Also verified live (2026-10-25): profile save (About line saved and reverted; both `profile.update` outbox items finished `done` on the first attempt, server version 1 to 2) and that the system photo picker opens and cancels cleanly. The avatar upload was then done by the owner on the same phone: the full chain (system picker, scale and JPEG, `upload-url`, `PUT`, confirm) worked, the profile version went 2 to 3, the signed `avatarUrl` is stored, and Coil shows the picture on the You tab. Also seen: after hours idle the server ended the session and the app showed "You were signed out" cleanly; signing in again restored everything.

Not yet verified live: handle claim (permanent, so left to the account owner), contact connect/sync, delete flows, push token.

Server facts learned: `SecurityOverviewResponse.verification` is the identity (KYC) status, `none` today. It is not the phone status; do not show it on the phone row.

Server facts (continued): chat `role` values are `USER` and `ASHA` (capitalised). Compare with `AshaMessage.isFromUser`, never with a lowercase string. Voice is tap to start, tap to stop (60 s cap); the transcript goes into the draft, never sent on its own. iOS audio (`IosAudio.kt`, AVFoundation, `NSMicrophoneUsageDescription`) compiles and is unit-tested through fakes but has not been run on a device or simulator yet.

## Avatars (added 2026-10-25)

Coil 3 (`coil-compose`, `coil-network-ktor3`) draws pictures over the initials avatar, so offline or failed loads still look right. Links are pre-signed for one hour and their query changes per profile load, so images are cached under the link without its query. Coil fetches with its own plain client: our bearer token is never sent to the storage host. Picking uses the system photo picker (`ImagePicker`: Android `PickVisualMedia`, iOS `PHPicker`), which needs no photo permission; the picker scales to 1024 px and re-encodes as JPEG before the upload (`upload-url`, `PUT`, confirm). Photo changes are online-only, never queued. Compiled and unit-tested on both platforms; on Android the whole upload chain is verified on a device.

## Push token (added 2026-10-25)

`PushTokenProvider` (Android: Firebase Cloud Messaging; iOS: none until APNs is set up) feeds `PushTokenRegistrar`, which sends `PUT /v1/devices/current/push-token` once per start whenever a person is signed in and online, and again when Firebase issues a new token (`CircasoMessagingService`). Verified on the Redmi: the backend accepted the token (`push token: registered`). The backend does not send pushes yet ("FCM/APNs sender not built"), so there is nothing to receive; when it does, add the Android 13 `POST_NOTIFICATIONS` permission request at the first moment a notification matters. iOS needs the Push Notifications capability in Xcode, an APNs key on the backend, and an `AppDelegate` hook that hands the device token to `PushTokenRegistrar.onNewToken`.

## iOS signing (2026-10-25)

`iosApp/Configuration/Config.xcconfig` carries the Apple `TEAM_ID`. `iosApp/iosApp/iosApp.entitlements` holds the Sign in with Apple capability (`com.apple.developer.applesignin`); both build configurations point at it with `CODE_SIGN_ENTITLEMENTS`. The server already accepts the bundle id `com.circaso.app` as the Apple audience. iOS Google sign-in still needs the iOS OAuth client id and the GoogleSignIn SDK.

## iOS Google sign-in (2026-10-25)

Google's SDK (GoogleSignIn-iOS 10.1.0, Swift Package Manager) lives on the Swift side: `iosApp/iosApp/GoogleSignInBridge.swift` implements the Kotlin interface `GoogleSignInProvider` (`iosMain/.../GoogleSignInBridge.kt`), installed at launch from `iOSApp.swift`; `IosSocialSignIn` only ever sees an ID token. `Info.plist` holds `GIDClientID` (iOS client), `GIDServerClientID` (the Web client the server accepts, so the token's audience is the Web id) and the reversed-client-id URL scheme. **Do not edit `project.pbxproj` while Xcode is open: Xcode overwrites it from memory.** Sign in with Apple is flaky on the iOS Simulator (Apple's forums call it a known limitation; we saw error 1000 / -7026 until an Apple Account was signed in on the simulator and the app was rebuilt with the project edits made while Xcode was closed). It then worked: see below.

## iOS verified live (2026-10-25, iPhone 17 Pro simulator)

Sign in with Apple (the server accepted the Apple token for `com.circaso.app`; the account then went through phone verification), device registration with the iOS key, the signed-in You tab with live account data, and the realtime socket (`socket: open`, `frame ping`). Apple gave a different name than the Android account, so iOS and Android are two accounts: usable as a pair for contact connect and sync. Not yet run on iOS: Google sign-in (wired, untried), the Asha microphone and playback, the photo picker, profile save.

## Contacts and delete flows verified live (2026-10-25, Android account `@pasaka` and iOS account `@circaso_ios`)

Connect by handle (`Connected with ...`), a handle that does not exist (`We couldn't find anyone with that handle.`), block (the contact leaves the list; confirmation first), Blocked people list, unblock (does not restore the contact), remove from contacts (confirmation says a phone sync will not add them back), reconnect by handle. Removing the only sign-in is refused with plain copy (`identity.provider_last`). **Remove this phone** (`DELETE /v1/devices/current`) signs out, returns to the welcome screen and wipes the local database (profile, contacts, Asha, outbox all empty). Not run live: schedule account deletion (`DELETE /v1/account`), the phone-book sync (it reads the owner's real contacts), linking Apple on Android (not available there).

## Account deletion and sheets (2026-10-25)

Schedule account deletion verified live on the Android account (`DELETE /v1/account`): the sheet says it cannot be undone and that the number and handle are released after 30 days; the confirm button stays disabled until `DELETE` is typed; after the call the app signed out and the local database was empty (profile, contacts, outbox). The screen after it shows the generic "You were signed out" line; a dedicated "Your account is scheduled for deletion" message would be better and is a small follow-up.

Sheets live in the scaffold's overlay layer, which is not inset for the keyboard, so `CircasoSheet` applies `imePadding()` itself: any sheet with a text field (the typed `DELETE`) lifts above the keyboard. Seen broken before this on the delete sheet; the fix is built and unit tests pass, but it has not been seen on a device because the test account is gone.

## Debug preview mode (2026-10-28)

Any screen can be opened with sample data, without signing in, from the launcher arguments: `adb shell am start -n com.circaso.app/com.circaso.MainActivity --es preview <name>` (Android, read by `AndroidLaunchArgs` in `MainActivity`; iOS uses the `-preview=<name>` launch argument). The names live in `gallery/PreviewScreens.kt`: `chats`, `chats-empty`, `chats-offline`, `chat`, `chat-offline`, `chat-failed`, `group`, `asha`, `asha-empty`, `asha-offline`, `voice`, `voice-speaking`, `voice-thinking`, `voice-ended`, plus the older `you`, contacts and auth ones. Every screen keeps a stateless `*Content` composable for this reason: the ViewModel is only glue. Debug builds only.

## Asha live voice (2026-10-29, guide A11)

Voice with Asha is a live conversation, not record-then-transcribe: the mic streams the whole time, Asha answers out loud sentence by sentence, and talking over her stops her at once (the server's `interrupted`). The clip flow (A3 `POST /v1/assistant/voice` + A4 speech) stays only for phones that cannot encode Opus (Android below 10) and for Swahili, which the live service refuses.

- **Protocol** (`feature/assistant/data/remote/LiveVoiceRemote.kt`): one Ktor WebSocket to `wss://<host>/v1/assistant/voice/live`, separate from the realtime socket because it carries binary frames both ways. The first text frame is `auth` with the app token, the agent token, `language` and `chatReading`; then binary Opus up, binary mulaw down, JSON text frames routed on `type` (`ready`, `transcript`, `turn`, `reply`, `reply_done`, `tool_request`, `interrupted`, `error`, `closing`). Unknown or malformed frames are skipped. `LiveVoiceSocketSession` is the only place that knows the frame shapes; `LiveVoiceEvent` is what the rest sees. The `tool_results` frame is built with an explicit `type` because the app's `Json` does not encode defaults (a test caught the frame going out without it).
- **Use case** `TalkToAshaUseCase`: wraps the session so the screen never sees `tool_request` (run through `DeviceToolRunner`, answered on the socket, at most 30 snippets) and sends a fresh `auth` frame whenever the session's access token changes (checked once a minute; `AuthManager` refreshes three minutes before expiry, so the server never sees an expired token).
- **Audio seams** (`core/platform/LiveAudio.kt`): `LiveMicrophone` (Opus packets 16 kHz mono 20 ms ~24 kbit/s, a loudness level for the animation, `muted` keeps the mic open but sends nothing) and `LiveSpeaker` (16-bit PCM in, `flush()` for barge-in). µ-law expansion and the dBFS-to-0..1 level mapping are common Kotlin (`Mulaw`, `AudioLevel`) and unit-tested. The server could switch to `linear16`; the ViewModel handles both.
  - Android: `AudioRecord` (VOICE_COMMUNICATION source) → `MediaCodec` `audio/opus` (software encoder on every phone from Android 10; the codec-config buffer is skipped, each output buffer is one packet) on one thread; `AudioTrack` streaming with a queue. Both take the shared `VoiceRoute`: `MODE_IN_COMMUNICATION` plus the loudspeaker (`setCommunicationDevice` on 12+), restored when the last one lets go, so the platform echo canceller runs and Asha's own voice does not come back as the person interrupting her.
  - iOS: Swift `LiveAudioImpl` behind `LiveAudioBridge` (same pattern as libsignal): one `AVAudioEngine`, input tap → `AVAudioConverter` to 16 kHz → `AVAudioConverter` to `kAudioFormatOpus` at 24 kbit/s; `AVAudioPlayerNode` for playback; session `.playAndRecord` / `.voiceChat` / `.defaultToSpeaker` with `setVoiceProcessingEnabled(true)` for echo cancellation. Levels cross as `KotlinFloat`.
- **Screen** `VoiceScreen` (no design frame exists; it follows the thread header, type scale and call-style round controls): `AshaVoiceLogo` (`feature/assistant/presentation/AshaLogo.kt`, 2026-11-05) is Asha's mark breathing inside rippling wave rings that follow the mic level while listening and her playback level while speaking (a synthetic swell keeps her waves moving between words), an arc while thinking, still with a badge when muted or offline, no motion with reduced motion; colours are theme roles. Below it the call is a chat transcript (`VoiceTranscript`: `turn` = the person's bubble, `reply` sentences append to Asha's, `reply_done` closes it, the live caption is a faded bubble). Mute and End below. Endings map the close reasons: `silence` → "It went quiet" with Talk again; `assistant.quota_exceeded` → voice time used up (the Asha Plus offer goes here when the plan exists); `assistant.language_not_supported` and no Opus → use clips; `unauthorized` → sign in again.
- **Entry**: the Asha composer's trailing button is the waveform (opens `VoiceRoute`) where `LiveMicrophone.supported`, otherwise the clip mic as before. Leaving the screen sends `end` (best effort, 1.5 s) and closes the socket; `onCleared` does the same off the scope.
- **Server side to know**: the dev server's live transcription provider is `integrations.ai.live-stt.provider` (`INTEGRATIONS_AI_LIVE_STT_PROVIDER` in `/opt/circaso/.env`): `workers-ai` is Deepgram Nova-3 fed raw Opus packets; `log`/`fake` treat real audio as silence and hang up after 30 s. Speech out is Deepgram Aura 2, mulaw 8 kHz. The server bills voice seconds for speech only.
- **Reconnect (2026-11-07):** a call that was live and drops without anyone ending it (the socket ends or throws, or closes with no auth reason) goes to `VoicePhase.Reconnecting`: microphone and voice stop, the transcript stays, the cut-off answer is closed. The ViewModel waits on `ConnectionState.status` (no polling), then reopens with `talk(conversationId = …)` after 0.5 / 1.5 / 3 s; the id is the one the saved answers (`reply_done`) carried, and the server's `auth` frame takes `conversationId` so the call carries on in the same Asha conversation (the server's context is that conversation's history). It ends as Offline (Talk Again) after 30 s or 3 failed reopens; never after a hang-up, `closing` (quota, silence, ended…) or an `unauthorized` close. A call that never reached `ready` still ends Offline at once.
- **Talking over her (2026-11-07, second pass; replaces the microphone gate):** the mic streams the whole time, her turn included; there is no loudness gate (with the echo canceller on, a normal voice sat under the old `BargeInLevel` 0.5 gate, so she could not be interrupted). The server decides (one settled non-filler word, not her echo, 300 ms while she plays; backend PR #61). The phone answers first: `VoiceActivity` (energy VAD on the post-AEC level: adaptive floor capped at 0.45, voice = 0.12 over the floor and at least 0.3 for 150 ms, ends after 250 ms) ducks her to 25% over 80 ms (`LiveSpeaker.setVolume`: Android `AudioTrack.setVolume`, iOS `AVAudioPlayerNode.volume`) and sets `ducked`, which shows Listening. `interrupted` flushes as before; no `interrupted` 900 ms after the voice stops ramps her back over 150 ms. Mute undoes the duck and stops the VAD. Tune `VoiceActivity` thresholds on real phones.
- **Chat reconnect (2026-11-07):** in `AshaViewModel`, a question that failed with `AppError.Network` (or whose stream ended with neither `done` nor an error) becomes `WaitingQuestion` (faded bubble, clock, "Asks when you're back online"), keeps the partial answer, and is asked again once `ConnectionState.status` is Online (after a 3 s settle); the history is refreshed first and a new copy of the question there means the server already answered. At most 3 asks in all, then it goes back to the draft. Quota, auth and server errors behave as before. Offline → Online while Asha is open shows "Back online" over the composer for 2 s and reloads usage and history.
- **Follow the newest line (2026-11-07):** `FollowLatest.kt` (chat and voice): reverse-layout lists keep a growing newest item anchored for free; only a new item scrolls (`animateScrollToItem(0)`, a jump with reduced motion). Following stops when the person drags more than 80 dp from the bottom and resumes when they settle back there, tap the round jump button, or send (voice: a new turn taken).
- **Tests** (`commonTest/feature/assistant/LiveVoiceTest.kt`, 16; reconnect, gate: `AshaReconnectTest.kt`, `VoiceViewModelTest`): the frame protocol both ways, tool requests answered on the socket, the token refresh frame, µ-law vectors, the level mapping, and the ViewModel's phases from `ready` to each ending. ViewModel tests clear the ViewModel in `finally`: a leftover `delay` loop on the virtual clock otherwise makes `runTest` spin forever after a failed assertion, hiding the real failure.

## Wallet and payments (2026-10-29, guide W1–W5, W9–W12; designs E1, E4, E5, E7, C3–C7)

A Circle user-controlled wallet: the server makes challenges, the person signs them with a PIN in Circle's SDK on the phone, and the server never holds a key. Built against dev's Circle testnet (Base Sepolia by default, USDC).

- **Layers** (`feature/wallet`): `WalletDtos`/`KtorWalletRemote` (every W route), `WalletRepositoryImpl` (Room `wallet_snapshot` = the last W1 as JSON, `transfer` = every payment this phone knows: W4 results, W5 reads, `transfer.updated`, `payment.received`), use cases, and ViewModels per screen. Reads come from Room; everything that moves money is online-only and never queued (offline shows the last balance "as of" and disables Send). DB v9.
- **The Circle SDK is its own module.** Shared code only knows `WalletSigner` (`execute(session, challengeId)` → Done / Cancelled / Unavailable / Failed) installed through `WalletSignerBridge` at launch, like the other bridges.
  - Android: Gradle module `:circlewallet` holds `circle.programmablewallet:sdk` 1.0.1192 (GitHub Packages; needs `pwsdk.maven.username` and `pwsdk.maven.password` with `read:packages` in `local.properties`, set 2026-10-29). The repository is declared in `settings.gradle.kts`, where the app resolves dependencies (a module-level repository is ignored). Without them the module compiles `src/stub` and signing answers `wallet.sdk_unavailable`, so the rest of the app always builds; with them `src/circle` and the SDK are compiled in. Circaso's colours and words inside Circle's screens come from the `circlepw_*` resources in the module (light and night). `CircasoApplication` installs the signer with the current Activity.
  - iOS: `CircleWalletImpl.swift` behind `#if canImport(CircleProgrammableWalletSDK)`; the SDK is CocoaPods-only. **Installed 2026-10-29** (pod 1.4.5, 23 pods): open `iosApp/iosApp.xcworkspace` from now on. The Podfile's notes matter:
    - Specs come from `https://github.com/circlefin/w3s-ios-sdk.git` (the old `w3s-ios-sdk-podspecs` repo stops at 1.0.4) and the CocoaPods CDN.
    - Pods keep their own deployment targets (raised only below 15.1): Circle's binary is built for 15.1 and can't import dependencies built for a newer iOS.
    - `BUILD_LIBRARY_FOR_DISTRIBUTION = YES` on every pod: without it the app stops at launch with "Symbol not found" in Alamofire.
    - **One GoogleSignIn.** Circle pins GoogleSignIn 7.1.0, which has no `nonce` (the backend requires it). `LocalPods/GoogleSignIn/GoogleSignIn.podspec.json` builds the official 10.1.0 under the 7.1.0 name, and the Swift package was removed, so the app has a single copy. Circle's own Google login (the only code built against 7.1.0) is not used.
    - Simulator builds are arm64 only (`ARCHS=arm64`): the shared Kotlin framework has no x86_64 slice.
    Theme and words in `CirclePWTheme.json` and `CirclePWLocalizable.strings`.
- **Fiat**: the phone's region currency (`localCurrencyCode()`, Kenya → KES) goes as `?currency=`; USDC = 1 USD at the server's rate. Amount maths is decimal-string only (`MinorUnits`, `usdcToFiat`, `fiatToUsdcMinor`): no float ever touches money. "Rates by Exchange Rate API" is credited under the total.
- **Screens**: Wallet tab (E1: balance, Add/Send/Receive/Cash Out, Coins per network, Recent from the local table, Networks link; create and setting-up states before a wallet; hide balances), Receive sheet (E4: @handle to share or copy, or the address per network; QR needs a library and is not added), Pay (C4 → the SDK's C5 → C6: pick a contact or arrive from a chat, type in fiat or USDC, note, W9 check with "Remind them" on `NO_WALLET` and a network pick on `NO_WALLET_ON_CHAIN`, W4 then the SDK, then the card into the chat), Send to a wallet (E5: address with paste, network, amount, warning, W11 check, review, sign), Payment (C7: amount, details, status, network, share receipt), Activity (E7, from the local table until W6 ships), Networks (W12: add a chain runs a challenge). Add money (E2) and Cash out (E6) open sheets that say the rails are coming; Cash out offers "Send to an Exchange".
- **In chats** (superseded 2026-11-04 by "Money in the chat" below: no Pay screen from a chat): a payment is an encrypted `payment` payload (`transferId`, `amountMinor`, `asset`, `blockchain`, the note as `text`) shown as `MoneyCard` on both sides; the receiver reads W5 from the card. The note never reaches the wallet API.
- **Realtime**: `wallet` joined `KnownModules` (its `seq` is kept); `transfer.updated`, `payment.received`, `wallet.ready` (ends the create/add polling at once) and `setup.requested` (the tab's nudge "Amina wants to send you money") are handled in the repository.
- **Previews**: `wallet`, `wallet-create`, `wallet-offline`, `transfer`.
- **Tests** (`commonTest/feature/wallet/WalletTest.kt`): the money maths, and the repository with fakes: refresh with currency, create (challenge → SDK → active), a cancelled PIN sends nothing, pay keeps note and name on the phone only, frames update the copy.
- **Not done**: QR code show/scan (library decision), W6 history beyond what this phone saw, W8 PIN change/restore (Circle challenges exist; no backend route yet), cash in/out. Chamas: see the next section. The Circle SDK code paths (`src/circle`, the Swift `canImport` branch) compile only with the SDK present and are untested until the credentials and pod are in.

## Chamas: group goals and the shared wallet (2026-10-29, guide CH1–CH6; designs D2–D6)

The **treasurer model** (backend decision 2026-10-29, for Pasaka to confirm): a group's goal names a treasurer, and the money sits in that person's own Circle wallet. There is no group-owned wallet, so nothing new has to be custodied. Contributing is a normal W4 payment to the treasurer sent from the chat (`chatId` on the transfer); the backend records it from `wallet.TransferConfirmed`. Paying out is a request any member can make, two admins approve (every admin when the group has fewer), then the treasurer pays it with W4 and links the transfer (CH6). Everything is visible to every member.

- **Layers** (`feature/chama`): `ChamaRemote` (CH1–CH6), `ChamaRepositoryImpl` (Room `chama_snapshot` = the last CH1 per group as JSON; `chama.no_goal` clears it and is not an error), use cases, `ChamaViewModel` (D4) and `ThreadChama` (what a thread shows). Reads come from Room; every write is online-only. Frames `goal.updated`, `contribution.added` and `request.updated` refresh the group they name; `chama` is in `KnownModules`. DB v10.
- **In the thread (D2, D3, D5, D6)**: `GoalStrip` floats under the header in the scaffold's banner slot (glass, animated in when the goal arrives); tapping it opens the group wallet, **Add** opens Pay with the treasurer and `goalName` set; `#500` in a group with a goal does the same with 500 filled. Open requests are `RequestCard`s under the messages (the backend cannot write E2EE messages, so they come from the snapshot, live, keyed by request id). Admins approve or decline in place; the treasurer sees **Pay from the Group**. `GroupIntroContent` (D6) shows once per group, kept in `AppPreferences` flag `chama.intro.<chatId>`; its button is "Got It" because members are added by admins (no invite flow), and the footnote says who holds the money.
- **Group wallet (D4)** `ChamaScreen`: held balance ("held by Otieno for the group"), the goal with days left, Contributions | Payments | Members (who put in what, treasurer and admins marked), **Make a Payment** (a request: member chip or someone else by name and address, amount in fiat or USDC, what for), export statement (plain text, shared). Admins get **Change Goal**; a group without a goal shows the set-up state for admins and "No goal yet" for members. Chat info has a "Group wallet" row.
- **Pay (D3 and D5)**: `PayIntent` carries `goalName`, `requestId` and an `address` for outside payees. A contribution titles "Add to Holiday Fund", shows "Visible to all N members" and the goal "after your contribution", and the card's note reads "Added to Holiday Fund" (the encrypted `payment` payload has no goal field yet, so the card head stays Sent/Received; adding `goal` to the payload is the next payload version). A payout fills the exact USDC amount (`inFiat = false`), then CH6 links the transfer with three retries; if it still fails the Done screen says the money went and keeps **Link to the Request** on screen so the treasurer is never asked to pay twice.
- **The request link (CH6) is never only in memory.** `attachPayment` writes request → (chat, transfer) to `AppPreferences` (`chama.link.<requestId>`, the `PendingLinks` port) before sending, on the app's scope; it is retried on every reconnect and chama frame and removed only when the server has it (or answers `request_closed`, `not_approved`, `transfer_mismatch`). While it waits, the phone shows the request as "Paying", so the treasurer is never offered to pay it twice. The payout amount is locked to what the admins approved, and the Done screen says "Paid" only after the link lands.
- **Goal deadline**: choices (no date, 1, 3, 6 months, 1 year) instead of a calendar, computed with plain civil-date maths (no date library in the project).
- **Contribution cards** in a group with a goal read "Contribution" with "Wanjiru added to Holiday Fund" (or "Paid from the group" from the treasurer) from the group's own copy; the payload needs no goal field.
- **Previews**: `group-goal`, `group-request`, `group-intro`, `chama`, `chama-payments`, `chama-empty`, `chama-offline`, `chama-loading`, `chama-goal-sheet`, `chama-payout`, `chama-payout-outside`.
- **Tests** (`commonTest/feature/chama/ChamaTest.kt`, 13): the repository with fakes (refresh, no_goal, failures keep the copy, frames refresh, writes with idempotency key, ledger mapping, a link that fails stays and shows as paying, a landed link is forgotten), deadlines, payout draft rules, amount helpers.
- **Review**: `.claude/agents/design-reviewer` checks any screen against its frame and the premium bar (fidelity, no shortcuts, fast and fluid); run it before calling a screen done.

## Group wallets (Spaces phase 2, 2026-11-04; guide "Group wallets", W14–W16, CH7–CH9; designs GW1–GW19)

Chamas and groups are one Group; a group may have its own wallet (option A: the owner sets its PIN and is its only
signer, admins approve payouts). The treasurer model above stays only for a server without group wallets.

- **The flag** (`core/flags/GroupWalletsFlag`, a Koin single): **wanted** is on in debug builds and off in release
  builds (`ClientInfo.debugBuild`), overridable on the phone (`AppPreferences.groupWalletsOverride`; in a debug build
  launch with `-group-wallets=on|off|default`, Android `--es group-wallets off`). **Server**: the first answer from W15
  or W16 settles it; `404 request.not_found` turns it off for the run. `enabled` drives the screens; `canSendWalletId`
  guards the wire: `KtorWalletRemote` refuses (`wallet.group_wallets_unavailable`, nothing sent) any `walletId`,
  `payoutRequestId` or `to.groupId` until a group-wallet route has answered, because an older server ignores those
  fields and would pay from the personal wallet. Turn the release default on once production has PR #28.
- **Wallet model** (`feature/wallet`): `Wallet` gains `walletId`, `owner` (`WalletOwner`: person or group, `chatId`,
  `signerUserId`) and `canSign`; `WalletListing` (W15/W16), `GroupWallet` (W15). Every remote call takes
  `walletId` (null = personal, so personal calls are byte-for-byte as before). Room (DB v17, auto-migration): the
  personal W1 stays in `wallet_snapshot`; group wallets' W1 in `wallet_cache` (per `walletId`); W16 in `wallet_list`;
  a group wallet's W6 history in `wallet_activity` (its own table, because a contribution is one transfer that is OUT
  of a personal wallet and IN to the group's); a payout signed on this phone is a `transfer` row with `walletId` set,
  kept out of the personal lists. CH7 per group in `group_money`.
- **One Circle SDK, many Circle users.** Each wallet is its own Circle user. `SdkSession.subject` (`SignSubject`:
  `walletId`, the name the phone gives it, what the challenge does) travels with every session, so `:circlewallet`
  names the wallet on Circle's screens ("Set a PIN for Kilimani Chama", "Approve from Kilimani Chama": Android's
  `CircasoPinTexts`, set before each call) and reports biometrics lost for that wallet only
  (`WalletSignerBridge.reportBiometricsLost(walletId)`; per-wallet flags `biometric_approval.<walletId>` and
  `biometric_lost.<walletId>`). `WalletRepositoryImpl` runs one challenge at a time (a `Mutex`), so one wallet's PIN
  screen never opens over another's. iOS: Circle's texts come from `CirclePWLocalizable.strings` and can't name the
  wallet; the app's own screen behind the PIN does (GW12b's approach). `WalletSigner` is unchanged, so swapping in
  Circle Modular Wallets (option B) later stays inside the module.
- **Biometric approval (2026-11-10).** Circle's own feature, client only (no server call): `WalletSdk.setBiometricsPin`
  (the PIN once, in Circle's screen) stores a key behind the phone's secure hardware for that Circle user. Circle's
  switch (`SettingsManagement.isEnableBiometricsPin`) is set **per call** from `SdkSession.biometrics`: on only for a
  wallet whose `biometric_approval[.<walletId>]` flag is on (and never for a new PIN, `SignPurpose.SetPin`), off
  otherwise, so Circle never shows its own "Allow biometrics" page after a PIN and the app's switch really turns it
  off. On: Circle's enter-PIN screen opens with the system prompt over it; cancel, failure or lockout leave its PIN
  pad underneath ("Use biometrics instead" retries); new fingerprints invalidate the key, Circle drops it and offers
  "Update biometrics" after the PIN (a Skip there reports lost: `BiometricSignals`). Android keeps the
  `SettingsManagement` object passed to `init` (the SDK reads it live); iOS re-runs `setConfiguration` when it
  changes (untested on device). The offer: once per wallet (`biometric_offered.<walletId|personal>`), after wallet
  creation (personal), GW4 (group), or the first payment approved with the PIN (`PinApprovals` →
  `BiometricOfferLayer` at the app root, `BiometricOfferRule`). Settings: You › Security › Payments (personal: Turn On,
  "On" → turn-off sheet, lost state) and group info (per-wallet switch). No new library: `androidx.biometric` comes in
  only as Circle's own dependency.
- **Screens**: New Group (GW1–GW1d) → `GroupWalletSetupRoute` (W14 + PIN → W15 → GW4 biometrics → W13 → CH9 → CH2;
  GW5b/GW5c and a cancelled PIN; Try Again resumes without a second PIN) → the chat. Group info (`GroupMoneyRows`,
  GW6–GW8b; locked wallet when the signer left; leaving blocked while the wallet holds money, §11.9; Change PIN is a
  coming sheet, no W8 route). `TargetRoute` (GW7b, CH2/CH8). **One wallet design** (D-M9, 2026-11-04): the Wallet
  tab's corner switcher (`WalletSwitcher`, a popover) picks the wallet; `WalletViewModel` reads a picked group through
  `GroupWalletReader` (CH7 + W1/W6 by `walletId`) into `WalletUiState.group`, and `WalletContent` draws it with the
  personal screen plus `GroupWalletHooks` (caption, Add / Payout / Receive / Target, target, payout requests).
  `GroupWalletRoute` pushes the same `WalletContent` (via `GroupWalletViewModel`, the same reader) under a bar. Pay (`PayIntent.groupId` contribute, `fromWalletId` + `requestId` pay payout, `fromWalletId`
  alone request payout; Paying from chip and GW10 sheet; GW19 Not sent). In a group, `#1000` is a contribution
  (GW9a2) and the preview's arrow opens the review; in a direct chat it does too when there is another wallet to pay
  from, otherwise the PIN as before (GW11b).
- **Money never queues**: every write here is online-only and disabled offline before the tap; reads come from Room.
- **Fees and caps (2F)**: not built. The seams: `PayUiState.fee` (`PayFee.None | Quoting | Quoted(amountMinor, rateBps)`,
  read by both the chat's money card and the Pay screen through `feeText` / `totalText`, and `canPay` waits while
  `Quoting`), `PrepareTransferResponse.fee`, `PayFailure`.
- **Tests**: `commonTest/feature/wallet/GroupWalletTest.kt` (flag, repository), `commonTest/feature/chama/ChamaTest.kt`
  `GroupMoneyTest`, `androidHostTest/feature/wallet/GroupWalletViewModelsTest.kt` (Pay, set-up, group info rules).
- **Previews**: `gw-*`, `wallet-switcher*` and `wallet-unified-*` in `gallery/GroupWalletPreviews.kt` (listed in
  `project-status.md`). Tests for switching: `androidHostTest/feature/wallet/WalletSwitchTest.kt`.

## Money in the chat (2026-11-04, Pasaka's correction; C3–C6, GW9a/GW9a2/GW9b/GW9d/GW15/GW17–GW19)

Money starts and ends in the chat: **no review page** between a chat and the Circle PIN, and no note field (the note
is the text typed after the amount, `#500 for lunch`).
- **The chat's money card** (`feature/messaging/presentation/ChatMoneyCard.kt`) floats over the thread while a `#`
  amount is in the composer. It is the whole review: who (or the group, with "Target … · 67% after this"), the amount
  in the person's currency (as typed: `#500` is KES 500) and in the coin, Network (`@asset` on its chain), Paying from
  (`PayingFromChip`; the GW10 sheet opens over the chat; a group wallet turns it into Request Payout), For (the note),
  Request (who approved, for a payout), Fee (`PayFee`, "No fee" until 2F), "{name} receives". Pay (or the composer's
  arrow) goes straight to the Circle PIN, which names the wallet. Offline the button is disabled with G3's line.
  A refusal before the chain (W4 error, SDK failure) stays on the card: "Not sent. Nothing was taken from …", Try
  Again (the same Pay, fresh key) and "Ref …".
- **Entry points**: composer `#amount` in a direct chat, in a group with an active wallet (contribution) and in a
  treasurer-model group (contribution to the treasurer); the goal strip's Add and a direct chat profile's Pay put `#`
  in the composer and focus it (`ThreadScreen.moneyPrompt`, `ComposerBar.focusRequester`); an approved request's Pay
  (D5) opens the same card over the group chat (`ThreadScreen.payout`, a `PayViewModel` keyed per request). `PayRoute`
  stays for what isn't a chat: the Wallet tab's Send, the group wallet screen (Add, Payout, a request's Pay), calls
  (H1/H5) and the treasurer-model chama screen.
- **One engine**: `PayViewModel` drives both the card and the Pay screen. `PayIntent.inChat` makes it finish once the
  PIN is done (the card is in the thread; no GW17 wait on the card), and `onQuickReset` readies it for the next `#`
  (Personal again; a payout keeps its approved amount).
- **The card in the thread** (`PaymentMessage`): `ThreadViewModel` follows each card's transfer from Room
  (`ThreadPayments`, `WalletRepository.transfers(ids)`): "Sending" (or "On its way" for the receiver) while
  `AWAITING_SIGNATURE`/`SUBMITTED`, the kind ("Sent", "Contribution", "Paid from …", "Received") only once
  `CONFIRMED`, "Not sent" + "Nothing was taken." + Try Again when `FAILED`/`EXPIRED` (Try Again puts exactly the
  same coin amount back on the money card, `PayViewModel.onRetryExact`, with `@asset` and the typed note). A card
  younger than 30 minutes that this phone hasn't read yet also counts as on its way (`ThreadUiState.paymentPending`),
  so nothing reads Sent/Received early. Pending cards are read with W5 as soon as the thread loads and every 8 s, only
  while the chat is on screen, and never again after a 403/404 (`paymentsUnreadable`). The head crossfades and my
  latest card's head is a polite live region. C6's chips show only once it has arrived.
- **Fitting the screen**: the card is capped at the room between the header and the composer; who, how much and Pay
  stay pinned and the rows scroll (large text, small phone, keyboard up). A failure is GW19's critical banner; the
  foot carries the reference and "1 USDC = KES …". A request's Pay reopened while its payment is on its way or
  confirmed is not offered again ("This payout is already on its way").
- **Fiat display rounds to the nearest cent** (`usdcToFiat`), so `#500` (3.875968 USDC, rounded down) reads KES 500 on
  both phones, not 499.99. Display only; the USDC amount is what moves.
- **Tests**: `GroupWalletViewModelsTest.kt` (`GroupWalletPayViewModelTest` chat cases, `ThreadPaymentStatesTest`).
  **Previews**: `chat-pay`, `chat-pay-from`, `chat-pay-signing`, `chat-pay-failed`, `chat-pay-offline`,
  `chat-pay-personal-only`, `chat-contribute`, `chat-payout`, `chat-cards`.

## Group money live (2026-11-04)

Every screen that shows a group's money reads Room (CH7 in `group_money`, the group wallet's W1 in `wallet_cache`, W6
in `wallet_activity`), so live means reloading those copies when money moves:
- **Others' money**: backend PR #30 adds `chama` / `money.updated` `{ chatId, transferId, kind, amount, userId?,
  requestId? }` to every member for each confirmed contribution (target or not) and paid payout. `ChamaRepositoryImpl`
  reloads CH7 on it (as on `contribution.added`, `request.updated`, `group.updated`); `GroupMoneyLive.onChamaFrame`
  then reloads the group wallet's W1 and W6 (`money.updated`, `contribution.added`, `request.updated` PAID).
  Until PR #30 is deployed, a contribution to a group **without a target** reaches the other members only when they
  reopen the group (the old server sends them nothing).
- **My own money**: `WalletRepository.settled` announces an outgoing transfer the moment the phone learns it is
  `CONFIRMED` or `FAILED` (my `transfer.updated`, or a W5 read); `GroupMoneyLive` reloads that group's CH7, W1 and W6
  at once and again 2.5 s later (the server records the contribution just after the chain confirms it).
- Started at launch next to the realtime dispatcher (`initKoin`). Tests: `GroupMoneyLiveTest`,
  `WalletTest.myPaymentReachingTheChainIsAnnouncedOnce`.

## Phase 1 design gaps closed in the app (2026-10-29)

- **ID-23 hold banner** (`HoldBanner`): after a restore, the Chats tab banner says payments are paused and for how long, with a sheet explaining why.
- **A10 Meet Asha** (`MeetAshaSheet`): once, on the first Chats open, with a sample card and what Asha can do.
- **F4 Notifications** (`NotificationsScreen`): built locally from what the phone already knows (payments received, requests, security events), with a seen marker in `AppPreferences`; the backend has no inbox route yet.
- **ID-28b delete blocked**: deleting the account with money in the wallet opens a sheet that sends the person to Send Money first.
- **C6 quick replies**: "Say Thanks" and "View Details" chips under the latest incoming payment card.
- **Still waiting on decisions or vendors**: calls (H1–H6, Cloudflare Realtime), support (SP01–SP06: a support account), add money / KYC / cash out (E2, E3, E6: partners), the website (W1/W2), business groups (D7), QR codes (E4, library).

## QR codes (2026-10-29, design E4/E5)

- Drawing: `qrose` 1.3.1 in shared code (`designsystem/components/QrCode.kt`): always dark on white inside the design's raised tile, so scanners read it in every theme. Approved by Pasaka as "qrose 1.0.x"; 1.3.1 is the release that works with Compose 1.12.
- Payloads (`core/qr/Qr.kt`): "On Circaso" codes are `circaso://pay/@handle`; wallet codes are the bare address. `QrPayload.read` also accepts EIP-681 / `solana:` URIs and `@handle`.
- Scanning: `QrScanner` → Android `play-services-code-scanner` 16.1.0 (Google's ML Kit UI, no camera permission), iOS VisionKit `DataScannerViewController` through `QrScannerBridge` (`QrScannerImpl.swift`, `NSCameraUsageDescription`). Used by Send to a Wallet (address) and the Pay person picker (a Circaso code → W/handle lookup → pay).

## Support (2026-10-29, designs SP01–SP06)

- A chat with Circaso's own account (`Support.Handle = "circaso"`): Pasaka must create that account; until then Help says support isn't set up. No backend module. Chats with it show "Circaso Support · Verified", SP02's quick replies ("Yes, please", "Call me instead", "That's all"), and SP05's "Did we sort it out?" (the answer goes to the chat as a message).
- SP01 Help (You → Help): Chat with us (a topic picked first becomes the opening message), Call us (a voice call to the support chat), Ask Asha, your conversation with support, the "never ask for your PIN" note.
- SP06 is the recovery case (guide I15), reached from "This number already has a Circaso account" → "Lost Access to That Account?": phone, email, what happened → "Case CX-xxxxx is open; we'll email you". The backend needs the onboarding scope, which the person has at that point.

## Calls (2026-10-29, designs H1–H5; backend `calls`, guide CL1–CL8, PR #4)

- Media through Cloudflare Realtime (SFU). Encrypted in transit, not end to end, so the screens say "Secure call", never "end-to-end encrypted".
- `core/calls/CallEngine`: Android `stream-webrtc-android` 1.3.10 (`AndroidCallEngine`), iOS `stasel/WebRTC` 155.0.0 via SPM (`CallEngineImpl.swift` through `CallEngineBridge`). Audio and video tracks are both pushed at join; the camera only runs while on, so voice can turn into video without renegotiating. `CallVideo` is the platform renderer tile.
- `feature/calls/CallController` (app-wide singleton): CL1 → engine offer → CL3 → answer; every joined person's tracks pulled once (CL4) and the SFU's offer answered (CL5); `calls` frames (`call.ringing`, `call.updated`, `call.ended`) keep state. ICE servers come only from CL1–CL3 responses (frames carry none). One call at a time; a second ring while busy is ignored.
- UI: `CallOverlay` sits above navigation (`AppNavigation.CallLayer`): H1 incoming, H2 voice with the newest money in the chat, H3 video with self-view, H4 group grid with the chama goal live, H5 contribute presets (Pay opens with the call minimised to a pill). Chat headers have voice and video buttons.
- Background ringing: Android shows its own high-priority call notification from the data push (`AndroidCallNotifier`, channel "Calls"); tapping opens the answer screen. iOS rings only in the app until CallKit + PushKit (VoIP push) are added; the backend logged that as an open question.
- Backend env for the sandbox: `INTEGRATIONS_CALLS_PROVIDER=cloudflare`, `CLOUDFLARE_REALTIME_APP_ID`, `CLOUDFLARE_REALTIME_APP_TOKEN`, optional `CLOUDFLARE_TURN_KEY_ID` / `CLOUDFLARE_TURN_KEY_TOKEN`.
- Not built: H2's "Add people" (no add-to-call route), H6 business live (business groups deferred).
- **Swap and spotlight (2026-11-09, local to each phone):** H3's small picture taps to swap with the big one and drags
  to any corner (`DuoStage`); a group tile taps into a spotlight with the others in a strip (under it upright, beside it
  when wider than tall; `FittedTiles(spotlight=)`), tap again for the grid. Tiles move as transforms only
  (`CallStage.TileMotion`, FLIP), so video surfaces are never rebuilt; Android draws call video on a TextureView
  (`CallTextureView`) so it can be clipped, layered and scaled. Pure rules in `CallStage.kt`, tests `CallStageTest`.
- **Screen sharing (2026-11-09):** `CallEngine.setScreenShare` swaps what feeds the one video track (no
  renegotiation); CL8 carries `screen` (backend PR #84) so viewers fit it and label it. Android: consent
  (`ScreenCaptureBridge`) → `CallService.startProjection()` adds the mediaProjection type to the running service →
  `ScreenCapturerAndroid` at ~720p/15 fps; the system's Stop flows back through `screenSharing`. iOS: ReplayKit
  broadcast extension sources in `iosApp/CircasoBroadcast` (not yet a target; its Xcode steps are in its
  SampleHandler.swift) and `ScreenShareImpl.swift`; Share screen stays hidden on iOS until the extension is embedded.
  Play Console needs the media projection foreground-service declaration before a production release.

## Circle social login removed (2026-10-29)

Pasaka asked for Circle's social login to go. Android: the SDK's Facebook dependency is excluded and its manifest entries removed (`circlewallet/src/circle/AndroidManifest.xml`); the SDK code never references it. iOS: Circle's prebuilt binary hard-links Google Sign-In and Facebook Login, so both must ship or the app can't start; Facebook's SDK is switched off in Info.plist (no app id, no auto-init, no event logging, no advertising id).

## Spaces core (phase 3, 2026-11-05; references/spaces-api.md SP1-SP37, W18-W19; frames SD-SW)

> **Removed from the app on 2026-11-05** (Spaces stopped; tag `archive/spaces-v1`; see `spaces-plan.md` → "STOPPED
> 2026-11-05"). None of the code below exists on `main` any more; this section is kept as the map for a restore.
> Room v20 dropped the spaces tables. Moments are now in Chats: see "Moments in Chats" below.

- **Flag** (`core/flags/SpacesFlag`, a Koin single beside `GroupWalletsFlag`): wanted on in debug, off in release, overridable (`AppPreferences.spacesOverride`, `-spaces=` launch argument). `KtorSpacesRemote` learns from every answer: `404 request.not_found` turns spaces off for the run, any `spaces.*` answer proves the module; while off nothing is sent (`spaces.unavailable`).
- **Data** (`feature/spaces/data`): `SpacesDtos`/`KtorSpacesRemote` (every SP route, each write with its own `Idempotency-Key`; SP29 uses the `clientPostId`), `SpacesRepositoryImpl` over Room v18 (auto-migration 17->18): `space` (SP3 JSON per space, offline "as of"), `space_mine` (SP7), `space_post` (keyed by my `clientPostId` for my posts, the server `postId` for others, so a queued post becomes the server copy in place; `state` sent/waiting/failed, `pendingEmoji` for a queued reaction; 300 sent posts kept per space), `space_invite` (SP20), `space_receipt` (a signed membership waiting for its receipt), `space_meta`. Outbox types `space.post` and `space.reaction`; everything else online-only. `chat.viaSpaceId` (M4 `viaSpaceId`, `ChatsRepository.openViaSpace`): the private chat with an owner is its own direct chat; peer lookups that mean "the" chat with someone skip it.
- **Frames** (`spaces` in `KnownModules`, `RealtimeDispatcher.onSpacesFrame`): `space.post_new`/`post_updated` write `space_post` and patch `space_mine` (unread counts skip the chat on screen, `setOpen`); `space.updated`/`price_changed`/`membership_updated` re-read SP3 (and SP7); `membership_updated` JOINED/RENEWED posts the stored receipt into the private chat (SM2); invites, requests and reports become `SpaceEvent`s for toasts and refreshes; `resync` reloads SP7 and SP20.
- **Money**: `PayViewModel` stays the one engine. `PayIntent.spaceId` pays a space (`to: { spaceId }`, from its private chat); with `spacePaymentId` (SP13) it is a membership: exact USDC, Personal fixed, no switching. Both are quoted by W17 at the space rate and W4 carries the `quoteId`. A membership exists only once `space.membership_updated` says so. Space wallets: `WalletRepository.startSpaceWallet/awaitSpaceWallet/spaceWallet` (W18/W19, `SignSubject.space`, one challenge at a time); `NamedWallet.kind == Space` signs as the space and never turns into Request Payout; `nameWallets` names SPACE wallets from the spaces on the phone; space counterparties in W6 rows are stored as `space:{id}` in `wallet_activity.counterpartGroupId`.
- **Presentation** (`feature/spaces/presentation`): shared `SpaceParts.kt` (tile, default token cover, role badge, anchored `SpacePopover` with `PopoverItem`/`PopoverConfirm` - spaces never use bottom sheets for menus - money words, system lines) and `SpacePostParts.kt`; areas `discover/` (tab sections, search, suggested, Chats invite cards and Spaces filter), `page/` (space page, `JoinCard`, `MembershipBanner`), `create/` (create steps, wallet set-up, settings, the pushed space wallet), `chat/` (space chat, private-chat parts, members, reports, invites). Each area has its own Koin module (`spacesViewModels`) and preview function (`gallery/Spaces*Previews.kt`). Navigation: routes in `Routes.kt`, one `SpacesNav` built in `AppNavigation` over the back stack.
- **Tests**: `commonTest/feature/spaces/` with `testing/SpacesFakes.kt` (`FakeSpacesDao`, `FakeSpacesRemote`, `SpaceSamples`).

## Moments in Chats (2026-11-05, after Spaces stopped)

- **Where:** the Chats tab's filter row is All, Unread, Groups, **Moments** (`ChatFilter.Moments`). Picking Moments
  swaps the list's rows for the Moments list in the same `LazyColumn` (`feature/status/presentation/MomentsList.kt`,
  `LazyListScope.momentsList`); the brand, search and chips stay, nothing navigates. The search narrows people by name.
- **State:** `MomentsViewModel` / `MomentsUiState` (was `SpacesViewModel`), created with the Home entry so the chip's
  dot and the chat rings are right at launch. Sections are derived in the state: `recent` (unseen, not muted, newest
  first), `viewed`, `mutedRings` (folded until opened; `rememberSaveable` in `ChatsContent`), `hasUnseen` (the chip's
  dot), `unseenPosters` (rings in the chat list), `myViews`, `myLatestAt`. Tests: `MomentsStateTest`.
- **Rows:** "My moment" first (Add / Seen by N · time / Posting n% with the arc / Waiting / Not posted; the + badge always
  opens the composer), a failed post's Retry/Delete card under it, then Recent, Viewed, Muted. Rings are drawn outside
  the 44 dp avatar (`Modifier.momentRing`) so ringed and plain photos line up; unseen is `colors.action`, seen
  `fillStrong` (the palette is monochrome, so "coloured" is the action colour; no new token).
- **Chat list:** a direct chat whose person has an unseen moment gets the ring; the photo (its own 44 dp target, "View
  {name}'s moment") opens `MomentViewerRoute(posterId)`, the row still opens the chat.
- **FAB:** with Moments picked, the floating + crossfades to a camera ("New moment") and opens `MomentComposerRoute`.
- **States:** S1d skeleton rows on the very first open; S1c empty with Share a Moment / Find People; S1e offline (the
  Chats banner says "Showing moments saved on this phone"); S1f the quiet "Couldn't check for new moments" line with
  Try Again and the reference. Privacy stays under You → Security → Moments.
- **Previews:** `chats-moments`, `chats-moments-muted`, `chats-moments-empty`, `chats-moments-loading`,
  `chats-moments-offline`, `chats-moments-error`, `chats-moments-posting`, `chats-moments-failed`, `chats-rings`
  (`gallery/MomentsPreviews.kt`).
