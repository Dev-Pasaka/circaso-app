# Circaso app: project status

Snapshot of 2026-11-03. Every design decision, what is built, what is pending, and known bugs, in one place.
Lives in the `circaso-mobile` skill (`references/project-status.md`). Deeper detail: `SKILL.md`,
`references/app-architecture.md`, `references/design-spec.md` §15, `references/offline-first.md`,
`references/spaces-plan.md` (the next feature) and `docs/adaptive-layout-plan.md`.

## 1. Where the code is

- Repo: github.com/Dev-Pasaka/circaso-app, branch `main`. Pushed up to `e823a7f` (presence, 2026-10-31); committed
  locally on 2026-11-03 and **not pushed yet**: `b7ef3ea` (icon and splash) and the skill docs commit after it.
- **Committed in `b7ef3ea`, not yet seen on a device**:
  - Circaso mark as the Android notification icon: `shared/src/androidMain/res/drawable/ic_stat_circaso.xml`, used by
    every `setSmallIcon` (chat, money, incoming call, ongoing call, live location) and set as Firebase's
    `default_notification_icon` in `androidApp/src/main/AndroidManifest.xml`.
  - Sharp launch splash: `androidApp/src/main/res/drawable/splash_icon.xml` is an empty `<animated-vector>` around
    `splash_mark_vector.xml`; `drawable-nodpi/splash_mark.png` deleted. See decision D-UI-9.
  - To finish: cold-start the app and send a push with the app in the background on a device.
- Backend: separate repo (`circaso`), sandbox server `/opt/circaso`. PRs #12–#22 merged and live.

## 2. Stack and architecture decisions

| # | Decision | Why / note |
| --- | --- | --- |
| D-A1 | Kotlin Multiplatform, one `shared` module, packages by feature; Compose Multiplatform UI on our own design system (Foundation, not Material). | One codebase, premium custom look. |
| D-A2 | Navigation 3 (typed `@Serializable` keys), MVVM (one ViewModel + one `StateFlow<UiState>` per screen), Koin DI, clean architecture (presentation / domain / data). | Agreed 2026-10-25. |
| D-A3 | Ktor only (OkHttp on Android, Darwin on iOS), kotlinx.serialization; no second HTTP library. | |
| D-A4 | Room KMP for data, DataStore only for non-secret settings; tokens and device key in our own Keystore / Keychain wrappers. | No generic secure-storage library. |
| D-A5 | **Offline-first is a product requirement**: Room is the read source, safe writes go through an outbox; **money is never queued or optimistic**; never fake success; never sign out because of the network. | Trust is the product. |
| D-A6 | Backend contract: `mobile-integration.md` wins; when the live server disagrees, the server wins and the guide is fixed. Design wins for look and copy. Features with no backend sit behind a flag, never a made-up endpoint. | |
| D-A7 | Platform code behind `expect/actual` or bridges (libsignal, Circle, WebRTC, live audio, QR, maps all use the bridge pattern on iOS). | |
| D-A8 | Every stateless `*Content` composable has a debug preview: Android `--es preview <name>`, iOS `-preview=<name>` (names in `gallery/PreviewScreens.kt`). | Lets us check screens without signing in. |
| D-A9 | **New libraries need Pasaka's approval first** (name, version, reason). | Planned stack. |
| D-A10 | Circle wallet SDK lives in its own module (`:circlewallet`; iOS `CircleWalletImpl.swift`); shared code only knows `WalletSigner`. | Vendor swappable, clear trust boundary. |

Approved libraries so far: Navigation 3, Koin, Room KMP, DataStore, libsignal, Circle SDK (Android 1.0.1192, iOS pod
1.4.5), GoogleSignIn 10.1.0 (local podspec under Circle's 7.1.0 pin), qrose 1.3.1, play-services-code-scanner 16.1.0,
stream-webrtc-android 1.3.10 / stasel WebRTC 155.0.0, Google Maps (maps-compose, play-services-maps, GoogleMaps pod),
Media3 1.4.1, multiplatform-markdown-renderer 0.46.0.

## 3. Product and design decisions

### Identity and security
- D-P1 Sign in with Google or Apple only, then a verified phone number, then a hardware device key. No passwords.
- D-P2 **No recovery key or PIN in sign-up** (2026-10-25, overrides design ID-08–ID-11, ID-16–ID-20): the wallet key and
  PIN are created by Circle at the first money action. A 12-word key with nothing behind it would be theatre.
- D-P3 One phone per account; a new phone signs the old one out. After a restore, payments pause (ID-23 hold banner).
- D-P4 Account deletion is final after 30 days; the app never promises a restore.
- D-P5 Lost-account recovery is a support case (`POST /v1/recovery/requests`, SP06).
- D-P6 The name is **Circaso** everywhere; never ship "CoinX".

### Money
- D-M1 Circle user-controlled wallets: the server makes challenges, the person signs with a PIN in Circle's SDK; the
  server never holds keys. Testnet on dev (Base Sepolia default, USDC). Circle's own social login removed.
- D-M2 Amounts are decimal strings, never floats. Fiat is the phone's region currency (USDC = 1 USD), credited "Rates by Exchange Rate API".
- D-M3 Pay from chats: money button in the direct thread header, `#500` in the composer opens Pay with 500 filled.
  Payments travel as an encrypted `payment` message; the note never reaches the wallet API.
- D-M4 **Chamas use the treasurer model**: the goal's money sits in the treasurer's own wallet; payouts need two admin
  approvals (every admin if fewer) and the treasurer pays; the request link is persisted until the server has it, so no one pays twice.
- D-M5 Add money, KYC, cash out (E2, E3, E6) and business groups (D7): **honest "coming" sheets** until licensed partners
  are chosen; Cash out offers "Send to an Exchange". No fake flows.
- D-M6 Money sound on payment received (in-app and as its own notification channel), can be turned off (You → Money sound).
- D-M7 Wallet UI: metallic cards that fan out like a hand of cards; two assets per network with See All; searchable
  assets and payments; currency setting.

### Chats
- D-C1 End-to-end encrypted with libsignal (PQXDH); server only sees public keys and ciphertext.
- D-C2 Chats open at the latest message; older messages load in pages of 100; jump-to-latest button.
- D-C3 Filter chips All / Unread (round count badge) / Groups.
- D-C4 Presence: Online / last seen today, yesterday, weekday or date; **never assumed online**; away 15 s after leaving the app.
- D-C5 Media: photos, in-app WhatsApp-style gallery, voice notes, video (Media3 / AVKit), location.
- D-C6 Live location WhatsApp-style (15 min / 1 h / 8 h), keeps updating in the background (Android foreground service
  with Stop; iOS background location), updates travel encrypted; full-screen map with profile-photo pins.
- D-C7 Maps styled monochrome from theme tokens. **The Static Maps key never ships in the app**: previews come from the backend proxy `GET /v1/maps/static`.

### Calls
- D-K1 Cloudflare Realtime (SFU + TURN), WebRTC on the phones. Labelled **"Secure call"**, never "end-to-end encrypted".
- D-K2 One call at a time; voice can switch to video without renegotiating; group grid up to 8; contribute presets during a group call.

### Asha (assistant)
- D-AS1 `/asha` typed in any text field opens Asha, except secret fields (PIN, recovery words). (Open: any field or composer only?)
- D-AS2 Rich answers: markdown, native charts, statements with Share as PDF, pencil diagrams, live "what I'm doing" status.
- D-AS3 Live voice over its own WebSocket (Opus up, mulaw down, barge-in); clips only for Android < 10 and Swahili.
  Voice by Kimi; selectable voices with samples; separate conversations (new, past, delete).
- D-AS4 Allowance numbers are whatever the server says; nothing hard-coded.

### Support and admin
- D-S1 Support is a dedicated, verified **support account kind**: no wallet, can't sign in on the phones; money is off in
  support chats ("Circaso support never asks for money").
- D-S2 The team uses an **admin site** (Next.js on Cloudflare, Google Workspace sign-in, backend `ops` API) covering
  support chats plus users (who is signed in now), transactions, groups. A server-side support service holds the support
  account's Signal keys (official `libsignal-client`); the app says "Support chats are read by the Circaso support team."
- D-S3 **Admin site and support service are ON HOLD: mobile first** (Pasaka, 2026-10-31). Phase A (account kind + guards) is done.

### UI and motion
- D-UI1 Follow the design frames exactly (copy, layout, tokens, all states); where the design lacks something, decide at
  the premium standard and note the departure.
- D-UI2 Four themes (light, dark, light high-contrast, dark high-contrast) and Dynamic Type; tokens by role, never raw hex.
- D-UI3 Run the `design-reviewer` agent after any screen change and fix its Blockers.
- D-UI4 Adaptive: split view at ≥ 840 dp (360 dp list), short = height < 480 dp, tabs become a left glass rail on short or
  wide screens, content framed (680 dp one column, 900 dp two-column landscape) while backdrops go edge to edge, spring push/pop.
- D-UI5 Top bars move to a left column on a phone on its side.
- D-UI6 Profile hero glows with the photo's own colours; tap the photo to open it large.
- D-UI7 QR codes always dark on white inside the raised tile, so they scan in every theme. "On Circaso" codes are `circaso://pay/@handle`.
- D-UI8 Heads-up pushes: messages use the high-importance channel so they pop over other apps.
- D-UI9 Android splash icon is an `animated-vector`: Android 12+ draws a still icon into a 108 dp bitmap and stretches it
  to 192 dp (blurry); an Animatable icon is drawn live at full size (verified in AOSP `SplashscreenIconDrawableFactory`).
- D-UI10 Circaso mark as the notification icon on Android (iOS uses the app icon).

### Out of scope
- Website (W1/W2) left out. Old first-launch screens A3–A8 superseded by the ID pages.

## 4. Built (app)

| Area | What works |
| --- | --- |
| Launch and sign-in | Splash held until session and theme are read (1.5 s cap), Welcome, Google (both platforms) / Apple (iOS), phone verification, device key, new phone, force update. |
| Account and security | Profile edit, avatar, security screen, biometrics, security log, sign out, delete account (blocked while money is in the wallet), contacts, blocked people. |
| Chats | Encrypted direct and group chats, roles, receipts, typing, delete for everyone, filter chips, paging, presence, Android push with heads-up, local notifications (F4). |
| Media | Photos, gallery, voice notes, video, location, live location with background updates. |
| Calls | Voice, video, group grid, incoming call screen, Android call notification from the data push, minimised call pill. |
| Wallet | Create (Circle PIN), balance per network with fiat, Send / Receive (QR show and scan), pay from chat, send to an address, payment detail, history, networks, money cards in chat, quick replies, currency setting, coin icons, money sound. |
| Chamas | Goal strip, contributions, payout requests and approvals, group wallet screen, intro card. |
| Asha | Text and live voice, rich answers, PDF statements, conversations, voice picker. |
| Support | Help screen, support chat UI (needs the support account), recovery case (SP06). |
| Adaptive | Phones portrait and landscape, iPad 11"/13" checked on simulators. |

## 5. Pending tasks

### Blocked on Pasaka
1. ~~Cloudflare Workers Paid plan~~ **Done by Pasaka (2026-11-03).** Checked the same day: Asha's three Workers AI models answer `200` with the paid plan: Kimi K2.6 (text, about 4.9 s), Aura-2 (speech) and Nova-3 (transcription; Aura's audio came back word for word). The `429` is gone. Not yet run end to end through the app (the emulator wouldn't connect over adb).
2. **Rotate the Tavily API key** (it was pasted in chat).
3. **iPhone install**: unlock the phone and turn on Developer Mode.
4. **Calls on the sandbox**: set `INTEGRATIONS_CALLS_PROVIDER=cloudflare`, `CLOUDFLARE_REALTIME_APP_ID`, `CLOUDFLARE_REALTIME_APP_TOKEN` (optional TURN key) if not done yet.
5. **iOS push**: upload the APNs key in the Firebase console and add the Push Notifications capability.
6. **Create the Circaso support account** (or green-light the support service), so Help has someone to talk to.
7. Answer open design questions in §7.

### Next work (suggested order)
1. Check the splash and notification icon on a device; commit.
2. Merge the backend presence PR (`presence.changed` frames); until then Online / last seen shows only when the server sends it.
3. **iOS push** in the app: APNs token (`IosKoin` uses `NoPushToken` and `NoChatNotifications` today) and the Notification Service Extension that decrypts messages (M7).
4. **iOS calls in the background**: CallKit + PushKit VoIP push (iOS only rings while the app is open).
5. Release readiness: run the R8 release build on a device (Room, Credential Manager and Ktor use reflection), add a baseline profile, re-measure cold start (target well under 1 s).
6. Refresh `mobile-integration.md` from the backend repo (the copy is from 2026-10-28; media, history and calls have moved since).
7. Adaptive: check signed-in flows on a real tablet and on a phone on its side.
8. Smaller: collapsing nav bar on scroll (`NavBar.kt`), SMS code auto-fill, H2 "Add people" to a call (needs a backend route), `goal` field in the encrypted `payment` payload.

### New feature in planning: Spaces (added 2026-11-03)
New **Spaces** tab between Chats and Wallet: WhatsApp-style statuses for mutual contacts on top, public spaces
(follow = join, live rooms, a space wallet to pay into) below, and **group and space wallets** separate from personal
ones (chamas and groups merge into Groups; a wallet is optional for groups and required for spaces; biometrics per
wallet; a target needs a wallet). Shops, paid memberships, fees (0–2 % on the first
$10,000, sender pays, gas sponsored and kept ≤ 25 % of the fee) and group daily caps (10,000 USDC) are part of it. **All decisions are taken; next is phase 0,
design.** Plan: `references/spaces-plan.md`.

**Phase 0 progress (2026-11-03):**
- Fee spike done (`references/fee-spike.md`): **batch** (`executeBatch` on the sender's SCA, one challenge, atomic) on
  Base, Polygon and Ethereum; **Solana waits** (0 % rule) until a second spike.
- Phase 0a frames: `Circaso-Spaces.html` at the root (copy in `assets/`, 74 frames, built by
  `scripts/build_spaces_canvas.py`): Spaces tab, Moments composer, viewer and privacy, groups with wallets, Paying from,
  the fee line, daily caps, and after-signing states. The design-reviewer's first pass found 3 Blockers, now fixed. The
  frames are **waiting for Pasaka's review**; phase 1 (Moments, backend and both apps) starts once he approves them.

### Backend gaps (design exists, no API yet)
- W7/W8: statements on the server, PIN change and restore.
- Server notification inbox (F4 is built locally), KYC, add money, cash out, business groups (D7).
- Device attestation (Play Integrity / App Attest): **production can't register devices until it exists**.
- `identity.code_invalid` tries left only in the English message: ask for `details.triesLeft`.
- Account deletion doesn't yet clean up messaging data; no route restores an account during the 30 days.
- Support service + admin site (on hold).

## 6. Known bugs and issues

| Issue | State |
| --- | --- |
| Opening a chat crashed (message window read before it was set) | **Fixed** in `acfe332` (2026-10-31). The "iOS app won't start" report afterwards was a stale simulator build; a clean build fixes it. |
| Blurry first splash logo on Android | Fix written (D-UI9), **not yet verified on a device**. |
| Android notifications showed stock system icons | Fix written (D-UI10), **not yet verified on a device**. |
| Asha returned 429 | **Fixed** by the paid plan (2026-11-03): all three models answer 200 when called directly. |
| iOS gets no pushes and doesn't ring in the background | Not built (see Next work 3 and 4). |
| Android emulator `Pixel_9_Pro` stuck "offline" in adb after cold boots | Environment issue. Two adbs are installed (Homebrew 37.0.0 and SDK 37.0.1); use the SDK one. API 37 headless falls back to software rendering (swangle) and never finished booting on 2026-11-03; next tries are `-gpu host`, then Wipe Data (this loses the signed-in test account). |
| Real SMS send via Twilio not confirmed end to end | Backend says to test with a real number and report the `code`. |
| ID-13 shows a masked number but the server needs the full number | The returning person types it. |
| Welcome screen's terms line has no links | Needs the terms and privacy URLs. |
| Circle SDK on Android pinned at 1.0.1192 (1.1.2 exists) | The bridge targets 1.0.1192; upgrade later deliberately. |
| iOS must ship Facebook's SDK (Circle's binary links it) | Switched off in Info.plist (no app id, no auto-init, no tracking). |

## 7. Open design questions

- Is `/asha` meant for any text field or only the chat composer?
- "Forgot your PIN" (ID-24b): Circle recovery flow vs recovery key plus number check.
- Should a large payment require Face ID / biometrics above an amount?
- Rooted or jailbroken phones: warn, limit payments, or allow silently?
- Asha's free allowance and paid plan (Asha Plus): not decided on the server.
- Remaining "CoinX" strings in the design copy (assistant, support, notifications, email sender).
- Treasurer model for chamas: backend decision of 2026-10-29, for Pasaka to confirm.

## 8. Testing notes

- Android: Pixel 9 Pro emulator (`emulator -avd Pixel_9_Pro -no-window -no-snapshot -no-boot-anim -no-audio`, run in
  the background). Pasaka's Redmi is a **personal phone**: only use it when asked; check Circaso is in front before any
  tap or screenshot.
- iOS: `iosApp/iosApp.xcworkspace` (CocoaPods), simulator "Circaso iPhone 17 Pro"; iPads "Circaso iPad Pro 11/13";
  simulator builds are arm64 only.
- Android Circle SDK needs `pwsdk.maven.username` / `pwsdk.maven.password` in `local.properties` (never commit).
- Tests: `./gradlew :shared:allTests` (common, Android host and iOS).
