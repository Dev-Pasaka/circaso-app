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

Features, in build order: `auth` (sign-in, phone, device), `profile`, `contacts` (+connections, invites, blocks), `assistant` (Asha), `account` (security, recovery). Later: chats, wallet, calls (flagged off until the backend exists). Since then: `messaging`, `wallet`, `chama`, `calls`, `status` (Moments), `support`, `share`, `vault`, `ramp` (Add money, 2026-11-22; identity verification, 2026-11-23; the profile's Verification row and screen, 2026-11-23).

Vendor SDKs live outside `shared`, each behind a seam installed at launch: Circle's wallet SDK in `:circlewallet` /
`CircleWalletImpl.swift` (`WalletSignerBridge`), Didit's identity verification SDK in `:diditkyc` / `DiditKycImpl.swift`
(`IdentityVerifierBridge`).

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
- **iOS build notes:** build the simulator with `ARCHS=arm64` (`generic/platform=iOS Simulator` alone asks for x86_64, which the KMP framework does not have). In `SignalBridgeApi`, every method is `@Throws` and its Swift implementation must be `throws`; `NSData` parameters arrive in Swift as `Data`; method names must not start with an ObjC selector family (`new`, `init`, `copy`, `alloc`, `mutableCopy`) and must not repeat a name from another exported interface with a different signature, or Kotlin/Native mangles them (`doNewIdentity`, `sha256(bytes_:)`). Check `shared/build/xcode-frameworks/.../Shared.h` when Swift says the type does not conform. **`pod install` and onnxruntime (2026-11-24):** `onnxruntime-c` downloads from
  `https://download.onnxruntime.ai/pod-archive-onnxruntime-c-<v>.zip`, which is a CNAME to Microsoft's retired
  `onnxruntimepackages.azureedge.net` CDN: the server presents a `*.azureedge.net` certificate (curl: "no alternative
  certificate subject name matches") and the azureedge host itself answers 404. Not a proxy or local cert problem, and
  never fixed by turning TLS checks off. Work around it with a copy already on disk: copy a checkout's `iosApp/Pods`
  (it holds `Pods/onnxruntime-c` 1.30.0) into the new worktree before `pod install`; CocoaPods keeps installed pods
  whose version is unchanged and downloads only what is new. With no copy anywhere, the pod needs a host that serves
  the archive under a valid certificate (Microsoft's fix, or a podspec pointing at another source).
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
  - *Push (Android):* `CircasoMessagingService.onMessageReceived` pulls and decrypts the inbox within FCM's window and posts the real sender and text (`AndroidChatNotifications`, channel "Messages"); `POST_NOTIFICATIONS` is asked on the Chats tab, not at launch; the open thread suppresses its own chat. iOS shows the server's placeholder alert until the Notification Service Extension (M7). Since 2026-11-13 chat and money notifications are conversation notifications (MessagingStyle, the sender's round photo from Coil's disk cache or Avatar-style initials, a long-lived conversation shortcut per chat so the photo is the icon on Android 11+, large-icon fallback for MIUI, saved names, `MessagePreview` media lines, Reply and Mark as read in `ChatNotificationActionReceiver`); group money shows the sender with no group shortcut. Privacy on: the generic words, no photo, no shortcut; sign-out clears them.
  - *Push (iOS, 2026-11-13):* FCM token (PushNotificationsImpl.swift); every alert is mutable and a message push carries `senderId`, `sentAt` and the ciphertext (backend #105). The Notification Service Extension (`iosApp/NotificationService`, Swift + LibSignalClient only, App Group `group.com.circaso.app`, Keychain group `$(AppIdentifierPrefix)com.circaso.app`, Communication Notifications) opens the ciphertext in memory against `NotificationSnapshot` (written sealed to the App Group by `NotificationSnapshotWriter` each time the app leaves the screen: Signal records, people with saved names, photo files, fiat) and words it with `Wording.swift`, a mirror of `NotificationText`/`MessagePreview`/`MoneyWording` (change both). The sender's photo shows via `INSendMessageIntent`. Privacy is read from the App Group defaults; `notificationPrivacySupported = true` on iOS. `IosChatNotifications` clears a chat's notifications (thread = chatId) and everything plus donated intents on privacy on and sign-out.
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
- Base URL is the constant `ApiConfig.BASE_URL`, `https://api.circaso.com` since 2026-11-18 (the same server as `sandbox.circaso.com`, which keeps working); a build setting comes with production.
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
- **Screens**: Wallet tab (E1: balance, Add/Send/Receive/Cash Out, Coins per network, Recent from the local table, Networks link; create and setting-up states before a wallet; hide balances), Receive sheet (E4: @handle to share or copy, or the address per network; QR needs a library and is not added), Pay (C4 → the SDK's C5 → C6: pick a contact or arrive from a chat, type in fiat or USDC, note, W9 check with "Remind them" on `NO_WALLET` and a network pick on `NO_WALLET_ON_CHAIN`, W4 then the SDK, then the card into the chat), Send to a wallet (E5: address with paste, network, amount, warning, W11 check, review, sign; rebuilt 2026-11-24, see "Send to a Wallet, rebuilt"), Payment (C7: amount, details, status, network, share receipt), Activity (E7, from the local table until W6 ships), Networks (W12: add a chain runs a challenge). Add money (E2) and Cash out (E6) open sheets that say the rails are coming; Cash out offers "Send to an Exchange".
- **In chats** (superseded 2026-11-04 by "Money in the chat" below: no Pay screen from a chat): a payment is an encrypted `payment` payload (`transferId`, `amountMinor`, `asset`, `blockchain`, the note as `text`) shown as `MoneyCard` on both sides; the receiver reads W5 from the card. The note never reaches the wallet API.
- **Realtime**: `wallet` joined `KnownModules` (its `seq` is kept); `transfer.updated`, `payment.received`, `wallet.ready` (ends the create/add polling at once) and `setup.requested` (the tab's nudge "Amina wants to send you money") are handled in the repository.
- **Previews**: `wallet`, `wallet-create`, `wallet-offline`, `transfer`.
- **Tests** (`commonTest/feature/wallet/WalletTest.kt`): the money maths, and the repository with fakes: refresh with currency, create (challenge → SDK → active), a cancelled PIN sends nothing, pay keeps note and name on the phone only, frames update the copy.
- **Not done**: QR code show/scan (library decision), W6 history beyond what this phone saw, W8 PIN change/restore (Circle challenges exist; no backend route yet), cash in/out. Chamas: see the next section. The Circle SDK code paths (`src/circle`, the Swift `canImport` branch) compile only with the SDK present and are untested until the credentials and pod are in.

## Add money: the ramp (2026-11-22, guide "Ramp" R1–R6; designs E2, E3 and the "Added from M-Pesa" row of E1/E7)

Buying USDC with M-Pesa from a partner merchant the server picks (the app never lists merchants). Built on backend
`origin/main` `73914b85` (PR #128). **Class: Money** (offline-first.md): online-only, never queued, never optimistic.

- **Layers** (`feature/ramp`): `RampDtos`/`KtorRampRemote` (R1–R6 through `walletCall`, so every call carries
  `X-Wallet-Network` from the wallet's own gate), `RampRepositoryImpl`, one use case per action, `AddMoneyViewModel` +
  `AddMoneyScreen` (`AddMoneyContent` is stateless). No Room table: an order is whatever the server last said. Kept on
  the phone: the person's own number (`SecureRampPhoneStore`, secure storage under `ramp.phone.<userId>`) and, per
  network, which transaction hashes were top-ups (`text.wallet.ramp.topups.<NETWORK>`, wiped at sign-out with the
  other `text.wallet.*` settings). DI: `di/RampModule.kt`.
- **The network** is pinned when the screen opens (`PinnedNetwork`, like Send to a wallet): the quote, the prompt and
  the order are all that network's whatever the Wallet tab shows later; an order read back says its own network and
  the flow follows it. The Testnet strip shows from the pin.
- **Flow:** `AddMoneyRoute(orderId?)` from the Wallet tab's **Add** button and the title bar's **+** (personal wallet
  only; with a group wallet on screen the old sheet still answers). R1 → a gate, the open order (resume), or the
  amount → R2 (whole shillings; the fee is inside what is paid) → review with a 60 s countdown taken from `expiresAt`
  but never more than a minute (a wrong phone clock) → R3 → the order's status.
- **Idempotency:** one key per action, the same on a retry that got no definite answer (network, timeout, any `5xx`
  such as `service.unavailable`, an unreadable answer, `409 request.in_progress`), a new one only after a definite
  refusal (another `4xx`: the server keeps the first answer for a day, so a refused attempt retried under its old key
  would only repeat the refusal). R3 with the same quote returns the same order and no second prompt.
- **Following the order:** `ramp` is in `KnownModules` (its `seq` is kept and sent in `last`, so frames are replayed
  after a reconnect; `resync` makes the screen read its order again). `order.updated` moves the state at once and the
  order is then read (R4), because the frame has no fee or hash. Beside it R4 is read every 4 s while the order is
  not settled and the phone is online. The frames keep being heard after an order ends, because a payment that lands
  after the request timed out moves it again (`EXPIRED` → `PAID`), and it is then followed anew. `COMPLETED`
  refreshes the wallet (W1) once per order, on the order's network.
- **Ending words** key on the state, plus `failureReason` only to decide whether "nothing was taken" may be said
  (`payment_failed`, `cancelled`, `expired`, or none, and the order never showed as paid); any other reason gets the
  careful wording with the order id as the reference, and its buttons are **Contact Support** and Done, never Try
  Again (which would invite paying twice). `REVIEW` is the same. An order that can't be read (opened from a push, or
  the open order) never says "nothing was sent": it exists and may be paid.
- **Design review (2026-11-22, `design-reviewer`):** fixed the same day: the amount field's caret with grouped digits
  (now a `VisualTransformation`), "nothing was sent" over an order that failed to load, Try Again where money may have
  gone, keys rotating on `5xx`, the chips wrapping, digits capped to the largest amount allowed, E3's footnote, the
  group wallet's "+" sheet.
- **Polish pass (2026-11-22, branch `feat/add-money-polish`), closing that review's open list:**
  - **Files:** `AddMoneyScreen.kt` (scaffold, pages, gates, E3, skeletons, the reference), `AddMoneyAmount.kt` (amount,
    review, the price's clock), `AddMoneyOrder.kt` (the order's status, `orderLook`, `orderStages`, `figureLabels`).
  - **Page frame** (`AddMoneyPageFrame`): what there is to read scrolls and the main button with its footnote is pinned
    under it (E2/E3). The scaffold already pads for the keyboard and the home indicator, so the button sits on top of
    the keyboard. What scrolls fades out as it nears the buttons.
  - **Pages and motion:** `pageOf(state)` names the page; one `AnimatedContent` keyed on it. Amount ↔ Review ↔ Order
    come in from their side over an eighth of the width while the old page fades (`BaseMs` in, `QuickMs` out);
    everything else fades; Reduce Motion is a fade. The leaving page keeps the last state it drew. System back does
    what the bar's does (Review → Amount; nothing while a request is being sent).
  - **Bar:** `CircasoNavBar(close = true)` (a cross, "Close") on E3 and on an order's status; E3 has no title.
  - **First paint:** a skeleton in the arriving page's shape (the amount page, or an order when `loadingOrder`).
  - **Amount:** "KES" on the number's baseline (`alignByBaseline`, also in `AmountDisplay` now); the number steps
    down a size when it wouldn't fit (long amount, large text, narrow phone) inside a line of fixed height; one line
    under it (the error, else "About 15.31 USDC", else the limits). The estimate is from the last price had in this
    visit (`lastQuote`, scaled in proportion): R1 has no rate, so there is none before a first price. An empty amount
    takes focus like Send; a quick amount ticks (haptic) and puts the keyboard away. While a price is being asked the
    amount and chain can't change, and going back drops the ask (`pricing` job).
  - **Review:** "You pay" and the amount; You get (strong) / Rate / Fee; To / From. The price's minute is in the
    footer: a thin line draining with "Price held for 0:42" in tabular digits, amber for the last ten seconds; run
    out, the same line says so and the same button becomes "Get a New Price", so nothing moves.
  - **Order:** the badge keeps its place while the title and paragraph cross-fade (the page eases to the new height);
    four steps (`orderStages`: request sent → M-Pesa confirms → USDC sent → in the wallet), each worded for where it
    stands, marks turning over in place. Landing while watched: a `Confirm` haptic, the four ticks held `MomentMs`,
    then the `MoneyCard` ("Added from M-Pesa"). Figures are labelled for the state (`figureLabels`: You paid / You
    got; Amount / Would have added; M-Pesa request / USDC due). The order id is a row in the figures (first where
    Support will ask for it), tap to copy; error references are tap to copy too ("Reference copied").
  - **Copy:** `ramp.merchant_unavailable` (409) → "We couldn't start this payment right now. Nothing was taken. Please
    try again in a moment." A code with no words of its own never falls back to "Something went wrong on our side":
    opening and pricing say no M-Pesa request was sent; a refused request says nothing was taken; a `5xx` or
    unreadable answer to R3 says "Nothing is taken unless you approve an M-Pesa request on your phone" (it isn't
    certain nothing went out).
  - **E3:** the canvas's ID-card and face-scan glyphs (`CircasoIcon.IdCard`, `FaceScan`); "Check your phone" uses
    `Handset` (a phone, not the call glyph). Its "about 3 minutes" and "verification partner" lines are reworded to
    what is true today ("A few minutes, once"; "Your documents are encrypted…"), and the disabled button is "Coming Soon".
  - **Wallet:** a top-up's payment detail (C7) reads "Added from M-Pesa", From "M-Pesa +254 7•• ••• 678", with the M
    disc; the row's fallback sub line is "To your wallet"; the Add button reads "Add money from M-Pesa" to screen readers.
  - **Second review, fixed:** busy buttons keeping their colours, a price ask that could be overtaken, the order
    footer cutting, the story never finishing, present-tense figures on ended orders, the skeleton's shape, the hero
    paragraph changing size. **Still open:** the success haptic has no counterpart on Send's Done; Android 13+ shows its
    own "copied" beside the toast (as on the payment detail); the top-up's payment detail has no shillings paid or
    order reference (the transfer doesn't carry them); E3's sections are 20 dp apart (the frame: 28); the toast path
    for `state.error` is unused; high-contrast themes, Android and a small phone were not looked at on a device.
- **The person's number.** R3 wants the account's verified number in E.164 and the server only returns it masked. The
  review shows the masked number; a phone that doesn't hold the full one asks for it once ("Your M-Pesa number"),
  checks its last three digits against the mask before anything is sent, and keeps it after the server accepts it.
  `ramp.phone_not_yours` forgets it and asks again. It is never logged (`StartOnRampRequest.toString()` hides it).
- **Push:** `kind: "ramp.order"` → `NotificationDestination.RampOrder(orderId)` → the Wallet tab with the order's
  status on top (`NotificationRoutes`, both platforms; the alert is the server's own words, so the iPhone extension's
  `Wording.swift` leaves it as it is, privacy on or off).
- **"Added from M-Pesa":** `LocalMpesaTopUps` (hash → masked number, provided in `AppNavigation`) lets `TransferRow`
  title an incoming payment whose hash is a completed order's. It is filled when an order completes on this phone
  and from R5 after each R1.
- **Error copy:** every `ramp.*` code the app can receive is in `ErrorMessages` (test: `RampWireTest`).
- **Previews:** `add-money`, `-empty`, `-loading`, `-offline`, `-error`, `-error-offline`, `-low`, `-quote-failed`,
  `-review`, `-review-phone`, `-review-phone-error`, `-review-expired`, `-review-repriced`, `-review-sending`,
  `-review-failed`, `-unavailable`, `-verify`, `-restricted`, `-busy`, `-prompt`, `-prompt-offline`, `-prompt-cancel`,
  `-paid`, `-sending`, `-done`, `-failed`, `-expired`, `-cancelled`, `-review-order`, `-loading-order`, `-estimate`,
  `-max`, `-review-merchant`, `-review-low` (`gallery/AddMoneyPreviews.kt`).
- **Tests:** `commonTest/feature/ramp/RampTest.kt` (repository with a scripted server, the wire shapes, the header and
  keys through a mock engine, the error copy, the push destination) and
  `androidHostTest/feature/ramp/AddMoneyViewModelTest.kt` (happy path, each gate, the amount rules, the price running
  out, keys on retries, the number, every ending, resume, cancel, offline).
- **States with no frame (for a design review):** the loading and load-failed pages; the four gates other than E3
  (not open to you, account paused, no partner online) and E3's disabled "Verification Is Coming Soon"; the amount
  page without E2's live rate (limits line, "Receive on" when there are several chains, fee as a percentage, daily
  limit, amount errors); the whole review page (E2's Rate / Fee / You pay rows moved here with "Continue to M-Pesa",
  plus You get, To, the countdown, "price has run out", "price was updated", the number field, the failure banner);
  every order state (sending the request, Check your phone, M-Pesa confirmed, Sending your USDC, added, didn't go
  through, timed out, cancelled, in review, an unknown state) with its three-step progress list, the offline and
  "couldn't check" notes and the cancel note. E2's **Card** row is left out (no backend). E2's design is one page;
  it is two here because a price is a server call that lasts a minute.
- **Not done:** cash out; the number could be saved at
  phone verification so nobody is asked for it; an orders history screen (R5 is only read for the wallet labels).

### Add money motion: the M-Pesa card becoming USDC, and the chime (2026-11-23, branch `feat/add-money-motion`)

Pasaka asked for buying to *feel* like converting cash: "the green card with the M-Pesa logo disintegrating, changing
into a silver USDC-blue card", the USDC amount on it, and a chime whenever it lands unless the sound is off. No frame
in the canvas; these are deliberate departures from E2's plain "Pay with" row and the order page's badge.

- **Brand colours** (`RampBrand` in `AddMoneyCards.kt`): M-Pesa green `#0F8039→#0A6A30`, USDC blue `#2775CA`, a
  brushed silver `#F8F9FB/#E3E6EA/#CBD0D6` with `#1C1C1E` ink. They are the brands', the same in every theme, and are
  never the app's accent: green only as M-Pesa's own card/chip, blue only as USDC's disc and glow. White on the green
  is ≥ 4.5:1 at its lightest corner; ink on silver ≥ 10:1. The surroundings use tokens; edges are a hairline (1.5 dp
  `ink` in high contrast).
- **M-Pesa's mark (replaced 2026-11-23, branch `feat/cash-out-motion`):** Safaricom's own logo from its Brand Toolkit
  (`designsystem/components/MpesaMarks.kt`: `MpesaLogo`, the reversed variant on the green card, method card and chip;
  `MpesaTag`, `MpesaDisc` and `MpesaDetailRow` on white elsewhere, shared by Add Money, Cash Out and the wallet).
  Source, licence and how the files are made: `brand-assets.md` → "M-Pesa's logo". The typed "M-PESA" wordmark is gone.
- **Amount and review:** `MpesaMethodCard` (the green card with the wordmark, the masked number, "The M-Pesa request
  goes here", a tick; `selected` semantics) replaces the "Pay with" row; `MpesaChip` sits under "You pay" on the
  review.
- **Order hero (`ConversionCard`)**, replacing the badge; the four steps stay under it as a compact caption
  (`Stages(compact = true)`), and an ending other than success keeps a small `SheetBadge` under the card.
  `cardStageOf(state, everPaid)`: Reserved/PaymentRequested → **Waiting** (green card, KES, masked number; a 2 pt
  breath and a faint sheen every 2.7 s) · Paid/Releasing → **Converting** (the green card comes apart into a 22-column
  grid of squares that drift up-right, shrink and fade with a fleck of dust each, left to right, while the silver USDC
  card forms behind them from specks; then a slow sheen every 3.6 s while the USDC is sent) · Completed → **Landed**
  (it settles from 96.5 % with one sheen pass, the `Confirm` haptic and the chime on the same frame) ·
  Failed/Expired/Cancelled unpaid → **Ended** (back to green if needed, colour drains to grey under a light veil) ·
  Review, an ending after payment, or an unknown state once paid → **Held** (a conversion under way finishes, then the
  card holds, undimmed, with the ending's badge under it; never back to green, which would say the shillings went home).
- **Timing:** conversion `ConvertMs` = Moment + Base + Quick = 1330 ms (linear progress, per-piece cubic easing; the
  last silver square lands exactly at 1), sheen `SheenMs` = 900 ms; watched end to end the landing is ~1.3 s after
  "paid" plus the settle. Interrupted states carry on from where the progress is; an order first seen paid or
  completed shows the silver card at once.
- **How it's drawn:** each face is a normal composable with its own `graphicsLayer` (recorded once), recorded into a
  `GraphicsLayer`; both are copied to bitmaps **ahead, while the green card waits** (and again if their words change),
  so the conversion starts on the frame "paid" arrives. Each copy is held as one `ShaderBrush(ImageShader(bitmap))`;
  `CardParticles` precomputes the grid and every piece's timing, drift and arrival in arrays; a frame only reads them
  and fills each square with `canvas.drawRect` through one `Paint` per face (the image as its shader,
  **anti-aliasing off**, so squares sharing an edge meet exactly at any scale; anti-aliased fills showed a lattice of
  seams) under an inline translate/scale. Arriving squares are exactly home before they finish fading in. **Don't use
  `drawImage(bitmap, srcOffset, …)` per piece:** on iOS (Skiko) every such call turns the whole bitmap into a new Skia
  image, and ~600 a frame stalled the conversion for ~2 s on the simulator; the shader fill runs at 60 fps. While the
  card waits, one square of each kind is drawn unseen (`warm`) so the first real frame doesn't pay for pipeline
  set-up (the first conversion after launch still showed one ~1 s hitch before this; recheck on a device). The copies
  are snapshot state, so a face drawn before they existed is drawn again. Progress is read in the draw phase only;
  the slow loops are composed only while they run. No allocation per frame. The shadow fades as the first pieces
  leave and returns as the silver forms; the card's edge (a hairline, 1.5 dp `ink` in high contrast) is drawn over
  the stack rather than captured in the faces (it shattered into fragments) and fades the same way. Mid-conversion
  the faces keep the words they were copied with, so an order that skips from waiting to completed doesn't force a new
  copy on its first frame; the silver label then cross-fades to "Added from M-Pesa". An order opened already
  completed waits at 96.5 % until the view model decides, then springs (new) or eases in `QuickMs` (heard before).
  If the stored list can't be read, the landing counts as new (never left waiting). A dimmed card uses a `BlendMode.Saturation` grey over the face plus a 10 %
  veil of the ground (a `GraphicsLayer.colorFilter` had no effect on iOS).
- **Reduce Motion:** a cross-fade (Base) between the cards; no pieces, breath, sheen or settle. Screen readers hear
  the card in words ("M-Pesa, You pay KES 2,000, from +254 7•• ••• 678" / "USDC on Base, Added from M-Pesa 15.31
  USDC"); the state change is announced by the title's live region, as before.
- **The chime** (`files/payment_chime.wav` and `androidApp/res/raw/payment_chime.wav`, made by
  `scripts/make_payment_chime.py`: E6 then B6, soft bell partials, 0.62 s, 32 kHz mono, 40 KB, about the coins'
  loudness). `PaymentChime` (common interface) → `AndroidPaymentChime` (SoundPool, `USAGE_NOTIFICATION_EVENT`, plays
  only in `RINGER_MODE_NORMAL`) and `IosPaymentChime` (System Sound Services: muted by the Ring/Silent switch, ringer
  volume, never touches the app's `AVAudioSession` that voice notes/calls/Asha set to categories ignoring the switch;
  chosen over an ambient `AVAudioSession` for that reason). DI: platform Koin modules; `NoPaymentChime` elsewhere.
- **When it rings** (`MoneySounds`, `core/platform/PaymentChime.kt`, a `single` in `WalletModule`): the setting is the
  existing **You → Money sound** (`MoneySoundOff`; its sub line now "Coins when money arrives, and a chime when money
  from M-Pesa lands"). The view model decides as soon as the order shows Completed (`armLanding`: heard before? sound
  on? sound loaded) and sets `celebrate` (or `quietLanding` for one heard before: no settle, no chime); the card calls
  `onLanded()` on the settle frame and `ringLanding` plays synchronously, then the order id is written to
  `text.wallet.ramp.chimed` (newest 50, wiped at sign-out). So: watched or opened later from a push/the open order,
  once per order per phone; sound off still counts as heard; failures, review and endings never ring. The chime is
  loaded when Add Money opens. While a **paid** top-up is on its way on screen, and 30 s after the chime, the coins for
  incoming money keep quiet (the same USDC arrives as `payment.received`).
- **Previews:** `add-money-live` (a top-up on a loop, with the real chime), `-converting-early` / `-converting` (mid
  disintegration) / `-converting-late` (frozen frames), `-landing`, and any Add Money preview with the endings `-dark`,
  `-hc`, `-dark-hc`, `-still` (Reduce Motion), e.g. `add-money-live-dark-still`.
- **Tests:** `androidHostTest/feature/ramp/AddMoneyChimeTest.kt` (rings once when the card settles, quiet on
  re-open, setting off, never for failures/review, coins quiet for the top-up's USDC, the cap, the stage mapping and the
  card's words).
- **Not verified:** real-device audio (both platforms), Android at all beyond the build, the silent switch/ringer
  behaviour on hardware, frame timing on a low-end Android.

### The conversion, both ways: one engine (2026-11-23, branch `feat/cash-out-motion`)

Pasaka: "could we have the same animation from card to M-Pesa in reverse when cashing out?" The drawing above now lives
in `CardConversion.kt` as `CardConversion(direction, stage, words, heldOnSecond, celebrate, quiet, onLanded, landingKey,
frozenAt)`; it knows only "the first card" and "the second card". `ConversionDirection.MpesaToUsdc` (Add Money's
`ConversionCard`, unchanged in behaviour) and `UsdcToMpesa` (Cash Out's `SaleCard`) pick which face is which, the dust's
colour (green flecks / silver `#AEB4BC`) and each face's light (`FaceLight`: green band 0.22, loops at 0.45 of it, so the
breath is the old 0.10; silver 0.42, loops 0.55). `CardWords` is now side-neutral (`mpesaLabel/mpesa/phone`,
`usdcLabel/usdc/chain`, both descriptions); both faces' labels cross-fade. New stage `Returned`: back to the first card,
whole and undimmed (a conversion caught mid-way cross-fades back), for money coming home. Faces are copied only once a
changed label has finished cross-fading (`LabelSettleMs`, Base + 2 frames): the simulator showed a sale's next round
breaking up with last round's "You sold". Each face is recorded into its `GraphicsLayer` by its own modifier behind
its own layer (`recorder`), which reads no animation: the breath, the sending sheen and the conversion only redraw the
recording, never record the face again (before, a new display list a frame for as long as an order waited; checked on
iOS that later label changes still show).
A card about to turn with its copies ready keeps the words they were made with (`aboutToTurn`), so a sale whose poll
missed PAYING_OUT (FUNDED straight to COMPLETED) turns on the next frame, then cross-fades to "Received on M-Pesa"
(preview `cash-out-live-fast`). Dimmed cards take a stronger veil on dark ground (0.24, `DimVeilDark`), or a silver
card hardly dims; a landing already heard opens whole, with no settle nudge.

- **Cash Out's stages** (`saleCardStageOf(state, sent || maybeSent)`): AwaitingTransfer, Funded → Waiting (silver,
  "You sell 10.00 USDC", breathing) · PayingOut → Converting (silver breaks up into the green M-Pesa card, "You receive
  KES 1,269" and the masked number; slow sheen while M-Pesa confirms) · Completed → Landed (settle, sheen pass, `Confirm`
  haptic and the chime on one frame; label "Received on M-Pesa") · Refunding/Refunded, and Expired/Cancelled/Failed
  after sending → Returned (silver stays) · the same when W4 got no answer (`maybeSent`) → Held (never promised back) ·
  the same before sending → Ended (silver dims) · Review and unknown → Held. A FAILED sale's figures say "Amount" /
  "Would have received", as the other endings do.
  Opened on a completed sale, the green card shows at once.
- **The page** (`SaleOrderPage`): the card is the hero in place of the badge; a badge under it only for warnings and
  endings (`saleBadge`: not for a plain "send your USDC" wait, never for the payout); the four steps as the compact
  caption, kept until the card has landed; "Cashed out to M-Pesa" `MoneyCard` removed (the green card is the moment;
  the receipt stays in the figures); "To" rows show the logo (`MpesaDetailRow`). The page no longer fires its own haptic.
- **The chime:** `CashOutViewModel(sounds = MoneySounds)` decides as Add Money does (`decideLanding` → `celebrate` /
  `quietLanding`, `onLanded` rings and marks heard; one remembered list for top-ups and sales). `ringLanding(id,
  quietCoins = false)` for a sale: its shillings land on M-Pesa, so no incoming USDC is the sale's and the coins are
  never held back (a refund's USDC plays the coins as any money received).
- **Previews:** `cash-out-live` (a sale on a loop with the real chime), `cash-out-live-fast` (PAYING_OUT missed), `-converting-early/-converting/-converting-late`,
  `-landing`; any Cash Out preview takes `-dark`, `-hc`, `-dark-hc`, `-still`.
- **Tests:** `androidHostTest/feature/ramp/CashOutChimeTest.kt` (rings once on settle, first-seen-completed, re-open quiet,
  sound off, never for refund/expiry/cancel/review/failure, coins not silenced, no player, stage mapping, words, badge).
- **Verified:** Android `assembleDebug`, ramp/wallet/core host tests, iOS simulator build and run (iPhone 18 Pro, iOS
  27.2): conversion recorded twice at ~59–64 fps around the break-up, longest frame gap 47 ms (one dropped frame); frames checked (pieces carry the right words, the label then turns to "Received on M-Pesa"). **Not verified:** on a phone, audio, Android motion.

## Send to a Wallet, rebuilt (2026-11-24, branch `feat/send-to-wallet-redesign`; design E5 and the Add Money family)

Pasaka: the old E5 screen "was made in a hurry"; make it fluid, premium and part of the app. E5 is the only frame;
everything past it follows Add Money / Cash Out (page frame, pinned footer, step slides, the silver card) and HIG.

- **Pages** (`SendToWalletScreen.kt`, one `AnimatedContent` keyed by `sendPageOf`): `NotOwner` (a group member: the
  message page), `Recipient`, `Amount`, `Confirm`. The view model's steps are unchanged (`ExternalStep.Form` holds both
  form pages as `SendPage`; Review, Signing and Done are the confirm page), so the network pinning, W11 → W17 → W4 and
  the retry/idempotency rules are exactly as before.
- **Recipient:** `CircasoTextField` (new `textStyle` and `trailing` slots) in monospace with `AddressGrouping` (spaces
  every four past `0x`, drawn only; the caret maps across them), Paste and Scan inside the field, a line saying what
  kind of address it is (or that it isn't whole yet), the scan's refusal (red) or notice (calm) under it, **Recent**
  (outside addresses this wallet paid on this network from `transfers`, newest first, deduplicated, never Cash Out
  deposits, people or groups; shown while the field is empty; a tap goes straight to the amount) and **Network** as
  rows of the chains that can pay this kind of address (EVM ↔ 0x, Solana ↔ base58) with each balance. Continue needs a
  whole address and such a network (`canContinue`).
- **Amount:** a "To 0x7a3F … 4b00" chip (back to the recipient), the hero entry (Cash Out's), the fiat line or the
  over-balance line in red, Available and **Max** (`onMax`; on review, if W17 says the fee doesn't fit on top, the
  amount becomes its `maxAmountMinor`, is priced again, and a notice says so), the note, E5's warning, fee/they-receive
  rows, Solana's SOL note.
- **Confirm (`SendCard.kt`):** the silver USDC card (Add Money's `RampBrand`/`SilverBrush`) stays in place from Review
  through the PIN to Sent; only what is under it changes. Phases (`sendPhaseOf`): Ready, Approving (a slow breath, a
  pulsing "Approving" mark, "Waiting for your PIN"), Sending ("Sending": lift + one sheen + a light tick, since the
  chain can still refuse it), Delivered (the same with the `Confirm` haptic, a tick), Failed ("Didn't go through":
  saturation grey and the ramp cards' veil, a cross). The card is laid out by the ramp's `CardStack` (the card's shape,
  or taller at large text), its shadow only on light ground, the sheen band made once per size and translated. Everything is `graphicsLayer`/draw-phase on `Animatable`s, interruptible, nothing allocated per
  frame; Reduce Motion keeps only the words. Review: the whole address (`GroupedAddress`: groups of four, first and
  last in ink and semibold, tap to copy, spoken as "starting … and ending …"), Paying from, Network, Fee, Total, They
  receive, Note, the fee line, G2's failure with its reference, the irreversibility warning. The button names the
  amount ("Send 50.00 USDC"). Sent: title by state (live region), three steps (Approved, On the network, Delivered),
  To / Network / Fee, "View on Explorer" once there is a hash (else "When the network has it"), Done and View Payment.
  The view model follows the sent transfer (`follow` = `ObserveTransferUseCase`). The steps' line fills on
  `CircasoSpring.progress()`; "On the network" is ticked once there is a hash. A failed payment shows its reference
  (tap to copy) and Contact Support.
- **Errors:** every message is from `ErrorMessages` by code. W11 saying the address isn't valid sends the person back
  to the recipient page with it under the field; a W11/W17 failure or a limit stays on the amount page as a red banner
  with the reference (`problem`); W4 failures stay on Review (G2) as before; going back while the check runs cancels it.
  Recents exclude Cash Out deposit addresses by `MpesaSales` (and the phone's "M-Pesa" name), so a ramp address is
  never offered again. The keyboard waits for history to load, so it never covers recents.
- **Departures from E5:** two pages instead of one form; no "Coin · USDC" row (USDC is on the card and the amount); the
  form's "Network fee" row shows only on Solana (the fee is priced for exactly this payment on Review, as before).
- **Codes** (`core/qr/Qr.kt` reads, `feature/wallet/domain/ScanResolve.kt` decides): `QrPayload.Scanned.Address` now
  carries `chainId`, `token`, `amountUnits` (EIP-681 `uint256`, exact integer, `2.014e6` allowed), `amount` (Solana Pay),
  `nativeCoin`, `amountUnreadable`; `Scanned.Unsupported(why)` for non-transfer calls, ENS names, Solana Pay
  transaction requests and transfers without a recipient. `resolveScan(scanned, chains, network, whose)` →
  `ScanFill.Fill(address, blockchain, amountText, notice)` or `ScanFill.Refuse(message)`. Chain ids (`ChainIds`): 8453
  BASE, 84532 BASE-SEPOLIA, 137 MATIC, 80002 MATIC-AMOY, 1 ETH, 11155111 ETH-SEPOLIA (plus ARB/AVAX for naming only).
  USDC contracts (`UsdcContracts`, the backend's `CustodyChainsConfig` table) and the wallet's own W1 `tokenAddress`
  both count as USDC. Pasting a payment URI or a pay link into the address field goes through the same path.
  **Pay links (2026-11-24, see "Pay links" below)** are the preferred form and are read first (`PayLinks.read`):
  `Scanned.Address` gains `chainName` and `asset`, and `resolveScan` takes the chain from `chainName` before
  `chainId`/token, so the same rules apply. **Every** EVM address read from a code is EIP-55 checked when mixed case
  (and never the zero address), and every Solana address must decode to a 32-byte key: otherwise
  `Unsupported(Why.BadAddress)`, "That code's address doesn't check out…".

  | Code | Result |
  | --- | --- |
  | `https://links.circaso.com/pay/<to>?chain=<BASE…>&asset=USDC[&amount=12.5]` (or `circaso://pay/<to>?…`) on a chain the wallet is on | address, chain, amount |
  | a pay link without `chain` | address; the person picks among the chains of its kind |
  | a pay link on another host | not read (Unknown) |
  | `ethereum:<usdc>@<id>/transfer?address=<to>&uint256=<n>` on a chain the wallet is on | address, chain, amount (n at the asset's decimals) |
  | the same without `uint256` / without `@id` | address and chain (the contract names the chain) |
  | `ethereum:<to>@<id>` | address and chain; with `value>0` a notice "asks for ETH/POL, not USDC" |
  | `ethereum:<to>` or a bare `0x…` | address; the person picks among the EVM chains |
  | a bare Solana address, `solana:<to>` | address and the wallet's Solana chain; `amount` without `spl-token` → "asks for SOL" notice |
  | `solana:<to>?spl-token=<USDC mint>&amount=<a>` | address, SOL or SOL-DEVNET by the mint, amount |
  | chain of the other network | refused: "That code is for USDC on Base Testnet. This is your wallet on Mainnet: switch to Testnet…" |
  | chain Circaso offers but the wallet isn't on (or still adding) | refused: "…which your wallet isn't on. Add Polygon under Wallet → Networks first." |
  | chain Circaso doesn't offer / unknown id | refused, naming it ("Arbitrum") or its id |
  | token other than USDC (or USDC of another chain with this id) | refused: "asks for a coin other than USDC" |
  | unreadable or too precise amount | the rest filled, "the amount couldn't be read exactly" |
  | other functions, ENS names, `solana:https…`, `circaso://pay/@h`, anything else | refused with what to do |

- **Tests:** `commonTest/core/qr/QrPayloadTest.kt` (15), `commonTest/feature/wallet/ScanResolveTest.kt` (13), and in
  `androidHostTest/feature/wallet/WalletNetworkViewModelsTest.kt` the scan fill, the other-network refusal, paste,
  Continue's rule, recents, Max lowered for the fee, and the sent page following the transfer.
- **Previews:** `send-wallet`, `-address`, `-scanned`, `-scan-refused`, `-solana`, `-loading`, `-offline`, `-amount`,
  `-amount-over`, `-review`, `-signing`, `-failed`, `-sent`, `-delivered`, `-live` (the card's motion on a loop); each
  takes `-dark`, `-hc`, `-dark-hc`, `-still`.
- **Settled (2026-11-24, Pay links):** the Receive code was an EIP-681 request whose first `0x…` is the USDC
  contract; exchange scanners that take the first address would send there. It is now a pay link whose only address
  is the recipient.
- **Not done:** ENS, Solana Pay transaction requests.

## Pay links (2026-11-24, branch `feat/pay-links`; backend PR #148, merchant portal)

Approved by Pasaka: Circaso's payment QR codes are safe for exchanges and other wallets, and Circaso still prefills
everything. One format everywhere (the app's Receive code, the merchant portal's top-up code):
`https://links.circaso.com/pay/<address>?chain=<BASE|BASE-SEPOLIA|MATIC|MATIC-AMOY|ETH|ETH-SEPOLIA|SOL|SOL-DEVNET>&asset=USDC[&amount=<decimal>]`.
Nothing but the recipient is in the path; nothing about the person in the query.

- **Why:** the only `0x…` is the recipient. A naive scanner gets the right address, a strict one refuses the code and
  the person copies the address; nobody can take the token contract.
- **Making codes** (`core/qr/PayLinks.kt`): `PayLinks.of(blockchain, address, amount?)` → the link, or null when the
  chain isn't one Circaso offers or the address doesn't check out (`Addresses`: EIP-55 via a plain-Kotlin
  `Keccak256`, base58 → 32 bytes). `QrPayload.forReceive` falls back to the bare address then. The host is the build's
  share host (`IncomingLinks.addHost` → `PayLinks.useHost`; `links.circaso.com` by default).
- **Receive (E4):** the code is the pay link; under it, centred as the handle tab, "USDC on Base" (a heading) and "Most
  wallets and exchanges can scan this. If one can't, copy the address below. In Circaso, the network and coin fill in
  too." ("Wallets on this test network can scan this…" on testnets: never "any", per the design review); the address is
  `GroupedAddress(spokenInFull = true)` (every group read out, so it can be dictated) with the warning under it.
- **Opened link, wallet not ready:** the view model waits up to 10 s for the wallet, then says "Your wallet isn't ready
  yet…" under the field; it never fills over an address already in the field (a restored screen).
- **Reading:** scan, paste and links from outside all go through `QrPayload.read` → `resolveScan` (table above).
  EIP-681, Solana Pay and plain addresses from other wallets are still read.
- **Opening from outside:** Android App Links on the share host now cover `/pay/` (manifest; `circaso://pay` too, the
  page's Open button), iOS Universal Links through the backend's AASA (`/pay/*` added; the entitlement is domain-wide)
  and the registered `circaso` scheme. `IncomingLinks.open` keeps a pay link in `pendingPay`; once signed in,
  `AppNavigation` opens `SendToWalletRoute(prefill = link)`, and the view model applies it as a scan once the wallet
  has loaded (it needs the chains). `circaso://pay/@handle` stays the "On Circaso" code.
- **Without the app:** the backend's page at `/pay/<address>` (share module): the network in the heading, the address
  in fours with Copy, the warning, Get Circaso / Open in Circaso. Strict: a link whose address doesn't check out, whose
  chain isn't offered or whose coin isn't USDC never shows an address.
- **Tests:** `QrPayloadTest` (pay links, checksums, Solana keys, the receive code's only address), `ScanResolveTest`
  (a pay link's fills and refusals, same rules as EIP-681), `IncomingLinksTest` (pending pay link), and in
  `WalletNetworkViewModelsTest` scan/paste of a pay link and a prefilled send.
- **Not done:** not yet tried with a real exchange scanner or a phone camera; nginx on the server needs the `/pay/`
  location from the backend repo for its designed 404/400 pages.

## Cash out: selling USDC for M-Pesa (2026-11-23, guide "Selling USDC for M-Pesa" R1/R2/R7/W4; design E6's place)

A person sells USDC from their own wallet and the shillings go to **their own verified M-Pesa number**; Circaso's engine
picks the buyer. Built on the backend's `feat/offramp` guide (`bab4ed7c`). **Class: Money**: online-only, never queued,
never optimistic.

- **Layers** (`feature/ramp`): `OffRampDto`, `DepositDto`, `StartOffRampRequest`, `QuoteResponse.providerFee/worth`,
  `OrderResponse.providerFee/deposit/receipt` (`RampDtos`); `KtorRampRemote.sell` (R7); `RampRepository.sellQuote/sell/
  saleSent/saleTransfer/sales`; use cases `QuoteSaleUseCase`, `SellForMpesaUseCase`, `ObserveSalesUseCase`; `SaleWallet`
  (the wallet as a sale needs it: balance on the pinned network, W17, W4; adapted from `WalletRepository` in
  `RampModule`); `CashOutViewModel` + `CashOutScreen.kt` (scaffold, gates, amount, review) + `CashOutOrder.kt`
  (`saleLook`, `saleStages`, `saleFigureLabels`, `refundWords`, the status page). Domain: `RampOrderState` gains
  `AwaitingTransfer`, `Funded`, `PayingOut`, `Refunding`, `Refunded` (Refunded settles; Refunding is followed);
  `RampDirection`; `SaleDeposit`; `OffRampStatus`; `SaleLabel` / `MpesaSales`. `PaymentRequest.rampOrderId` and
  `PrepareTransferRequest.rampOrderId` (W4 and W17; left out of every other payment).
- **Entry:** the Wallet tab's **Cash Out** (`WalletActions.onCashOut` → `CashOutRoute`), personal wallet only (a group
  wallet keeps the old sheet). Always shown, like Add; the flow shows R1's gate. A server from before selling (no
  `offRamp`) or `offRamp.open` false is the closed page ("Cash out isn't open to you yet" with E6's "Send to an
  Exchange"; E6's "Notify Me" has no route and is left out).
- **Flow:** R1 → open order (resume; a purchase is handed to Add Money, and Add Money hands a sale here) → closed →
  barred → paused → identity (the shared `IdentityCheck`, `VerifyEntry.CashOut`: "Verify once, cash out any time",
  "Taking you to Cash Out.") → nobody buying (`offRamp.merchantOnline`) → the amount.
- **Amount:** a USDC/KES switch (the amount is carried across with the live price), the balance on the chain with Max,
  the chain with the most USDC picked first ("Sell from" when R1 lists several), a live price under the number (R2 asked
  450 ms after the amount rests, a fresh key per ask: R2 reserves nothing), limits from `offRamp.limits` (on what is
  received), errors: over the balance, below/above the limits, "nothing would reach M-Pesa after the fees"
  (`ramp.amount_invalid`).
- **Review:** "You sell 10 USDC"; Rate, Worth at this rate, Circaso fee (taken out), M-Pesa payout fee (taken out),
  You receive (strong); To M-Pesa (masked verified number), From your wallet on the chain; the price's clock as Add
  Money's; one button, **Sell**. The number is the account's own (R7 refuses any other): a phone without it asks once,
  checked against the mask, as Add Money does.
- **Sending the USDC:** after R7, W17 for the transfer to `deposit.address` (exactly `deposit.amountMinor`); no fee →
  the approval comes up at once; a fee → the page shows it ("costs a network fee of 0.05 USDC, so 10.05 USDC leaves
  your wallet") and waits for **Send USDC**; W17 unreachable → nothing is sent unseen. W4 always with **the order id as
  the Idempotency-Key**, so every retry is the same transfer. A cancelled approval: "You didn't approve the transfer, so
  nothing left your wallet. KES 1,269 is held for you until 18:15." with Send USDC and Cancel Sale (R6) until the
  deadline (a countdown in the footer). `wallet.ramp_order_funded` → never send again, read the order;
  `wallet.insufficient_funds` / W17 `INSUFFICIENT_FUNDS` → "Not enough USDC for this sale" with **Sell X Instead**
  (R6, then the amount page with balance − fee); `wallet.fee_changed` → the new fee shown first;
  `wallet.network_changed` → "Switch to Testnet to finish" (Send waits until the app comes back to the front); no answer
  → "We couldn't confirm the transfer" (`maybeSent`: nothing on the page may then say "nothing left your wallet", and
  Cancel isn't offered).
- **Following:** frames (`ramp` / `order.updated`, `direction`) and R4 every 4 s while not settled; the four steps
  "USDC sent → Received by Circaso's partner → M-Pesa payout sent → KES 1,269 on your phone"; COMPLETED: "KES 1,269
  sent to your M-Pesa", the receipt (tap to copy), the `MoneyCard` "Cashed out to M-Pesa" and a `Confirm` haptic;
  REFUNDING/REFUNDED worded from `failureReason`; EXPIRED (nothing sent: "Nothing left your wallet"; sent: "It's sent
  back to you by itself"); CANCELLED "Nothing left your wallet."; REVIEW "Your money is safe…", Contact Support, never
  send again.
- **The wallet's history:** `RampRepositoryImpl` keeps, per network under `text.wallet.ramp.sales.<NETWORK>`, each
  sale this phone paid (order, W4 transfer id, masked number, shillings, receipt, state, refund hash); every sale frame
  moves its state, COMPLETED/REFUNDED read the order for the receipt/refund hash. `LocalMpesaSales` makes the W4 row
  read "Cashing out to M-Pesa" / "Cashed out to M-Pesa" / "Cash out not paid" / "Cash out being checked" with
  "KES 1,269 · Receipt TKN3H2X1QP", and the refund's incoming row "Returned from a sale"; the payment detail adds
  "Received on M-Pesa", "M-Pesa receipt" and "Sale".
- **Push:** the same `ramp.order` kind opens `AddMoneyRoute(orderId)`, which hands a sale to `CashOutRoute(orderId)`.
- **Copy for codes:** `ramp.payout_fee_unavailable` "We can't price a sale right now. Nothing left your wallet. Try
  again later."; `ramp.pricing_unavailable`, `ramp.no_merchant_available`, `ramp.limit_exceeded` (the sale limits),
  `ramp.offramp_unavailable` (the closed page), `ramp.wallet_required` (no-wallet gate with Manage Networks),
  `ramp.quote_expired`/`not_found` (a new price, shown), `ramp.order_in_progress` (opened), `ramp.phone_not_yours`
  (asked again), `ramp.order_not_cancellable` (a note, the order read).
- **Previews:** `cash-out` and every state (the list is in `gallery/CashOutPreviews.kt`'s KDoc), `activity-cash-out`,
  `activity-cash-out-states`, `transfer-cash-out`, `transfer-cash-out-refunded`.
- **Tests:** `androidHostTest/feature/ramp/CashOutViewModelTest.kt` (45), `commonTest/feature/ramp/CashOutTest.kt`
  (`CashOutWireTest` 12 against the guide's JSON, `CashOutRepositoryTest` 10, `CashOutRemoteTest` 1).
- **Contract gaps (for Pasaka):** (1) W17 needs the deposit address, so the wallet's network fee can only be known after
  R7: the review says one may follow, and Max can overshoot (handled by "Sell X Instead"); a W17 by chain and amount
  alone would let the review show it. (2) R7 only takes the account's own number, so the person can't send to another
  Kenyan number (the task asked for "changeable"). (3) Sales made on another phone aren't labelled in this phone's
  history (R5/R4 carry no transfer id or deposit address after funding). (4) Whether Circaso's and the payout fees are
  charged on a refunded or expired sale isn't said; the page shows them under "Amount / Would have received".
  (5) The push for a sale has the same `kind` as a top-up, so it routes through Add Money first.
- **Not done:** run against a server; a device run.

## Add money: identity verification (2026-11-23, guide "Identity verification (KYC)" R0; design E3)

A person verifies their identity **once, when they first add money**, and only where the server says it is required
(R1's `verification.required`: always on Mainnet, on Testnet when staff switch it on). Nothing else in the app asks.
**Since 2026-11-23 the same check can also be done from the profile, the check itself lives in `IdentityCheck`, the
last known status is kept on the phone, `submitted` is honoured and the push opens Verification: read "Identity
verification in the profile" below, which supersedes what this section says on those points.**
Built on the backend's `feat/ramp-kyc` guide. **Class: Online-only** (offline-first.md): never queued, nothing kept on
the phone; the state shown is whatever R1, R0, `GET /v1/ramp/kyc` or a `kyc.updated` frame last said.

- **The SDK is behind a seam, like Circle's.** Shared code knows only `IdentityVerifier` (`verify(sessionToken)` →
  `VerifierOutcome`: Finished / Cancelled / CameraDenied / Ended / Offline / Unavailable / Failed), installed through
  `IdentityVerifierBridge` at launch. **The outcome is a hint, never the truth**: after every one the app reads
  `GET /v1/ramp/kyc`.
  - Android: Gradle module `:diditkyc` holds `me.didit:didit-sdk-core` 4.15.4 (Didit's own Maven repository, declared
    in `settings.gradle.kts` with an exclusive `me.didit` filter; no credentials). `CircasoApplication` installs
    `DiditKyc`; the SDK itself is initialised the first time someone verifies. `startVerification(token, configuration)`
    then `launchVerificationUI(activity)` as soon as the SDK has begun (it shows its own loading and "couldn't start"
    screens). Min SDK 23 (the app's is 24). No new permission: `CAMERA`, `RECORD_AUDIO`, location and `VIBRATE` were
    already declared; the SDK adds a `FileProvider` (`${applicationId}.didit.fileprovider`) and a browser `<queries>`.
    `androidApp` excludes `META-INF/versions/9/OSGI-INF/MANIFEST.MF` as Didit's setup asks.
  - iOS: `DiditKycImpl.swift` behind `#if canImport(DiditSDK)`; `pod 'DiditSDK/Core'` 4.15.4 from Didit's podspec URL
    (not on Trunk; a prebuilt XCFramework, iOS 13+). The SDK's only way in is its SwiftUI modifier, so `ContentView`
    carries `.identityVerification()` (which is `.diditVerification { … }`) and `DiditSdk.shared.startVerification(token:)`
    presents from there. `Info.plist`: the camera, microphone and photo-library purpose strings now also name identity
    verification (the keys were already there; no new key, no entitlement).
  - **Core, not the full SDK, on both:** no NFC chip reading (it would add OpenSSL, an iOS entitlement and an App
    Review question for a step the workflow doesn't have). Colours come from Didit's white-label settings.
  - Configuration on both: close button and its exit confirmation on, `closeOnComplete` on (Circaso's own "Checking
    your details" follows at once, with no second "done" screen to tap), logging off, the phone's language, system font.
  - **Fallback:** a build without the SDK (no `pod install`) or an SDK that stops for a reason of its own opens the
    session's `url` in the **system's** in-app browser (`openInSystemBrowserAndWait`: a Custom Tab by intent extras,
    `SFSafariViewController`), never in Circaso's own web view (which has no camera by design). No OS-version gate was
    needed: the SDK's minimums are below the app's.
- **Layers** (`feature/ramp`): `KycResponse`/`VerificationDto`/`KycSessionDto` (`RampDtos`), R0 + the read in
  `KtorRampRemote` (through `walletCall`, so they carry `X-Wallet-Network`), `RampRepository.startKyc/kyc/kycUpdates`,
  use cases `StartKyc`, `GetKyc`, `ObserveKycUpdates`, `RunIdentityCheck`; `AddMoneyViewModel` owns the flow
  (`VerifyUi` inside `AddMoneyUiState`); `AddMoneyVerify.kt` has the words (`verifyLook`) and the two pages.
  `RampStatus` gains `verification` and `blocked`; a server from before verification is read off its short `kyc`.
- **Flow:** R1 → `blocked` wins (its own page) → account paused → **`verification` not cleared: the verify pages** →
  no partner online → amount. A check that R1 says is `PENDING` or `IN_REVIEW` is read (`GET /v1/ramp/kyc`, which asks
  Didit) *before* the page shows, so coming back after finishing lands on the result, not on "Continue". The button
  calls R0 (busy from the tap until Didit's screens are up; the page under them doesn't move), then the SDK; when it
  closes, the read; `VERIFIED` shows "You're verified" for 1.8 s with a success haptic while R1 is read behind it,
  then the amount page, with nothing to tap. The amount typed before a `ramp.kyc_required` is kept.
- **Idempotency:** one key per tap of the button, the same on a retry with no definite answer (network, `5xx`,
  `request.in_progress`), a new one once the server answered (success or `4xx`): R0 itself returns the same open
  session, so "Continue Verification" never starts over.
- **Waiting for a result:** after `Finished` the page is "Checking your details" at once and the read repeats every
  5 s for a minute, then every 20 s; `IN_REVIEW` is read every 20 s while the page is open. The frame and the push
  arrive beside it. Nothing is read offline. Back from the hosted page (where "finished" can't be known) the wait is
  given 20 s under its own words (never "ID and selfie sent"), then the page says "Finish verifying your identity".
- **Frames, resync, resume:** `ramp` / `kyc.updated` replaces what is shown, for the flow's own network only;
  `VERIFIED` is final (a replayed older frame never walks it back); while Didit's screens are up frames are held and
  applied once they close. A `resync` and the app coming to the front (`LifecycleResumeEffect`) read the check again.
  When today's tries come back (`retryAt`, or the 429's `retryAfterSeconds`) the page reads itself again: by a
  timer while it is open, and again whenever the app comes to the front or the connection returns
  (`readIfItMayHaveMoved`), so nobody is left with only Close once the time has come.
- **Closing Didit's screens early:** R0 already said a check is open, so "Finish verifying your identity" is on the
  page the instant they are gone; the read follows behind and replaces it only if the server says otherwise.
- **Design review (2026-11-23, `design-reviewer`), fixed the same day:** the hosted page claiming "sent"; "you won't
  be asked again" and "for good" (an ID can expire; each network has its own check); a busy stale page after closing
  the SDK; a Close-only dead end after `retryAt`; "less than a minute" that never changed; the ground colour snapping
  between E3 and the other states (`CircasoScaffold(easeGround = true)`, opt-in, used only by Add Money; never on
  a theme change or under Reduce Motion); titles repeated in their messages; "Continue Verification" too long for large text (now "Continue", the
  guide's word); E3's footer stepping; the haptic missed when verified is the first state; a red banner for a calm
  note; the live region speaking twice. **Second pass, fixed:** the ground easing leaking into Home and New Group
  (now opt-in); the live region back on the lasting wrapper with the leaving words silenced; the slow message
  repeating its footnote; a wake-up read the server doesn't yet agree with is tried again after 15, 30 and 60 s;
  overlapping reads (only the newest answer is shown); an SDK that ended itself offers nothing until the server has
  answered; after the hosted page's 20 s the open check is still read every 20 s for three minutes; a cap with no
  time given is left alone on resume. **Open:** the first paint is still the amount page's skeleton before E3;
  `CircasoButton`'s label shifts when its spinner appears (a design-system matter); iOS has no watchdog if Didit's
  screens were never presented (they do present on the simulator: checked with the probe); the live region needs
  one TalkBack and one VoiceOver pass on a phone.
- **Errors → states:** `ramp.kyc_required` on R2/R3 → R1 is read and its state shown (if R1 disagrees, the refusal's
  words on the amount page, never a loop); `ramp.blocked` anywhere → the barred page; `ramp.kyc_attempts_exceeded` →
  "You've tried a few times today"; `ramp.kyc_unavailable` → on our side, Try Again, with the request reference;
  `ramp.kyc_not_configured` → on our side, no retry, with the reference; `ramp.unavailable` / `ramp.account_restricted`
  → their existing pages; no connection or an unknown code → a note on the page ("Nothing is lost").
- **Push:** `kind: "ramp.kyc"` (+ `network`) → `NotificationDestination.RampKyc` → `AddMoneyRoute(network = …)` under
  the Wallet tab; the flow is pinned to the push's network and reads R1, so it lands on the right state (verified goes
  straight to the amount). The alert is the server's own words (`Wording.swift` leaves `ramp.kyc` alone).
- **Never logged or kept:** `KycSession` and `KycSessionDto` hide themselves in `toString`; the token and the link
  are never in the UI state; the SDK's logging is off.
- **Pages:** `AddMoneyPage.Verify` is E3 itself (plain ground, `Start Verification`, the consent note naming Didit,
  a Testnet line saying it is a practice run); `AddMoneyPage.VerifyState` is every state after it, in the order
  page's own layout (badge, title, paragraph, a three-step story while a result is awaited, the buttons pinned).
- **Copy** (title / message / button), from `verifyLook`:
  - Never started: E3, "Start Verification".
  - Open check: "Finish verifying your identity" / "You've already started. Pick up where you left off: it takes a
    few minutes." / Continue.
  - Step asked again: "We need one more thing" / "A reviewer asked for one step again to finish verifying you. The
    rest is already done." / Continue.
  - Checking: "Checking your details" / "This usually takes less than a minute. You don't need to do anything."
    (after a minute: "This is taking longer than usual. Nothing more is needed from you.") / Close
    ("You can leave this screen. We'll tell you when it's done.").
  - Back from the hosted page (not known whether they finished, so nothing is said to be sent): "Checking where
    your verification stands" / "If you finished, the result shows here in a moment." / Close.
  - In review: "We're taking a closer look" / "A reviewer is going over your details. This usually takes a few
    minutes, and we'll notify you as soon as it's done." / Close.
  - Verified: "You're verified" / "That's it. You can add money any time now." (no button; "Taking you to Add
    Money.").
  - Declined, retryable ("Try Again"): `document` "We couldn't verify your ID" / "Try again with a clear photo of a
    valid ID, with all four corners showing."; `document_expired` "Your ID has expired" / "Verify again with an ID
    that is still valid."; `document_unreadable` "We couldn't read your ID" / "Its details didn't come through
    clearly. Try again with your national ID or passport."; `liveness` "The selfie check didn't pass" / "Try again in
    good light, with your face uncovered."; `face_match` "Your selfie didn't match your ID" / "Try again in good
    light, facing the camera, with your own ID."; `declined` or an unknown reason "We couldn't verify you this time" /
    "Something in the check didn't pass. Please try again.".
  - Declined with today's tries used (`canStart` false, `retryAt`): the same title / "Next time, … Today's tries are
    used up. You can try again tomorrow, after 09:05." / Close.
  - Declined for good ("Contact Support", no retry): `not_approved` "We couldn't verify your identity" / "Trying again
    won't change this. Contact Support and we'll help you from here."; `identity_in_use` "This ID is already in use";
    `blocked` "You can't add money on Circaso".
  - Out of time: "Your verification ran out of time" / "Start again when you're ready. It takes a few minutes." /
    Verify Again. ID expired since: "Your ID has expired" / "The ID you verified with is no longer valid. Verify again
    with one that is." / Verify Again.
  - Tries used up: "You've tried a few times today" / "You can try again tomorrow, after 09:05." / Close.
  - Didit out of reach: "We couldn't start your verification" / "This is on our side, and nothing is lost: it didn't
    use one of your tries. Try again in a moment." / Try Again.
  - Not set up: "Identity verification isn't available yet" / "We're still setting it up on our side. Nothing is
    needed from you and nothing is lost. Please try again later." / Close.
  - Camera refused: "Circaso can't use your camera" / "…Turn Camera on for Circaso in Settings, then come back and
    continue." / Open Settings.
  - Offline: the button waits, with "You're offline. Verifying your identity needs a connection."; a wait says
    "You're offline. This updates by itself when you're back."
  - Barred (R1 `blocked`, `ramp.blocked`): "You can't add money on Circaso" / "Contact Support if you think this is
    a mistake." / Contact Support.
- **Previews** (`gallery/AddMoneyPreviews.kt`): `add-money-verify` and `-mainnet`, `-starting`, `-offline`, `-failed`,
  `-continue`, `-continue-starting`, `-continue-offline`, `-sdk-stopped`, `-more`, `-checking`, `-checking-offline`,
  `-checking-stale`, `-checking-slow`, `-returned`, `-review`, `-done`, `-declined` (`-face`, `-document`, `-unreadable`, `-liveness`, `-unknown`),
  `-declined-wait`, `-final`, `-in-use`, `-blocked`, `-expired`, `-id-expired`, `-expired-wait`, `-tries`,
  `-tries-open`, `-down`, `-down-starting`, `-setup`, `-camera`; `add-money-barred`. Debug probes that need no server:
  `add-money-verify-probe` (runs the installed SDK with a token no server made) and `-probe-page` (the system
  browser).
- **Tests:** `androidHostTest/feature/ramp/AddMoneyVerifyTest.kt` (68: whether it is asked, starting, each SDK outcome
  against each server answer, R0 with nothing to do, every error code and the keys on retries, R2/R3 routing, frames,
  leaving and coming back, every state's words, the cap, offline, the push) and `commonTest/feature/ramp/RampTest.kt`
  (the guide's JSON for R0, the read, R1 and the frame; an older server; the routes and their keys on the wire; that
  a session never shows in a log line; the push destination).
- **Not verified until a real phone runs a sandbox session** (the SDK can't run in previews): see
  `project-status.md` → "Next work".

## Identity verification in the profile (2026-11-23, branch `feat/kyc-in-profile`; design F2's "Verification" row, E3)

Pasaka: "on the profile section, if my KYC profile is verified, I should see if it's verified. And if it's unverified,
I should be able to verify it on my own without having to start a transaction to verify it." Built on the backend's
`fix/ramp-kyc-submitted` guide (`submitted` on the verification) and for the routes being opened to everyone (below).

- **One check, two ways in.** The check itself was lifted out of `AddMoneyViewModel` into **`IdentityCheck`**
  (`feature/ramp/presentation/IdentityCheck.kt`): R0, Didit's screens or the hosted page, the read after them, the
  wait, frames held under the SDK, the daily cap, coming back to the app, the keys on retries. It owns no state: the
  screen's state holds the `VerifyUi` and hands it over through `IdentityCheck.Host` (`verify`, `update`, `showing`,
  `online`, `canVerify`, and what to do on `onVerified`, `onNotRequired`, `onBlocked`), so a screen can still change
  its page and the check in one step. `AddMoneyViewModel` and `IdentityViewModel` are the two hosts. Add Money's
  behaviour is unchanged (its 68 tests pass as they were, bar one title): verified still shows for 1.8 s and goes on to
  the amount. The pages are shared too: `VerifyIntroPage` / `VerifyStatePage` take a `VerifyPageState`
  (`verify`, `online`, `testnet`, `canVerify`, `entry`), and `verifyLook(verify, now, entry)` words both.
  `VerifyEntry.Profile` differs only where the destination does: no "Not Now" (the bar's Back leaves), the button that
  only leaves reads "Done" rather than "Close", and verified says "Your identity is confirmed." and goes nowhere.
- **`submitted` (the backend's 2026-11-23 change) is read before `canStart`, everywhere a state is decided:**
  `RampVerification.submitted` (false when the server doesn't send it), `awaitingDecision`, `startable`;
  `VerifyUi.startable`; `verifyLook` (a `PENDING` + `submitted` check is "We're checking your details" before any
  note this phone was showing); `IdentityCheck.show` (sets `checking`, drops a camera or tries note), `onVerify`
  (refuses), R0's answer (a session that came with `submitted` is never opened; the DTO mapper drops it); the
  profile's row. While it is true: no button, R0 never asked, the SDK never reopened, and `GET /v1/ramp/kyc` is read
  **every 5 s for as long as the page is open** (a wait this phone only believes in, after the SDK's "finished", is
  still 5 s for a minute and then 20 s, as before; `IN_REVIEW` stays at 20 s). Reads rest while the app isn't in front
  (`IdentityCheck.onPaused`; Didit's own screens over the app don't count) and resume with a read. The title of the
  wait is now the guide's "We're checking your details" in both entries.
- **The phone's copy (offline-first: reading the status is a Read; verifying is Online-only).** `RampRepositoryImpl`
  files every answer the server gives about the check (R0, `GET /v1/ramp/kyc`, R1's `verification`, a `kyc.updated`
  frame under the frame's own network) as `SavedIdentity` JSON in `AppPreferences` under
  `text.wallet.ramp.kyc.<NETWORK>`: one per wallet network, wiped at sign-out with the other `text.wallet.*` settings
  (so one account's), holding only the state, its reason code, `retryAt`, `submitted` and when it was said. Never a
  session. `ramp.unavailable` from a verification route is remembered as "not open here"; no answer (no connection, a
  fault) leaves the copy alone; R1 refusing says nothing about the check. `RampRepository.identity` /
  `ObserveIdentityUseCase` read it for the network being read (the pinned one, else the selected one).
- **The row.** You tab → **Money** group, first row, titled **"Verification"** as F2 names it (the task said
  "Identity"; the frame wins on copy). F2 puts it under "Account" beside "Profile", a group the app no longer has
  (Profile became the hero's Edit Profile, 2026-10-30); Money is where the code already meant it to go ("Money rows
  (currency, limits, verification)") and what it is for (E3: "Sending and receiving on Circaso never needs this.
  Moving money in or out of Circaso does"). Styled as its neighbours (`ListRow`, a sub line, the chevron), with the
  status at its end as F2 draws it: the canvas's `cx-badge` (a 14 px sign and a 13/18 semibold word in the tone's
  colour; F2 itself shows the amber clock "In review"), or the one word for what opening it does in `action` colour.
  **No verified mark by the name:** F2 has none, the palette has no positive colour, "Verified" beside a name in the
  canvas means the Circaso team (SP frames), and the status is per network, so a mark on the name would say more than
  is true on Testnet. `identityRow()` (pure, tested) decides it; `IdentityRowModel` keeps it true (asks on the profile
  being opened: each arrival on the tab, the app coming to the front, the connection returning, another network
  selected); `IdentityRow` draws it, with a skeleton of the row (chevron included) as its first paint. Only what
  changes fades (`AnimatedContent` with `easeWords`, the row easing to its new height); the title never does. On the
  You tab the row and its divider open and close their room (`AnimatedVisibility`: fade + expand in `BaseMs`, fade
  `ExitMs` + shrink `QuickMs`, 0 ms under Reduce Motion), so the rows under it never jump. At a font scale of 1.3 or
  more the badge or action word moves under the status (`ListRow`'s new `below` slot), so the title is never squeezed.
  - Verified: "Your identity is confirmed" · ✓ Verified. Not verified: "Needed to move money in or out of Circaso"
    ("Not verified" where the server doesn't require it) · **Verify**. In progress: "Started. Pick up where you left
    off" · **Continue**. Step asked again: "One more step is needed" · **Continue**. Checking (`submitted`): "We're
    checking your details" · clock Checking. In review: "A reviewer is going over your details" · clock In review.
    Needs another try: "The last check didn't pass" / "The last check ran out of time" / "Your ID has expired" ·
    **Try Again** (an expired check: **Verify Again**, the screen's own button words). Tries used up: "Today's tries
    are used up. You can try again tomorrow, after 09:05". Couldn't verify (final): "Contact
    Support for help" · ! Couldn't verify (critical). Not asked for and can't be started: "Not needed right now".
    A newer server's status with nothing to do: "We're working on your verification" · clock In progress.
  - Offline, or the read got no answer: the same status and sub line from the copy, with a footnote line under it
    (`IdentityRowLook.note`, `inkTertiary`): "Last known status. Verifying needs a connection." (when there is
    something to do) / "Last known status. Updates when you're back online." / "Last known status. Couldn't check
    just now.", and the action word quietened. Nothing known: "Shows when you're back online" / "Couldn't check just
    now".
  - Testnet: every sub line starts "Testnet · ".
  - `ramp.unavailable`: no row at all.
- **The screen** (`IdentityRoute(network?)`, `IdentityViewModel`, `IdentityScreen.kt`; nav title "Verification",
  Back, the Testnet strip from the network it is pinned to when it opens). Pages (`IdentityPage`): Loading (the shape
  of a state's page, breathing), LoadFailed (nothing on the phone and no answer: "You're offline" or "Your
  verification didn't load" with the request reference and Try Again), Unavailable ("Verification isn't available
  yet", never an error), Gate (barred / "Verification is paused on this account", Contact Support), NotNeeded ("No
  verification needed"), Intro (E3), State (every state after E3, the shared page), **Verified**. It reads
  `GET /v1/ramp/kyc` (not R1: the top-up's gates aren't needed to verify), shows the copy first, and marks it
  ("Showing your last known status, checked today, 14:05.") when offline or when the read got no answer. A "verified"
  from the copy isn't final (the server's answer replaces it: an ID can expire); one the server gave in this visit
  is (a replayed frame never walks it back). Coming back to the app reads a settled status again.
  - **Verified stays**: the badge, "You're verified", "Your identity is confirmed. There's nothing more to do." (Testnet:
    "…confirmed for Testnet. This was a practice check."), then three rows: "Add money from M-Pesa / What being
    verified unlocks on Circaso" (never "cash out": E6 isn't built), "Didit ran the check / Our verification partner.
    Your documents went to them encrypted and are never shown to other people." (E3's own promise; nothing is said
    about what Circaso keeps, which the guide doesn't state), "Counts on Mainnet / Testnet has its own practice check,
    with sample documents." (Testnet: "Counts on Testnet only / It used sample documents. Mainnet has its own check,
    with your real ID."). A secondary **Done** at the foot, as the screen's other end pages have; no jump into Add
    Money; a `Confirm` haptic only when it happened in front of the person after the server had spoken in this visit.
    **No date**: the contract gives none (see gaps).
  - **Offered, not required** (`required: false`, `canStart: true`): E3's paragraph reads "Sending and receiving on
    Circaso never needs this. Adding money doesn't need it yet either: verify now and it's done when it does." (never
    "by law", which is only said where the server requires it).
- **Frames and the push.** `kyc.updated` is filed in the copy (the row follows) and heard by an open screen, for its
  own network only. `ramp.kyc`: a tap opens `IdentityRoute(network)` under the **You** tab
  (`NotificationRoutes.resolve`), unless Add Money is the screen on top (`NotificationRoutes.staysOn`: it hears the
  same news and goes on to the amount). The push arriving while the app runs reads the check for its network
  (`IdentityPush.received`: Android `onMessageReceived`, iOS `shouldPresent`), so the row is right before anyone taps.
- **Routes being opened (backend, in progress on 2026-11-23).** The verification routes are being opened so they work
  with the ramp off or verification not required for trading. Until a server has that, R0 and the read answer
  `ramp.unavailable` (403): the row is left out and the screen says "Verification isn't available yet". When the
  routes open, the next answer replaces the remembered "not open here" by itself. A network where verification isn't
  required but may be started (`required: false`, `canStart: true`) shows E3 and can be verified.
- **Never logged or kept:** as before (`KycSession`, `KycSessionDto` hide themselves; the token and link never reach
  a state); the copy holds no session and nothing personal (test: `IdentityCopyTest`).
- **Previews** (`gallery/IdentityPreviews.kt`): the screen `identity` (verified, Testnet), `-verified-mainnet`,
  `-verified-offline`, `-verified-unconfirmed`, `-verified-just`, `-loading`, `-load-failed`, `-load-offline`,
  `-unavailable`, `-barred`, `-restricted`, `-not-needed`, `-none`, `-none-mainnet`, `-none-offline`, `-optional`,
  `-starting`, `-continue`, `-continue-offline`, `-more`, `-checking`, `-checking-slow`, `-checking-offline`,
  `-review`, `-declined`, `-expired`, `-id-expired`, `-wait`, `-final`, `-tries`, `-down`, `-setup`, `-camera`; the
  row in every status on one page `identity-rows` (`-offline`, `-unconfirmed`, `-mainnet`); on the You tab
  `you-verified`, `you-unverified`, `you-identity-loading`; Add Money's `add-money-verify-submitted`.
- **Tests:** `androidHostTest/feature/ramp/IdentityViewModelTest.kt` (43: every status from the profile, verifying
  without a top-up and staying on verified, `submitted`, the 403, each error with its reference, the copy and
  offline, frames, the push's network, coming back, and the row's model: asking, offline, the 403, frames and the
  push, another network selected); `AddMoneyVerifyTest` (82: the 68 it had, the push's new destination, and 14 for
  `submitted` in Add Money); `commonTest/feature/ramp/IdentityTest.kt` (`SubmittedWireTest` 9: the guide's JSON for
  R0, the read, R1 and the frame, and a server that doesn't send the field; `IdentityCopyTest` 13: the copy per
  network, what replaces it and what doesn't, no session in it, the push's read; `IdentityRowTest` 15: every row
  status, Testnet, offline, nothing known, hidden).
- **Contract gaps (told to Pasaka, 2026-11-23):** (1) no "verified at" date anywhere in `verification`, so the screen
  can't say when; (2) until the routes are opened, someone the ramp isn't open to can't verify or see a status
  (`ramp.unavailable`); (3) the guide doesn't say what Circaso keeps of the documents, so the verified page says only
  E3's promise (and "Add money", not "cash out": E6 is still MISSING); (4) the push says "a tap opens Add money" in the guide: the app opens
  Verification instead (asked for); (5) whether the server always writes `submitted: false` or leaves it out can't be
  told from the wire, so "the server knows the field" is never assumed: a wait that only this phone believes in is
  kept as before.
- **Design review (2026-11-23, `design-reviewer`), fixed:** the row popping in and out of the Money group; offline
  rows losing their status; large text squeezing the title; the whole row cross-fading; "cash out" and an unconfirmed
  data claim on the verified page; "by law" on an optional check; no Done on the verified page; the skeleton without a
  chevron; button words differing between the row and the screen; screen-reader progress on the skeletons and the
  verified heading not folded into its paragraph. **Left as is:** the row and the screen each take the selected network
  (`networks.followed` / `networks.current()`), the same one on the You tab, so the route isn't given the row's
  network; "in a moment" on a wait is worded when the row is drawn and redrawn on the next read.
- **Not verified on a phone:** the SDK run from the profile (same seam as Add Money, which is also still unrun on a
  phone), the push tap on a device, TalkBack/VoiceOver passes.

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

## Wallet networks: Mainnet and Testnet side by side (2026-11-18; contract `wallet-networks-contract.md`)

One server talks to Circle Mainnet and Testnet. Each is its own Circle user: a person's two wallets have two PINs and
never touch. People who had a Testnet wallet get a Mainnet | Testnet switch; new people are Mainnet only and never see
the switch or the word testnet. This is closed testing with real crypto.

- **One source of truth** (`feature/wallet/domain/WalletNetwork.kt`): `WalletNetwork` (`MAINNET`, `TESTNET`),
  `WalletNetworks` (`state`: options, `selected`, `default`, `known`, `legacy`, `switchable`, `testnet`, a one-off
  `notice`). The implementation is `data/repository/WalletNetworksStore`, a Koin single over
  `GET /v1/wallet/networks` and two small settings, `text.wallet.network` (only a choice the person made) and
  `text.wallet.networks` (the server's last list), both wiped at sign-out with `text.wallet.*`; the store follows the
  settings, so a sign-out resets it. With no choice saved the server's `default` leads.
- **Degrading safely.** `404 request.not_found` on the list is a server from before networks (`legacy`): one network
  (Testnet, where the phone's copies already are), no switch, **no header**. Before the server has answered at all
  (`known == false`) no header is sent either and nothing says testnet. A network the server stops listing, or
  `403 wallet.network_unavailable` on any call, moves to the default and the Wallet tab says so once.
- **On the wire.** `X-Wallet-Network` goes on every wallet-scoped call through `CircasoApi.walletCall(gate)` and
  `WalletNetworkGate` (`core/network`): all of `KtorWalletRemote` (except the list itself), `KtorVaultRemote`,
  `KtorChamaRemote`, and Asha's turn (`POST /v1/assistant/chat/messages`). Asha's live voice socket sends none (its
  auth frame has no field for it). DTOs with `blockchain` gained an optional `network`; frames and the payment push
  are read for it.
- **Pinning** (`PinnedNetwork`, a coroutine context element): a call is made and saved for one network whatever is
  selected when it answers. The repositories wrap every remote call in it (`WalletRepositoryImpl.on`), frames are
  handled under the network they name (else their chain's, else the phone's row, else the selected one), and
  `PaymentRequest.network` is the pin of `PayViewModel` / `SendToWalletViewModel`: `pay` sends nothing if the
  selection has moved (`wallet.network_changed`) and never opens the PIN if it moves between W4 and the SDK (the row
  becomes Expired). W5 for a card of the other network is retried on that network.
- **A payment's screen shows its pin, not the selection** (design review of PR #74). `PayUiState.network/testnet` and
  `SendToWalletUiState.network/testnet` come from the pin; the wallet is read with `ObserveWalletUseCase(network)`
  (`WalletRepository.walletOn`); the Testnet strip is driven by that state. The pin follows the selection only while
  nothing is being signed, and is looked at again when signing ends: Pay starts the card again on the new network
  (amount and note kept), Send returns to its form with a notice. `wallet.network_changed` resets the same way.
  Only a card still being filled in moves (Pay: Pick or Amount; Send: not Done): a payment being signed, on its way
  or done keeps its network while it is on screen, and on Sending / Done the mark comes from the result's own chain.
  A group wallet the money leaves is read for the pin too (`WalletRepository.walletOfOn`).
- **Data carries its network.** `Wallet.network` is the row's network. `WalletViewModel.networkSide` re-reads the
  wallet, the picked group and the switcher's balances per selected network and hands them on with that network; the
  tab's `network` and `testnet` come from that data, so one network's balance is never drawn under the other's label
  (a copy that says it is another network's is dropped). `BiometricApprovalViewModel` and `GroupInfoMoneySource` read
  the setting and the wallet for one network together.
- **First visit to a network:** when nothing of it was read on this phone and the server's list says the wallet is
  `NONE` or `SETTING_UP` (`WalletUiState.createAtOnce`), the create screen shows at once, offline too.
- **Room v24** (auto-migration with `WalletNetworksMigration`): `wallet_snapshot` and `wallet_list` are one row per
  network (their `id` column is gone); `wallet_cache`, `wallet_activity`, `chama_snapshot`, `group_money` have
  `network` in their keys; `transfer` has the column (lists filter on it, a payment is still found by id). Every row
  from before is `TESTNET`. Every repository flow is `networks.selected.flatMapLatest { dao…(it.wire) }`, which is why
  switching is instant. Test: `iosTest/core/WalletNetworksMigrationTest.kt` (the project's pattern).
- **Per network on the phone:** biometric approval and "biometrics lost" flags (`AppPreferences.biometricFlag(walletId,
  network)`: Testnet keeps the names from before, Mainnet's carry `mainnet`), the PIN Vault's remembered status
  (`vault.status` / `vault.status.mainnet`; the server picks the slot from the header), the create and group set-up
  idempotency keys, the tab's "wants to send you money" nudge.
- **UI** (no design frame; decided to the premium bar; the top row redesigned 2026-11-19 after Pasaka saw two equal
  pills fill the row on his 384 dp phone): the Wallet tab's top row is **one control and one quiet status chip**
  (`WalletTopRow` in `WalletScreens.kt`, a small custom layout inside the page's `s4` inset). Personal | Groups
  (`WalletModeControl`, at its own size) sits at the start; `NetworkChip` (`WalletNetworkParts.kt`: the network's dot,
  its name in `footnote` semibold, a small chevron, a 32 dp capsule on `fill` inside the row's 44 dp target, outlined
  in high contrast, the word crossfading on a change) sits at the end and only shows for someone with both networks.
  With no chip the control is centred as before networks and eases to the start if a chip arrives later; the chip
  alone still sits at the end; where the two don't fit on one line (large text) the chip takes its own line,
  end-aligned. The chip opens the **Network sheet** (`NetworkSheetContent`, drawn in `WalletOverlay`;
  `WalletUiState.networkSheet`, `WalletViewModel.onNetworkSheet`): the person's networks as radio rows on the Group
  Wallets sheet's grid ("Real money" / "Test money, no real value", plus "· Not set up yet" from
  `WalletNetworksState.statusOf`), a tick on the selected one, a footnote that each is a separate wallet with its own
  PIN; a tap picks and closes, offline too. `TwoSegmentControl` serves Personal | Groups only again. Mainnet's dot is
  ink held back (the theme has no positive role: `money` is ink), Testnet's is `warning`; `NetworkDot` draws both and
  the Testnet tag uses it, so chip, sheet and tag are one family. Test money is
  marked by `TestnetTag` / `TestnetNote`, and on screens by the one `TestnetStrip(show)` (`WalletNetworkParts.kt`;
  it owns its padding and eases open, a fade with reduced motion; the note wraps at large text): in the scaffold's
  `strip` on Pay and Send (from their pinned network), Transactions, Networks, Assets, Currency, the group wallet,
  group wallet set-up, the target form and the chama screen (`LocalTestnet`, provided in `App`); on the Receive sheet
  and the thread's goal strip; on a payment's detail and a chat's money card by the payment's own chain, whatever is
  selected. **Decided 2026-11-18:** old Testnet cards and receipts stay marked for someone who later has only
  Mainnet (it is the truth about that money). In You → Security the "Approve payments with…" row names the wallet
  ("For your Mainnet wallet") when the person has both networks. Chain
  names say "(testnet)" from `isTestnetChain` (SEPOLIA, AMOY, DEVNET, FUJI, TESTNET). The first visit to a network
  with no wallet is the create screen, named ("Set up your Mainnet wallet") and saying it is a separate wallet with its
  own PIN.
- **Solana:** `isSolanaChain`: the fee row reads "No Circaso fee · network fee paid in SOL" (Pay, Send and the
  receipt; "Network fee in SOL" on the chat's slim card). Too little SOL is one code, `wallet.insufficient_gas` (W4
  and W17), and one W17 `problem`, `INSUFFICIENT_GAS`: `PayBlock.Gas` on Pay and the chat card,
  `SendToWalletUiState.gasShort` on Send, both drawn by `SolanaGasNote` (what is wrong, the wallet's Solana address,
  Copy Solana Address).
- **Previews:** `wallet-network-mainnet|testnet|groups|groups-testnet|create-mainnet|create-testnet|offline`,
  `wallet-testnet-only`, `wallet-mainnet-only`, `gw-pay-sol`, `gw-pay-sol-gas`, `gw-send-sol-gas`, `gw-pay-testnet`.
- **Tests:** `commonTest/feature/wallet/WalletNetworksTest.kt` (the store, the header on the wire with a mock engine,
  per-network copies, frames, the payment pin, the legacy server) and
  `androidHostTest/feature/wallet/WalletNetworkViewModelsTest.kt` (Pay and Send show and follow their pin, the reset
  on a switch, `PayBlock.Gas` from W17 and W4, the Solana fee lines, the Wallet tab never pairing one network's label
  with the other's money, `onNetwork`, the notice, the create screen at once).
- **Group wallets are not on "the selected network"**: see "Group wallets and networks" below.
- **Not done:** Circle's own PIN screens don't name the network; the treasurer-model link retry (`PendingLinks`) is
  not per network; nothing here has run against a server with networks (the routes were not live when it was built).

## Group wallets and networks (2026-11-19, Pasaka after testing PR #74)

**The principle, across the app:** a group's money is on the network its wallet is on. An existing group with a
Testnet wallet can also get a Mainnet wallet (then it has both); a new group has one wallet, on Mainnet. So a new
Mainnet group shows and takes Mainnet money while the Wallet tab is on Testnet (with no Testnet mark), and an old
Testnet group shows its Testnet wallet, marked, while the tab is on Mainnet.

- **One rule, one place** (`feature/wallet/domain/GroupNetwork.kt`, pure, tested): `groupNetwork(usable, selected,
  wallets)` is the selected network when the group has a wallet there; otherwise its only wallet's network (when the
  person may use it); otherwise the network a new wallet is made on, `newGroupWalletNetwork`: Mainnet whenever the
  person may use it, else Testnet. A wallet that works comes before one still being set up. Before the server has said
  which networks there are (or on a server without networks) nothing is decided: the selected one.
- **Where the facts come from:** `GroupNetworks` (a Koin single) keeps `GroupNetworksState` in memory from
  `WalletNetworks` and the per-network W16 copies (`WalletRepository.walletsOn(network)`, Room `wallet_list`). Its
  `refresh()` reads W16 on **each network in the person's options and never on another** (that would be
  `wallet.network_unavailable` and move the selection); the Wallet tab's refresh, group info's refresh and the end of a
  group wallet set-up call it. `networkOf(chatId)` is the rule; `networkOfWallet(walletId)` is the list that holds the
  wallet (a wallet is on one network); `mainnetFor(chatId)` says whether an old group's owner can add (or must finish) a
  Mainnet wallet; `withoutMainnet` lists those groups.
- **How a screen is pinned.** `WalletNetworks.followed` is "the network pinned on the collector, else the selected
  one", and every repository flow is built on it (`WalletRepositoryImpl`, `ChamaRepositoryImpl`), as every call
  already was through `effective()`. So pinning is one line: a view model works in `viewModelScope.pinnedTo(network)`
  (everything it launches and collects), and a flow is read with `.on(network)` (`flowOn(PinnedNetwork)`).
  `GroupWalletViewModel`, `TargetViewModel`, `ChamaViewModel`, `GroupWalletSetupViewModel`, and by wallet id
  `ActivityViewModel` and `NetworkAssetsViewModel` take a `network`; the navigation entry works it out
  (`groupNetworkOf` / `walletNetworkOf` in `AppNavigation.kt`), keys the view model on it and wraps the screen in
  `OnWalletNetwork`, which provides `LocalTestnet` from **that** network, so the screen's `TestnetStrip` /
  `TestnetNote` never follow the tab's selection. `GroupInfoMoneySource` and `ThreadChama` follow
  `GroupNetworks` themselves (the goal strip's "Testnet ·" is `ThreadUiState.moneyTestnet`).
- **Paying.** `PayViewModel.target()`: money into a group is on the group's network, paid from the person's own wallet
  **on that network**; money out of a group's wallet is on that wallet's network; anything else follows the
  selection as before. `PayIntent.network` / `PayRoute.network` fix it when the card was opened from one of a group's
  two wallets. `PaymentRequest.followsSelection` is false for group money, so `WalletRepositoryImpl.pay` no longer
  refuses it with `wallet.network_changed` when the tab shows the other network. If the person has no wallet on that
  network yet, `PayUiState.needsWalletLine` says "Set up your Mainnet wallet to pay into {group}." (the network named
  only for someone with both), on the Pay screen and the chat's money card. `SendToWalletViewModel` does the same for
  a group wallet's send.
- **Creating.** No picker anywhere. `GroupWalletSetupRoute.network` is fixed when the set-up opens
  (`AppNavigation.setUpGroupWallet`: the rule, so Mainnet for a new wallet and the old network for a set-up already
  started; New Group passes `newWalletNetwork()`), and the whole set-up (W14, PIN, W15, biometrics, W13, CH9, CH2)
  runs pinned to it. The screen names the network for someone with both ("Setting up Kilimani Chama’s Mainnet
  wallet") and takes its Testnet strip from `GroupWalletSetupState.testnet`.
- **Group info** (`GroupMoneyRows`): `GroupInfoMoney.network/testnet/wallets/mainnet`. One wallet: "Group Wallet"
  with `TestnetTag` when it is test money. An old group's owner: "Add a Mainnet Wallet" ("Real money · its own PIN")
  or "Finish Mainnet Wallet Setup", which run the normal set-up for Mainnet; members see nothing until it exists. Both
  wallets: "Mainnet wallet" then "Testnet wallet" (tagged), each with its balance, each opening
  `GroupWalletRoute(chatId, network)`; the rows under them (pay from, target, security) are for the rule's network and
  say which. Leaving stays blocked while either wallet holds money.
- **Wallet tab, Groups side:** still the selected network's group wallets. On Mainnet, `WalletUiState.withoutMainnet`
  (the owner's old groups with nothing started on Mainnet) is a section "Without a Mainnet wallet" in the Group
  Wallets sheet; a tap opens that group's Mainnet set-up. With no Mainnet group wallet at all but such groups,
  Personal | Groups still shows and Groups is `NoMainnetGroupWallets` ("No Mainnet group wallets yet") with the list.
- **`wallet.group_mainnet_only`** (W14 on Testnet for a group with no Testnet wallet): "New group wallets are made on
  Mainnet." in `ErrorMessages`, keyed on the code.
- **Tests:** `commonTest/feature/wallet/GroupNetworksTest.kt` (the rule; the state's lookups; W16 only on usable
  networks; a new wallet made on Mainnet with Testnet selected; a pinned read asked, saved and read for its network;
  a group payment not stopped by the selection; the header on the wire; the new code's words) and two view-model tests
  in `androidHostTest/feature/wallet/GroupWalletViewModelsTest.kt` (the set-up and the pushed group wallet on a
  network other than the selected one).
- **Not done / only reasoned:** nothing here has run on a device or against the deployed server. Asha's wallet tools
  and the call's money sheet use the selected network (the call sheet's group contribution goes through
  `PayViewModel`, so it follows the group). The treasurer-model link retry (`PendingLinks`) is still not per network.
  Circle's PIN screens name the group, not the network.

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
- Payloads (`core/qr/Qr.kt`): "On Circaso" codes are `circaso://pay/@handle`; wallet codes are, since 2026-11-24, a
  Circaso pay link (`QrPayload.forReceive` → `PayLinks.of`; the bare address when the chain isn't offered or the
  address doesn't check out; see "Pay links"). Both receive codes carry the Circaso mark (`QrCode(mark = true)`, error
  correction H). `QrPayload.read` parses pay links, EIP-681, Solana Pay, plain addresses and `@handle` (see "Send to a
  Wallet, rebuilt" for what it fills in).
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
  `chats-moments-offline`, `chats-moments-error`, `chats-moments-posting`, `chats-moments-preparing`,
  `chats-moments-failed`, `chats-moments-prepare-failed`, `chats-rings` (`gallery/MomentsPreviews.kt`).
- **Posting (2026-11-14, like WhatsApp status):** Post records the moment and closes the composer; a video is got
  ready (made smaller when over 32 MiB) and sent by `MomentsRepositoryImpl` in the app's scope, in order, resumed
  after a restart, with one determinate ring for the batch. See `project-status.md` → "Posting a moment".

## One passkey per account: the PIN Vault and the group key backup (2026-11-22, branch `fix/one-passkey-vault-and-backup`)

Both features seal under a circaso.com passkey's PRF secret (guide V1–V4, KB1–KB7), with the same relying party and
the same WebAuthn `user.id` (the account id). Google Password Manager and iCloud Keychain keep **one** passkey per
site and person: making a second one replaces the first, and whatever the first sealed can never be opened again.
That happened on a real account (the vault under one passkey, the backup root under an earlier one that no longer
existed), so:

- **The rule lives in `core/platform/AccountPasskey.kt` and nowhere else.** Neither repository calls
  `PasskeyPrf.create`. `AccountPasskey.secret(own, …)`: the asking feature's own record first; else the first passkey
  another record names (each wallet network's vault, the selected one first, then the backup root); a passkey is made
  only when no record names one, and never while a record couldn't be read (`Answer.Unknown`). Wired in `VaultModule`
  (the other networks' vault slots are read with `PinnedNetwork`).
- **Healing (`KeyBackupRepository.reseal`):** when a PRF secret arrives for a passkey other than the one the server's
  root names (`onPrfSecret` after a vault open or save, `turnOn`) **and this phone holds the root**, the same root is
  sealed again under the answering passkey and saved with KB2 + `expectedVersion`. The root key doesn't change, so
  backed-up group keys and saved names stay readable. Never without the local root, and never with a root that isn't
  the server's (proof: a backed-up group key that the phone's root opens; an account with no backed-up group key
  isn't healed).
- **`KeyBackupState.needsRepair`** (from `refresh`): this phone holds the root but the server's copy names another
  passkey than the vault does. The Security row then reads "Locked with a passkey you no longer use, so other phones
  can't restore" with **Repair** (= `turnOn`: one answer from the passkey in use).
- **A passkey that isn't here:** `PrfResult.NoPasskey` (Android `NoCredentialException`) →
  `KeyBackupProblem.PasskeyMissing` / `VaultProblem.NoPasskey`. Android's `NotAllowedError` ("User canceled the
  request", what backing out of "use another device" gives) is `Cancelled`, as on iOS where the two can't be told
  apart; so after a cancelled restore the sheet (since 2026-11-23; Security's row too) offers "Passkey not on this phone?" instead of claiming it. Copy is in
  `KeyBackupCopy` (tested).
- **Start a New Backup** (Security, behind a confirm sheet; `KeyBackupRepository.startNew`): KB3 then KB2 under the
  passkey this phone can use. Nothing is deleted before a passkey has answered; if the answering passkey opens the old
  root after all it is a restore, and if this phone holds the root it is a reseal. A passkey is made here only when no
  vault names one.
- **Not done:** iOS still reports a missing passkey as a cancel (`PasskeyImpl.swift` is untouched). A vault whose own
  passkey is gone has no in-app way out beyond Remove and save again. A phone holding a root from before backup was
  turned off and on elsewhere still uploads keys under it (older gap; `reseal` refuses to make it worse).

## The chat backup from the word go (2026-11-23, branch `feat/backup-from-the-start`)

Pasaka: "everything should be set up from the word go, to reduce the steps". After a reinstall his backup sat under a
passkey the phone no longer had, restore only offered "another device", and the way out was buried in Security. Now
one sheet does what this phone needs, right after signing in, with no new server routes.

- **Which sheet** (`BackupPromptViewModel.wanted`, from `KeyBackupRepository.status` after the launch `refresh`):
  no root on the server → **SetUp**; a root this phone doesn't hold → **Restore**; this phone holds a root under a
  passkey the vault doesn't use (`needsRepair`) → **Repair**. Unknown (offline at launch, unsupported phone) → none.
  One sheet at a time; once past its first step or busy it is held whatever the status does (a restore's "done" isn't
  swept away by `onPhone` turning true), and nothing follows it.
- **When:** only while `calm` (`BackupCalmReporter` in `AppNavigation`, its own scope under the Home entry, which tells
  the view model even when the Chats tab's overlay isn't composed): Home on top, the Chats tab, chats loaded, no Meet
  Asha, no call, no secret input (`SecretsOnScreen`), **no other sheet open** (`SheetsOnScreen` /
  `LocalSheetsOnScreen`: every `CircasoSheet` counts itself while visible unless `counted = false`, so the hold sheet,
  Leave Circaso?, Meet Asha and invites all count), no Asha bubble, no system permission prompt (`SystemPrompts.during`
  wraps the contacts and Android notification asks; iOS's push authorisation in Swift isn't wrapped), no in-app
  browser, no group-join or share sheet, no pending link. It rises `SettleMillis` (1.2 s) after the screen gets calm
  and steps aside (keeping its place) when it isn't. Money steps are pushed routes, so they are never under it.
  "Done" only counts down while seen (a call mid-passkey keeps it for later).
- **SetUp:** "Back up your chats" / "Turn On With {Face ID|Fingerprint|Screen Lock}" = `turnOn`: the vault's passkey
  if one exists, else a new one made in the same step (one system sheet); the vault then uses it (`AccountPasskey`).
  Done: "Your group names are backed up", closes itself after 2.4 s (never while TalkBack/VoiceOver is on:
  `rememberScreenReaderOn`). Titles say "chats" only where Pasaka named them ("Back up/Restore your chats"); the
  results say "group names", because message history isn't in the backup (no fake success).
- **Restore:** "Restore your chats" (+ "N groups show members' names…") / "Restore With …" = `restore`.
  `PasskeyMissing` → in the same sheet "Your passkey isn't on this phone" with **Use Another Device** (the restore
  again), **Start a New Backup** (→ the confirm step with `KeyBackupCopy.NewBackupTitle/Message`, then `startNew`), and
  Not Now. `Cancelled` keeps the sheet and adds "Passkey not on this phone?" (Android's back-out of the hybrid sheet and
  iOS's missing passkey both arrive as a cancel). Use Another Device answered "not here" again says "This phone
  couldn't offer another device…". `TurnedOff` re-reads and shows SetUp with a note why. Done: "Your group names are
  back" (or "N groups weren't in your backup…", counted once the chat list has been still for
  `SettleListMillis`, then fixed, 6 s), "Your new backup is on". Drag, scrim and Back mean Not Now, except on the
  confirm, where they go back to the choice as Cancel does. Errors and notes sit under the main button with their Ref
  (`SheetActions(noteRef)`), in a polite live region.
- **Repair:** healed silently the next time the passkey answers (the vault's `onPrfSecret` reseals); until then
  "Finish securing your backup" / "Repair With …" = `turnOn`. Done: "Your backup is secure".
- **Offline:** the sheet stays, its button can't be tapped and says "Needs a connection. You can carry on when you're
  back online."; it is live again once back. A passkey is never asked while the server can't be reached.
- **Not Now** (and Close, drag, Back before anything is done): quiet for 7 days (`QuietMillis`), kept per account
  and sheet in `AppPreferences` text `keybackup.quiet.<Sheet>` = "userId|until" (`PrefsBackupPromptMemory`, cleared
  on sign-out so the next sign-in is asked afresh). A restore put away stays on Chats as the quiet card
  (`BackupRestoreCard`: "Restore your chats" / "Restore With …", which runs the restore at once inside the sheet; its
  Not Now is quiet for 7 days too, key `keybackup.quiet.Card`). Security's "Group names backup" row stays the place to see and manage it.
- **Repository fix:** `KeyBackupRepository` now tracks whose root and status it holds (`ownedBy`): after a sign-out the
  last person's root stayed in memory and `onPhone` stayed true, so a new sign-in on the same run would neither be
  offered a restore nor back up under its own root.
- **Previews:** `backup-setup`, `-setup-busy`, `-setup-offline`, `-setup-error`, `-setup-done`, `backup-restore`,
  `-restore-groups`, `-restore-busy`, `-restore-cancelled`, `-restore-offline`, `-restore-error`, `backup-restored`,
  `-restored-left`, `backup-not-here`, `-not-here-busy`, `-not-here-offline`, `backup-confirm-new`,
  `-confirm-new-busy`, `backup-new-done`, `backup-repair`, `-repair-offline`, `backup-repaired`, `backup-card`,
  `-card-groups`, `-card-offline`, `backup-setup-turned-off`, `backup-not-here-again` (`gallery/BackupPreviews.kt`). Tests: `BackupPromptViewModelTest`.
- **Not verified:** real passkey sheets (needs a Play-signed build on a phone: the circaso.com asset links only match
  the Play signing key); whether Android's Credential Manager offers the QR/hybrid option when the backup's passkey
  isn't on the phone (it may answer `NoCredentialException` at once, which lands on the "not here" step anyway).
  The screen-reader check is read once when the layer is composed (turning TalkBack/VoiceOver on mid-session isn't
  seen until Chats is shown again).

## The verified badge (2026-11-24, branch `feat/verified-badge`; backend PR #144)

A person whose identity Circaso verified (an approved check in Didit's **live** environment; the testnet's sandbox
approvals never count) carries a small seal on their avatar wherever it appears, and their profile says "Verified".

- **Data.** The server sends `verified` (and `verifiedAt` on users' shapes) on `PublicProfileDto`, `MyProfileResponse`,
  `ChatProfileDto` and `CounterpartyDto`; a server without the field reads as false. Room v25 (auto-migration 24→25)
  adds `contact.verified`, `chat_member.verified`, `profile.verified` and `profile.verifiedAt`, filled wherever those rows
  are written from the server (contacts refresh, chat refresh, own profile). `users` / `profile.updated` already reloads
  a peer (`onPeerProfile`: U10, then `ChatsDao.updateMemberProfile` and `ContactsDao.updateVerified`) or my own profile,
  and the server now sends it when a badge comes or goes, so seals change live; otherwise the next fetch does it.
- **Payees in no contact or chat (2026-11-24, branch `feat/verified-gaps`).** Room v26 (auto 25→26) adds
  `transfer.counterpartVerified` and `wallet_activity.counterpartVerified` from W6 `counterparty.verified`
  (`CounterpartyDto.verified`, absent = false). After each W6 page (`syncHistory`, `loadActivityPage`, `refreshActivity`)
  `syncVerified` writes each person's answer onto **every** stored payment with them (`WalletDao.updateTransfersVerified`
  keyed on `COALESCE(counterpartUserId, toUserId)`, `updateActivityVerified`), so a seal taken back leaves older rows
  too; the server reads the badge when it answers, so any page is current. A row naming no person keeps what it had;
  `transfer.updated` / W5 answers keep it. `onPeerProfile` (`users` / `profile.updated`) also updates both tables. A payee
  who is no contact gets no frame (the server sends it to the person and their contacts), so for them the next W6 read
  (every wallet refresh) is what removes it.
- **Who is verified, for every avatar.** `ContactsDao.observeVerified()` is the union of verified user ids across
  `contact`, `chat_member`, `profile`, `transfer` and `wallet_activity`; `App.kt` provides it as `LocalVerifiedPeople`. `Avatar(..., userId = …)` draws
  the seal when that id is in the set (or `verified = true/false` overrides). Group avatars, stacks of faces and things
  pass no `userId`. Offline it shows what was last stored.
- **The seal** (`designsystem/components/Avatar.kt`, `VerifiedSeal`): a twelve-point rosette in the money card's titanium
  (`moneyCard` blended from `moneyCardSheen`, so graphite on light and brushed silver on dark), a check in `onMoney`.
  The gap around it is a real hole cut out of the picture (`sealCutOut`, `BlendMode.DstOut` on an offscreen layer), not a
  painted ring, so it reads on any ground (rows, sheets, glass, press states, photos). Black stages (calls, the Moment
  viewer) pass `onDarkStage = true` for the dark themes' silver whatever the theme. Size `sealSize(avatar)` =
  avatar × 0.22 + 6 dp within 12–28 dp; its centre sits on the disc's rim at 45° (`sealOffset`), inside the square, so
  nothing clips it. What must follow the circle (a clip for the round ripple, a shadow, a ring, the You hero's press)
  goes in `discModifier`. Member lists are replaced in one transaction (`ChatsDao.replaceMembers`) and the set is
  `distinctUntilChanged`, so seals don't blink during a chat refresh. Screen readers hear "Verified".
- **Profiles** (`designsystem/components/Verified.kt`, `VerifiedPill`): a quiet capsule (seal + "Verified") under the
  name on a direct chat's info screen, in the contact sheet (meaning written out under it, no second sheet) and on my
  own You hero. A tap opens a sheet: "Circaso verified this person's identity with a government ID and a selfie. Only
  the badge is shown, never their ID or selfie." (my own: "…your identity… People see only the badge, never your ID or
  selfie."). The pill fades and expands in (`VerifiedPillReveal`), still under Reduce Motion. My own, when not verified, keeps the Verification row
  (You → Money) as before.
- **Not done:** gallery previews of the seal across the four themes; the seal's "Verified" is its own screen-reader stop
  where a name sits beside an unmerged avatar (group sender avatars, the call stage). The test suite still needs the
  assistant's `*MaintenanceTest` classes left out (they hang, known since 2026-11-22).
