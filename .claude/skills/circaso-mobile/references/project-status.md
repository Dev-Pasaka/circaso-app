# Circaso app: project status

Snapshot of 2026-11-05. Every design decision, what is built, what is pending, and known bugs, in one place.
Lives in the `circaso-mobile` skill (`references/project-status.md`). Deeper detail: `SKILL.md`,
`references/app-architecture.md`, `references/design-spec.md` §15, `references/offline-first.md`,
`references/spaces-plan.md` (stopped; the record for Moments, group wallets and fees) and `docs/adaptive-layout-plan.md`.

## 0. Current focus (Pasaka, 2026-11-05)

**Spaces is stopped.** Focus is chats, groups, chamas and wallets: **polish, premium feel and optimisation; no new
features.** Spaces was removed from the app on 2026-11-05 (branch `worktree-agent-a0d7b47b9255ac3c1`; last commit with
Spaces tagged `archive/spaces-v1`; Room v20 drops its tables); the backend keeps it archived behind
`SPACES_ENABLED=false` with its data. Why, what was removed and how to restore: `spaces-plan.md` → "STOPPED 2026-11-05".
- **Tabs:** Chats, Wallet, You (no Spaces tab).
- **Moments** stay, WhatsApp-style in Chats: a **Moments** chip after All / Unread / Groups (a dot while there are unseen
  moments) swaps the list for My moment, Recent, Viewed and Muted (folded); the floating + becomes the camera; a person
  with an unseen moment has a ring on their chat avatar, and the avatar opens it. Privacy: You → Security → Moments.
  See `app-architecture.md` → "Moments in Chats".
- **Stays from the Spaces plan:** groups with optional wallets, the one Circaso fee, daily caps, Paying from, the
  wallet PIN and biometrics per wallet.

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
- D-M10 **Any priced coin in the person's money** (Pasaka, 2026-11-06; backend PR #48, CoinGecko): W1's assets carry
  `usdPrice` (dollar coins are 1 even without it). `#500 @eth` stays KES 500, turned into ETH at usdPrice × the wallet's
  rate with string math, rounded down; the card shows the coin as its sub-line ("0.001105 ETH · Fee KES 4.52"); typing
  in the coin still works; a coin with no price falls back to coin units. Prices are display and typing only, never a
  payment or fee. Credit "Prices by CoinGecko" with the rate line. Code: `unitFiatRate`, `coinToFiat`,
  `fiatToCoinMinor`, `coinShort` in `WalletModels.kt`; `PayUiState.unitRate`.
- D-M3 Pay from chats: **money starts and ends in the chat** (Pasaka, 2026-11-04): `#500 @usdc for lunch` makes the
  chat's money card, which is the whole review (who, amount in currency and coin, network, Paying from, the note,
  the fee, what they receive); Pay goes straight to the Circle PIN. No review page, no note field. The card in the
  thread says Sending until confirmed, then Sent; Not sent with Try Again. Payments travel as an encrypted `payment`
  message; the note never reaches the wallet API. See `app-architecture.md` → "Money in the chat".
- D-M4 ~~Chamas use the treasurer model~~ **Retired by Spaces phase 2 (2026-11-03)**: chamas and groups are one Group; a
  group may have its own wallet (its own Circle user, own PIN and biometrics), the owner is its only signer and pays
  only payouts the admins approved (two admins, or any one admin, CH9). The treasurer flow stays in the app only for a
  server without group wallets (the flag off). See D-M8.
- D-M8 **Group wallets behind a server-checked flag** (`GroupWalletsFlag`): on in debug builds, off in release, and off
  by itself on a server whose W15/W16 answer 404; `walletId`, `payoutRequestId` and `to.groupId` never go to a server
  that hasn't answered a group-wallet route (an old one would pay from the personal wallet). Turn the release default
  on when production runs PR #28.
- D-M9 **One wallet design for every wallet, switched from the corner** (Pasaka, 2026-11-04, overriding GW16's title
  switcher and the separate group wallet screen): the Wallet tab has no title; a compact control in its top-right
  corner (wallet glyph, the wallet's short name, a chevron; 36 dp pill in a 44 dp target, like the bars' icon
  buttons) opens a popover anchored to the corner (not a sheet) with every wallet the person can see: picture or
  initials, name, what they can do with it, the last balance read, a tick on the one on screen, "Locked" when its
  signer left. A group wallet is drawn by the personal wallet's own screen (cards, actions, assets, Recent); only its
  money and payments change, plus its caption, Add / Payout (Send for a space) / Receive / Target (admins), target
  progress and payout requests, all inside the same layout. `GroupWalletRoute` (group info, goal strip) pushes the
  same screen under a bar, without the switcher (the person came for that group). Flag off: no switcher at all.
- D-M5 Add money, KYC, cash out (E2, E3, E6) and business groups (D7): **honest "coming" sheets** until licensed partners
  are chosen; Cash out offers "Send to an Exchange". No fake flows.
- D-M6 Money sound on payment received (in-app and as its own notification channel), can be turned off (You → Money sound).
- D-M7 Wallet UI: metallic cards that fan out like a hand of cards; two assets per network with See All; searchable
  assets and payments; currency setting.

### Chats
- D-C1 End-to-end encrypted with libsignal (PQXDH); server only sees public keys and ciphertext.
- D-C2 Chats open at the latest message; older messages load in pages of 100; jump-to-latest button.
- D-C3 Filter chips All / Unread (round count badge) / Groups / Moments (a dot for unseen moments; swaps the list for
  the Moments list in place, 2026-11-05).
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
- D-AS5 (2026-11-05) No mascot: the Rive octopus (Asha/Robbie) is removed with its runtime and assets; Asha is her
  mark. Her chat header has a menu (⋮ Android, ellipsis iOS) opening one sheet: New chat, Recent chats, Usage &
  credits, Settings (voice). Voice mode shows the call as a chat transcript under an animated logo whose waves follow
  the real mic / playback level. See `asha-next.md` (top note).

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
| Chamas | Goal strip, contributions, payout requests and approvals, group wallet screen, intro card (treasurer model, flag off). |
| Groups with wallets (flag) | New Group with the wallet switch (GW1), group wallet set-up with its own PIN and biometrics (GW2–GW5c), group info money rows (GW6–GW8b, locked, leaving blocked), target (GW7b), one wallet design for every wallet with the corner switcher (GW16 as redrawn 2026-11-04, D-M9), contribute (GW9a2/GW9d), Pay Payout (GW9, GW11, GW19), Paying from and Request Payout (GW9b, GW9c, GW10). Not yet on a device. |
| Moments | Composer (text, camera, gallery, voice), viewer, replies in chat, reactions, privacy, contact-saved rule, push; shown in Chats under the Moments chip with rings on chat avatars (2026-11-05). |
| Asha | Text and live voice, rich answers, PDF statements, conversations, voice picker; menu sheet (new chat, recents with delete, usage & credits, settings); voice mode transcript with the animated logo (2026-11-05, mascot removed). |
| Support | Help screen, support chat UI (needs the support account), recovery case (SP06). |
| Adaptive | Phones portrait and landscape, iPad 11"/13" checked on simulators. |

## 5. Pending tasks

### Blocked on Pasaka
1. ~~Cloudflare Workers Paid plan~~ **Done by Pasaka (2026-11-03).** Checked the same day: Asha's three Workers AI models answer `200` with the paid plan: Kimi K2.6 (text, about 4.9 s), Aura-2 (speech) and Nova-3 (transcription; Aura's audio came back word for word). The `429` is gone. In the app (emulator, signed in): Asha answered a question in full, and Read aloud fetched speech and played it. Live voice (microphone) not tried: the emulator runs without audio.
2. **Rotate the Tavily API key** (it was pasted in chat).
3. **iPhone install**: unlock the phone and turn on Developer Mode.
4. **Calls on the sandbox**: set `INTEGRATIONS_CALLS_PROVIDER=cloudflare`, `CLOUDFLARE_REALTIME_APP_ID`, `CLOUDFLARE_REALTIME_APP_TOKEN` (optional TURN key) if not done yet.
5. **iOS push**: upload the APNs key in the Firebase console and add the Push Notifications capability.
6. **Create the Circaso support account** (or green-light the support service), so Help has someone to talk to.
7. Answer open design questions in §7.

### Next work (suggested order)
0. **Focus (2026-11-05): polish, premium feel and optimisation of chats, groups, chamas and wallets; no new features.**
   First: run the Moments-in-Chats change and the Room v20 upgrade (an install with Spaces data) on a device.
1. Check the splash and notification icon on a device; commit.
2. Merge the backend presence PR (`presence.changed` frames); until then Online / last seen shows only when the server sends it.
3. **iOS push** in the app: APNs token (`IosKoin` uses `NoPushToken` and `NoChatNotifications` today) and the Notification Service Extension that decrypts messages (M7).
4. **iOS calls in the background**: CallKit + PushKit VoIP push (iOS only rings while the app is open).
5. Release readiness: run the R8 release build on a device (Room, Credential Manager and Ktor use reflection), add a baseline profile, re-measure cold start (target well under 1 s).
6. Refresh `mobile-integration.md` from the backend repo (the copy is from 2026-10-28; media, history and calls have moved since).
7. Adaptive: check signed-in flows on a real tablet and on a phone on its side.
8. Smaller: collapsing nav bar on scroll (`NavBar.kt`), SMS code auto-fill, H2 "Add people" to a call (needs a backend route), `goal` field in the encrypted `payment` payload.

### ~~New feature in planning: Spaces (added 2026-11-03)~~ Stopped 2026-11-05

**Spaces stopped and removed from the app on 2026-11-05** (see §0 and `spaces-plan.md` → "STOPPED 2026-11-05").
What follows is the history of the Spaces work, kept for the record; the Spaces tab, spaces core (phase 3) and space
wallets described in it are gone from the app. Moments (phase 1), group wallets (phase 2) and fees and caps (2F) stay.

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
  frames were **approved by Pasaka (2026-11-03)**.
- Two later corrections from Pasaka are in the frames: money starts in the chat (`#amount`, `@asset`, the note in the
  message) and the sheet only reviews (fee, total, Paying from); `#` in a group with a wallet contributes to it.

**Pasaka's review of the built Moments (2026-11-03), done in `e59fb67`/`b628ab8`:** the plus on my ring opens the
composer; recents strips from the phone's library in photo, video and voice modes (WhatsApp-style) plus the system
picker for photos, videos and audio files; text moments get ~40 backgrounds (solids and gradients) and six fonts,
**replacing the monochrome four** (a moment is the person's content, so its palette is fixed, not theme tokens);
moment video has no player UI at all (bare surface, hold to pause, one mute button); video tiles show thumbnails.
Nothing to show is never an error screen. The first moment was posted through the sandbox after PR #26 deployed.

**Phase 1 progress (2026-11-03): Moments built, posting works on the sandbox.**
- Backend: `status` module (ST1–ST8, frames, 10-minute retention sweep) on branch `feat/status-moments` in the
  worktree `circaso-status` (commits `9d0c5e7`, `32b93c9`; `./gradlew build` green). **PR #26 open (2026-11-03), not yet merged or deployed.**
  Contract: `references/moments-api.md`; the backend guide copy is in that branch's `docs/mobile-integration.md`.
- App (`fc6779f`, `10f6147`, `aad3d2d`): Spaces tab (4 tabs), Moments data layer (Room v13/v14, outbox posting,
  sender-key encryption in the Signal store, encrypted media), composer (CameraX 1.6.2 on Android, AVFoundation on
  iOS), viewer, privacy (You → Moments), replies that quote a moment in the chat. Android and iOS compile; Android
  host tests pass.
- App commits pushed to `main` (up to `a30c06b`, 2026-11-03).
- To finish: merge and deploy backend PR #26, sign in on the emulator, post and view a moment between two
  accounts; design-reviewer pass on the built screens.
- Design review of the built screens (2026-11-03): its 6 Blockers are fixed (no "sent" before the server has a
  reply; the quote names the poster and goes "no longer available"; media hashed and decrypted off the main thread;
  video at 3.5 Mbit/s so 60 s fits the 32 MiB limit, with a size check and plain copy; hold-to-record that can't leave
  the camera or mic running; the voice moment as designed with listen-back, 1:00 cap and slide to cancel). Also fixed:
  view count from the server, the heart undone when not taken, Only-share-with needs someone.
- Composer and posting gaps closed (2026-11-03, branch `worktree-agent-ad0e9b22a8d9ac97c`): each library kind asks for its
  own access (Android 13+ photo access says nothing about videos; Android 14 "selected photos and videos" is Limited
  with a Manage tile; access off says so with Allow access); gallery M3 (All/Photos/Videos, numbered ticks, up to 10,
  each its own moment posted in order) and multi-pick on the strip (hold a tile); M3c limited/off states with the
  system picker as a fallback; trimmer M3b (Media3 Transformer, already a dependency, on Android; AVAssetExportSession
  on iOS; no new library), opened by itself for videos over a minute; a too-large video is re-encoded before posting;
  determinate posting ring from real bytes sent (Ktor `onUpload` on Android, `didSendBodyData` in the background
  URLSession on iOS); Forward (V4) from my moment's viewers sheet and from the composer review, a full-screen chat
  picker (up to 5 chats) through `MediaMessages`; composer and forward ViewModel tests, Android previews. The black
  video review on the emulator was the emulator's `c2.goldfish.h264.decoder` failing on its first buffer (Codec2
  BufferPool error 14); `MomentVideo` now retries without a decoder that failed (software decoder) and shows the
  thumbnail until the first frame renders, and says so if the phone can't preview it.
- Voice moments (2026-11-04): the waveform is the person's own, sampled from the recorder's meter while recording
  (Android `MediaRecorder.getMaxAmplitude`, iOS AVAudioRecorder metering; no Visualizer, no new permission) and
  sent as 48 bars (0..100) in the encrypted body (`levels`); it fills as the voice plays, the bars at the playhead
  lift, it freezes when held or paused, and only the fill moves under reduced motion. Older moments and picked
  audio get a gentle procedural wave. Voice moments take a background from the text palette (default Metal, as
  before), chosen on the review and drawn by the viewer. `AudioPlayer` gained progress, pause and resume.
- Still open from that review (should-fix, before release): V3 voice viewer and V7 "no longer available"; "Message X" in More (V1b); the
  viewers sheet doesn't scroll; shade gradients behind text on media; Ask Asha and Search in the Spaces header; the
  Spaces tab dot; S1e "not downloaded" ring; offline first open says "No moments yet"; the heart and Delete should be
  disabled offline (or queued); a decrypted moment's media isn't deleted after 24 h; hold doesn't pause video or voice;
  the reply draft should follow the moment; no prefetch of the next moment; drag-to-close and ring transitions;
  accessibility actions in the viewer; proper icons (More, Lock, Heart, People, Mute, ChevronDown); privacy previews.
- Viewer finished (2026-11-03): **reactions use the chats' WhatsApp-style picker** (now the design-system
  `ReactionPicker`, shared with ThreadScreen): the heart opens it above the button, any emoji, tapping mine takes it
  back, the moment pauses while it is open. **Departure from V1, decided by Pasaka:** V1's heart sent a quiet ❤️ in one
  tap; now a ❤️ is two taps (heart, then ❤️) in exchange for any emoji, as in chats. The heart looks off and says
  "Needs a connection" offline. **Drag down to close** follows the finger, springs back under 22 % of the height,
  closes past it or on a fast fling, and pops without the sideways slide (`dropDown()` / `dropTransition`).
  Previews: `-preview=moment-text|moment-photo|moment-video|moment-mine|moment-mine-viewers|moment-react|reaction-picker|reaction-picker-expanded`.
  ViewModel tests in `MomentViewerViewModelTest`. Open: a radius/elevation token for the picker bubble (26 dp, 10 dp
  shadow; ask Pasaka), composer and privacy previews.
- **Key request for a new phone (2026-11-04)**, app branch `worktree-agent-a484d47e0ab67d8f2`, backend branch
  `feat/moment-key-request` (PR #29, **not merged or deployed**; the app's requests answer 404 until it is, which
  the app treats as "ask again at the next refresh"). Why: signing in again makes a new Signal identity, so every
  key copy sealed before is unreadable on the new phone, and the poster's phone still counted that person as holding
  its moments key (deliveries are per user, not per identity), so later moments stayed unreadable too. Now: after a
  refresh the viewer asks each poster whose live moments it can't open (`POST /v1/moments/keys/request`, once per
  unreadable moment, at most every 10 minutes per poster, never for my own); the poster gets
  `status.key_requested`, and through the outbox (`moment.key_copies`) refreshes the 1:1 session to the requester's
  current identity (`SessionCipher.refresh`), forgets their delivery, seals each live moment's **body** again for
  them (a `momentBody` 1:1 payload; a sender-key copy made now could not open moments already posted, the chain only
  moves forward) and sends `POST /v1/moments/key-copies`; the viewer gets `status.keys_ready`, refreshes, and
  `MomentKeys.learn` returns the body for that moment. Any identity change seen in chats (`SessionCipher`'s
  `identityChanged`: a message from, or a send to, a new identity, or `keys.changed`) also forgets that person's
  moments delivery, so my next moment carries a fresh copy. My own moments posted from an earlier phone can't be
  recovered and stay hidden. Tests: `MomentKeyRequestTest`. Not pushed: a poster who is offline answers when their
  socket replays the frame.

**Phase 2 progress (2026-11-04): group wallets built in the app (steps 2b–2g), on branch `worktree-agent-abce5f97288748815`, not on a device yet.**
- Backend PR #28 (W14–W16, `walletId`, CH7–CH9) was being merged and deployed to the dev sandbox; the app keeps the flag
  regardless (see D-M8 and `app-architecture.md` → "Group wallets").
- Built: 2b wallet model (owners, `walletId`, W16, Room per wallet, one SDK with a session per wallet and per-wallet
  biometrics, every approval naming its wallet); 2c/2d New Group with the wallet, set-up, group info, target; 2e/2f
  contributions into the group wallet and payouts from it (request with CH4, approve per CH9, the signer pays with
  W4 `walletId` + `payoutRequestId`); 2g Paying from and the Wallet tab's switcher (now the corner popover, D-M9). 33 new tests; previews
  `-preview=gw-new-group|gw-new-group-wallet|gw-new-group-offline|gw-new-group-failed|gw-setup|gw-setup-bio|gw-setup-face-id|gw-setup-failed|gw-setup-offline|gw-setup-cancelled|gw-info-none|gw-info-finish|gw-info-offline|gw-info-loading|gw-info-error|gw-info-wallet|gw-info-bio-lost|gw-info-member|gw-info-locked|gw-target-needs-wallet|gw-leave-blocked|gw-target|gw-wallet|gw-wallet-member|gw-wallet-loading|gw-wallet-error|gw-contribute|gw-pay-payout|gw-pay-signing|gw-not-sent|gw-request-payout|gw-pay-personal|gw-pay-from|gw-pay-offline`.
- Departures (decided at the premium standard): ~~the review is the existing full-screen Pay screen~~ **superseded
  2026-11-04: from a chat there is no review page; the chat's money card carries GW9's rows** (the Pay screen stays
  for the Wallet tab, the group wallet screen and calls); GW1's group photo isn't offered (no backend for it at create); GW1's daily-cap
  sentence waits for 2F (the cap isn't enforced yet, so saying it would be untrue); iOS Circle PIN texts can't name a
  group wallet (static strings), so the app's own screen behind the PIN names it; Change PIN is a coming sheet (no
  W8 route); GW1's "By" date is the existing month choices, not a calendar.
- Design review (2026-11-04): both Blockers fixed (a group payment now waits in GW17 "Sending from …" and says Paid only
  once W5/`transfer.updated` confirms it, FAILED goes to GW19; the per-wallet fingerprint switch turns on and off), and
  the should-fix items: target edits keep the shown currency and the saved date, Remove Target confirms, Personal is
  named on Circle's PIN screen when there are other wallets, a member's Request Payout names the signer, GW19's Try
  Again and Ref caption, references on every error, GW16's caption above the amount, money rows straight under group
  info's header, bottom-anchored set-up buttons and a finishing tick, thinner hero glyphs. Still open (polish): the
  chat payment card's head reads the note ("Paid from …") rather than W5's `fromGroupId` (it now waits for
  confirmation: Sending, then the kind); skeleton loading rows; Wallet tab crossfade between wallets; SegmentedControl radio semantics; GW5's
  "3 of 5" network count; previews for GW9a2/GW18/GW16-in-tab.
  chat payment card's head reads the note ("Paid from …") rather than W5's `fromGroupId`, and doesn't wait for
  confirmation; skeleton loading rows; SegmentedControl radio semantics; GW5's "3 of 5" network count; previews for
  GW9a2/GW18. (The Wallet tab crossfade and the GW16-in-tab previews are done, see below.)
- Wallet tab redrawn (2026-11-04, D-M9, branch `worktree-agent-aac051142a41327a5`): the corner switcher and its popover
  (`WalletSwitcher.kt`), one wallet design for personal and group wallets (`WalletContent` with `WalletUiState.group`
  and `GroupWalletHooks`; `GroupWalletReader` shared by `WalletViewModel` and `GroupWalletViewModel`), a crossfade
  between wallets, the wallet skeleton for a group too, and See All / asset statements / all assets opening the wallet
  on screen (`walletId` on `AssetActivityRoute`, `NetworkActivityRoute`, `NetworkAssetsRoute`). Departures: Add and
  Payout are off on a locked wallet (nothing can leave it, so nothing is invited in), Receive there warns; Manage
  networks and the missing-networks card stay personal (W12 has no `walletId`); a space wallet says Payout, not Send,
  until spaces send directly (Pay turns any group `fromWalletId` into a payout request today); in the tab the caption
  leads with the wallet's full name (the corner shows a short one, "Mama Mboga"); hide balances is one
  `BalancePrivacy` single shared by the tab and the pushed screen (per run). Design review: the Blocker (popover shadow
  clipped by its window) and every should-fix item fixed (corner row eases in instead of jumping, skeleton built from
  the page's own measures, name in the caption, space wallet honest, hidden balances shared, offline reason under
  the balances, popover width grows with Dynamic Type and moves the balance under the name at large sizes).
  Tests: `WalletSwitchTest` (11).
  Previews `-preview=wallet-switcher|wallet-switcher-open|wallet-switcher-three|wallet-switcher-locked|wallet-unified-personal|wallet-unified-signer|wallet-unified-member|wallet-unified-offline|wallet-unified-locked|wallet-unified-loading|wallet-unified-setting-up|wallet-unified-space`
  and `gw-wallet*` now draw the unified screen. Not checked on a device or simulator yet (no install, by instruction).
- To finish: device check on the emulator with two accounts (owner and member) against the sandbox once PR #28 is
  live; the Circle SDK paths (`:circlewallet/src/circle`, the Swift `canImport` branch) only build with the GitHub
  Packages token and the pods, so naming the wallet on Circle’s screens is untested until then. 2F (fees, caps) is built: see below.

**Pasaka's corrections (2026-11-04), built on `main`, not yet on a device:**
- **No review page from a chat** (one-to-one and groups): the chat's money card is the review and Pay opens the
  Circle PIN; the card in the thread is Sending → Sent only once confirmed, Not sent with Try Again; failures before
  the chain stay on the card with the reference. Every chat entry (composer `#`, goal strip Add, profile Pay,
  contributions, an approved request's Pay) is in the chat. Details: `app-architecture.md` → "Money in the chat".
- **Group money live**: contributions and payouts update the balance and target on every member's open screens.
  Backend **PR #30** (`feat/group-money-live`, worktree `circaso-group-live`): `chama` / `money.updated` to every
  member, target or not; **not merged or deployed**. The app handles it already, and reloads the group at once when
  my own payment confirms. Details: `app-architecture.md` → "Group money live".
- To test on two devices (owner A and member B in a group with a wallet and a target, plus a group without a target):
  B types `#100 test` in the group: the card shows the group, target after, Paying from Personal, No fee; Pay → PIN →
  the card in the chat says Sending, then Contribution; A's goal strip, group info and group wallet screen move
  without leaving (needs PR #30 for the group without a target); B's own goal strip moves at once. Offline: Pay is
  dimmed with the offline line. Cancel the PIN: nothing in the chat. A direct chat: `#50 lunch @usdc`, Paying from
  switch to the group wallet → Request Payout; A's profile Pay puts `#` in the composer.

**Phase 2F progress (2026-11-04): fees and limits built, backend PR #31 open (not merged or deployed), app on branch
`worktree-agent-ab009271e84d17f92`, not on a device yet.** Plan and decisions: `spaces-plan.md` §11.10; contract:
`mobile-integration.md` → "Fees and limits" (copy refreshed from the PR branch, also at the project root).
- Backend: fee and limit rules in the database with seeds, W17 quote, W4 `quoteId` and `wallet.fee_changed`, the EVM
  `executeBatch` challenge (payee + fee wallet from server config), rolling 24 h limits at W4 under a lock, fee ledger.
- App: `PayViewModel` quotes W17 400 ms after the amount, coin, network, payee or Paying from changes (only the latest
  answer counts; offline nothing is asked and the fee says "Needs a connection"; a server without W17 answers 404 and
  shows "No fee"). `PayFee` is None / Quoting / Quoted (rate, minimum, `quoteId`, `expiresAt`) / Failed / Offline; Pay
  waits for a settled fee. W4 sends the `quoteId`; `wallet.fee_changed`, `wallet.limit_exceeded` and
  `wallet.insufficient_funds` keep the card (no "Not sent": nothing was attempted) with GW13c's banner, GW14b's note
  and "Pay After 14:30", or GW20's "the most you can send" with "… Instead" (`onUseMax`, never a cent over in KES); a
  fresh key after each. Shared UI in `FeeParts.kt` (fee row with spinner / Try Again, total "—" until known, the
  footnote "One Circaso fee, network cost included." with "minimum", rate and "held until"), used by the chat's money
  card and the Pay screen. A Request Payout shows "about KES 50 · 1%, set when paid" and GW14's "can send X more today"
  with "Request X Instead" (a request may exceed today's cap). GW14c: the thread's D5 card asks W17 (as a member) for
  each approved, unpaid payout and says "Approved · waits for the limit", when it fits and who pays, with a disabled
  "Pay After 14:30". New `CircasoIcon.Info` (GW20's glyph).
- Departures (premium standard): the GW13 footnote says "fee and rate held until 14:52" (a clock time) instead of a
  ticking countdown; GW14c's line names "the group wallet" (the card has no group name in scope).
- Design review (2026-11-04): both Blockers fixed (the card and the D5 card wake at `fitsAt` and when a hold runs out, asking W17 again and showing GW13c before Pay; the last answer stays on the card, dimmed, while a new one is asked, with the notes easing in and out and a Crossfade on the fee) and the should-fix items: a stale quote can't be signed for a new amount (`feeStale`, also guarded in `onPay`), a bare "Send" until the fee is known, "held until" hidden once passed, problems above the rows with Network and For dropped, "tomorrow 09:00", a 44 dp Try Again, GW13d only for retryable failures (others say why), no "0%" for an unknown rate, offers worded with exactly what they fill in, the Pay screen's fee row a `DetailRow` (new composable overload), the card row's value wrapping, Medium offer buttons, footnotes as the frames have them, the offline thread asking again later, and the GW14c joiner.
- Tests: `PayFeeTest` (11), `PayoutWaitsTest` (3). Previews `-preview=chat-fee|chat-fee-quoting|chat-fee-minimum|chat-fee-changed|chat-fee-error|chat-fee-offline|chat-fee-short|chat-contribute-fee|chat-payout-cap|chat-request-cap|chat-request-waits|gw-pay-fee|gw-pay-fee-quoting|gw-pay-fee-error|gw-pay-fee-short`.
- To finish: merge and deploy PR #31; create the Circle fee wallet per chain and set `WALLET_FEES_CHAIN_<CHAIN>_ADDRESS`
  (until then no fee is taken, limits and W17 still work); on the emulator pay `#1` (minimum), `#500` (0.5 %), into a
  group (1 %), to an address (2 %), a payout over a group's cap (GW14b/GW14c); sign one batch challenge and check
  its webhook confirms the transfer. Release note: an app without this build gets `wallet.fee_changed` on every paid
  payment once PR #31 is live.

**Phase 3, Spaces core, built in the app (2026-11-05, branch `worktree-agent-a8a33e7f818615802`), then removed the
same day when Spaces stopped (code kept at tag `archive/spaces-v1`).**
Contract `references/spaces-api.md` (to 3f2a9ea); backend PR #33 merged and deployed on the dev sandbox. Behind
`SpacesFlag` (on in debug, off in release, off for the run on `404 request.not_found` from any SP route; debug
override `-spaces=on|off|default`, Android `--es spaces off`). Architecture: `app-architecture.md` → "Spaces core".
- Built per frame group: SD1–SD3 (Spaces tab sections replace "Spaces are coming"; search with @handles and people;
  Suggested), SS1–SS6e and SI2b (the space page for every join type, visitor and member, About, Members, all states),
  SN1–SN8b (three steps, live @handle check, the space wallet's PIN via W18/W19 as its own Circle user, set-up ticks,
  failed/hidden, settings, details with Remove Photo, price change), SM1–SM6 (the join card on the page, Paying from
  fixed to Personal, the fee through W17 at the space rate with `quoteId`, PIN directly, membership only once
  confirmed, renewal banner, grace, ended, leave), SI1–SI4b (invite people, invite cards at the top of Chats with
  Accept/Decline and Undo before SP22, invites and requests), SH1–SH1f (space chat: not end-to-end encrypted line, role
  badges, counted reactions in the post's popover, admins-only, muted, removed tombstones, offline queue), SH2–SH2e
  (private chat with the owner through M4 `viaSpaceId`, "Private · via {space}", `#` pays the space, Follow/Join to
  Pay for non-members; owner inbox labels and the Spaces filter chip), SR1–SR4d (members with popovers, roles, report
  screen, reports queue), SW1–SW2b (space wallets in the unified Wallet tab: tile and name in the switcher, owner
  sends, admins view; paying from a space wallet in a chat sends directly).
- Previews: `sd-*`, `si-invite*`, `sh-owner-inbox`, `sh-inbox-labels` (discover); `space-*` page and join names;
  `space-new*`, `space-pin`, `space-setting-up`, `space-setup-*`, `space-live`, `space-settings*`, `space-details`,
  `space-price-change`, `space-wallet*` (create and wallet); `space-chat*`, `space-private*`, `space-members*`,
  `space-member-*`, `space-roles`, `space-report*`, `space-reports*`, `space-invite*`, `space-invites*` (chat and admin).
- Tests: `SpacesRepositoryTest` (16), `DiscoverViewModelsTest` (10), `SpacePageViewModelTest` (15),
  `SpaceCreateViewModelsTest` (13), `SpaceChatViewModelsTest` (14).
- Left: space posts are text only (no media upload/display yet, SP33/SP34 exist); payment cards in the private chat
  still read Sent/Received rather than "Paid to / Received into {space}" (needs a payload or head change); the
  personal transfer table has no `toSpaceId` column (named from what Pay set); SM1g's Receive has no route to the
  Wallet tab's Receive sheet from a pushed screen (`SpacesNav.receive` is a no-op); cover photo editing (not designed);
  pushes `kind = space.*` don't deep-link yet; no device run yet.
- Design review (2026-11-05): both Blockers fixed (join hold countdown and expiry; Joining always ends) plus Leave
  from About, card motion, dim behind popovers, honest Add Money, no empty flash on the Spaces tab. Still open
  (should-fix): the space page head uses per-item negative offsets (replace with one overlap via `Modifier.layout`);
  `MembershipBanner` should be the design-system `Banner` with an action slot; the money cards' raw shadow colour
  (shared with `ChatMoneyCard`) wants a `--shadow-float` token; SN1 doesn't show the picked photo; SN3's team picker
  isn't lazy; opening a space from a row should seed the head from the row instead of a full skeleton (and the
  skeleton's fixed widths overflow 360 dp); SW1 Recent says "Payment" for every incoming kind; nice-to-haves (Undo
  pill motion, Learn more → About tab, SN3 owner row name, SN6 deep link from the summary, Message offline when the
  private chat exists, token radii and paddings).
- To test on the sandbox: sign in two accounts on the emulator (debug build, flag on); A creates a public space and a
  members-only one (0.10 USDC monthly) and sets the space wallet PIN; B finds it by `@handle`, follows, posts and
  reacts (and offline: posts wait, then send), messages the owner privately and pays `#1`; B joins the members-only
  space (card → PIN → Joining → welcome, receipt in the private chat); A invites B to an invite-only space (card in
  Chats), mutes, removes and bans B, resolves a report; A sees the space wallet in the Wallet tab and pays a supplier
  from it.

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
| Blurry first splash logo on Android | **Fixed and verified** on an API 37 emulator (2026-11-03): the cold-start mark is sharp at full resolution. |
| Android notifications showed stock system icons | **Fixed and verified** (2026-11-03): a real FCM push to the signed-in emulator showed the Circaso mark (`icon=0x7f080116`, `ic_stat_circaso`, channel `messages`). |
| Asha returned 429 | **Fixed** by the paid plan (2026-11-03): all three models answer 200 when called directly. |
| iOS gets no pushes and doesn't ring in the background | Not built (see Next work 3 and 4). |
| Android emulator `Pixel_9_Pro` stuck "offline" in adb after cold boots | **Fixed** 2026-11-03 by Wipe Data (approved by Pasaka); the test account must sign in again. Use the SDK's adb, not Homebrew's. A spare AVD `Circaso_Check` also exists. |
| Real SMS send via Twilio not confirmed end to end | Backend says to test with a real number and report the `code`. |
| ID-13 shows a masked number but the server needs the full number | The returning person types it. |
| Welcome screen's terms line has no links | Needs the terms and privacy URLs. |
| Circle SDK on Android pinned at 1.0.1192 (1.1.2 exists) | The bridge targets 1.0.1192; upgrade later deliberately. |
| iOS must ship Facebook's SDK (Circle's binary links it) | Switched off in Info.plist (no app id, no auto-init, no tracking). |
| A confirmed payment showed no Received card: the receiver's phone couldn't open the E2E card after the sender signed in on a new phone (incident 2026-11-05) | **Fixed in the app** (2026-11-05, see "Recovery" below); the server half is backend PR #35 (**not merged or deployed**). |
| Group sender keys were tracked per person, not per identity, so a member's new phone never got my key | **Fixed** 2026-11-05: any identity change forgets that person's deliveries in every group (`ChatsDao.deleteDeliveriesTo`); a group retry receipt hands the key over again too. |
| Setting a group photo failed on Pasaka's phone (group `efee1e59-…`, made 2026-11-04 06:32 EAT): no `group_meta_key` row at all, so the name showed "Group" and rename/photo returned `Unexpected` before any request | **Fixed in the app** 2026-11-06, see "Lost group keys" below. No backend change. |

### Lost group keys (2026-11-06)

- **Cause (the only path the code has):** the creator's metadata key lived only in memory until `POST /v1/chats`
  answered. A create the server committed but whose answer never reached the phone (20 s client timeout, dropped
  connection, the view model's scope cancelled by leaving the screen, the app killed) left the group on the server
  sealed under a key no phone had; it then came in with the chat list. The sealer and the DAO were ruled out (the same
  sealer keeps the Signal identity, which survived; nothing deletes the row but sign-out). Confirm in the server logs:
  that phone's `POST /v1/chats` around 2026-11-04 03:32 UTC, its duration and whether the client hung up.
- **Fix:** the key is written under `pending:<idempotencyKey>` (metaVersion 0, same table, no migration) before the
  request leaves; a retry, even after a restart, reuses it; success makes it the group's; a group that turns up
  unreadable tries the waiting keys (`GroupKeys.adoptPendingKey`). Pending rows go after 30 days.
- **Recovery for phones already without a key:** a group this phone can't name makes it ask the other members
  (admins first, at most 20, once per 10 minutes per group, not within 10 minutes of joining) with a 1:1
  `SENDER_KEY` message holding `ChatPayload` type `metaKeyRequest`, Moments-style. A member who holds the key and
  still sees the asker in the group answers 1:1 with a `metaKey` payload (once a minute per person). A received key
  for the version the phone holds must open it, or it is dropped. Older apps drop the request quietly.
- **No answer:** after a minute, an admin's Rename or photo change gives the group a new key: sealed with the typed
  name (the photo flow opens the name sheet as "Name the group" when it can't read the name), PATCHed as the next
  meta version (`avatarMediaId` cleared unless a new photo goes with it), then sent to every member. Members and
  admins inside the minute see `messaging.meta_key_missing` / `meta_key_asked`. Removing someone no longer seals
  the placeholder "Group" as the name when the phone can't read it. Tests: `GroupMetaKeyRecoveryTest`.

### Recovery: money cards and retry receipts (2026-11-05)

- **Money is never hidden by decryption.** `ChatPaymentCards` (messaging `Recovery.kt`) keeps one payment card per
  transfer per chat. The wallet tells it about confirmed chat payments from the server's own record:
  `payment.received` with `transferId`/`chatId` (then W5), and W6 rows with `transferId` + `chatId` in `CONFIRMED`
  (the last 30 days, so a new phone isn't flooded). Then a Received card, or my own Sent card on a new phone, shows
  in a **direct** chat even if the E2E card is unreadable or never arrives. Groups have their own money cards. That
  card has no note (the server never has one) and no `messageId`. When the E2E card arrives, it fills in that card
  (its note, id and time), and my own send takes over a wallet card instead of adding a second. Incoming money is
  kept under its transfer id in the wallet (one row, not two). Until backend PR #35 is deployed, the frame and W6 IN
  rows carry no `transferId`: the receiver gets no fallback card yet, but retry receipts (below) still recover the
  E2E card. Tests: `MessageRecoveryTest` (part 1), `ChatPaymentSeenTest`.
- **Retry receipts (Signal-style), no backend change.** A `MESSAGE` this phone can't open (an Undecryptable row)
  makes it ask the sender, **once per message**, at most 5 a minute, for messages received in the last 30 days.
  Rows from before this fix (like the incident's) are asked about at the next inbox sync. The request is a plain
  `WireFormat.Type.RetryRequest` (type 5, JSON `{v, messageId}`, unencrypted, as Signal's decryption-error messages
  are). It goes as a `CONTROL` message in a direct chat, or a 1:1 `SENDER_KEY` message to the sender alone in a group
  (the only kinds the server sends to one member). Neither is pushed. Older apps drop type 5 quietly. The sender
  answers if the message is its own and under 30 days old, the asker is still in the chat, it hasn't answered that
  person for that message, and they're under 10 answers a minute. It starts a fresh session from the asker's current
  bundle (`SessionCipher.restart`, at most once a minute per person). Unlike `refresh`, it always restarts, because a
  session can break without an identity change. In a group, it hands its sender key over again. It then sends the
  message 1:1 again (`CONTROL` / `SENDER_KEY`, not pushed) with `ChatPayload.resendOf` = the original id. The
  receiver saves it under the original id and time and deletes the "can't show" row; a resend can't replace a
  message that opened or that is someone else's. Bookkeeping: `message_retry` (DB v19). Tests: `MessageRecoveryTest`
  (part 2).
- **Launch safety:** `appModules(platform)` is the one module list `initKoin` starts with, and `KoinGraphTest`
  builds it, failing on a null or late-declared module (checked by planting one).
- Follow-ups: the incident's own payment row predates the backend link (no backfill), so its Received card comes
  from the retry receipt once both phones update. A "can't show" row whose sender no longer has the message stays
  as it is.

## 7. Open design questions

- Is `/asha` meant for any text field or only the chat composer?
- "Forgot your PIN" (ID-24b): Circle recovery flow vs recovery key plus number check.
- Should a large payment require Face ID / biometrics above an amount?
- Rooted or jailbroken phones: warn, limit payments, or allow silently?
- Asha's free allowance and paid plan (Asha Plus): not decided on the server.
- Remaining "CoinX" strings in the design copy (assistant, support, notifications, email sender).
- ~~Treasurer model for chamas~~: superseded by group wallets (spaces-plan §7, decided 2026-11-03).

## 8. Testing notes

- Android: Pixel 9 Pro emulator (`emulator -avd Pixel_9_Pro -no-window -no-snapshot -no-boot-anim -no-audio`, run in
  the background). Pasaka's Redmi is a **personal phone**: only use it when asked; check Circaso is in front before any
  tap or screenshot.
- iOS: `iosApp/iosApp.xcworkspace` (CocoaPods), simulator "Circaso iPhone 17 Pro"; iPads "Circaso iPad Pro 11/13";
  simulator builds are arm64 only.
- Android Circle SDK needs `pwsdk.maven.username` / `pwsdk.maven.password` in `local.properties` (never commit).
- Tests: `./gradlew :shared:allTests` (common, Android host and iOS).
