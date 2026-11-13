# Circaso design system (Compose Multiplatform)

Code: `shared/src/commonMain/kotlin/com/circaso/designsystem/`. Built on Compose **Foundation**, not Material3 widgets. Debug gallery: `com/circaso/gallery/ThemeGallery.kt` (what `App()` shows today).

## Rules
- Use roles from `CircasoTheme.colors` / `.typography` and the scale objects (`CircasoSpacing`, `CircasoShapes`, `CircasoSizes`, `CircasoMotion`). No raw hex, no magic dp where a token exists.
- Wrap everything in `CircasoTheme { }`. It picks light/dark/light-hc/dark-hc from the system and provides `LocalPlatformStyle`.
- Native feel = behaviour and metrics. Look-only differences branch on `CircasoTheme.platform` (`PlatformStyle.Ios | Android`); anything needing a native API goes through `expect/actual` in `designsystem/platform/`.
- Tap handling is always `Modifier.pressable { }` (iOS: spring scale + dim, no ripple; Android: ripple). Never use bare `clickable` for controls.
- Every interactive component sets a semantics description (TalkBack/VoiceOver). PIN dots announce progress, never digits.

## Components (`designsystem/components/`)
`CircasoButton` (Primary/Secondary/Plain/Destructive; Large 50/Medium 44/Small 34; `busy`, `block`), `CircasoIconButton`, `CircasoTextField` (label above, `error` line, `leading` slot), `ListGroup` + `ListRow` + `DetailRow` + `RowDivider`, `Avatar` (initials; photos later), `AmountText`, `PinDots` + `PinKeypad`, `Spinner`, `CircasoIcon`/`CircasoIconImage` (drawn per platform, e.g. Back = chevron on iOS, arrow on Android).

## Chrome (`designsystem/chrome/`)
`CircasoScaffold` (top bar, `strip` slot for bars that stay until dealt with (wallet hold, restore names, live location), content with the floating `banner` slot over its top, floating bottom bar, `overlay` slot for sheets, `toast` slot; content gets the bottom padding the tab bar needs), `LargeTitleBar` (top-level screens, actions on the right), `CircasoNavBar` (pushed screens: back, title, subtitle, trailing; centred on iOS, start-aligned on Android), `CircasoTabBar` + `TabItem` (floating glass pill, unread dot), `Banner` (inline glass card for in-page notes, tones Neutral/Warning/Critical), `FloatingNotice` + `FloatingNoticeHost` (the pop-up: floats, never pushes the screen), `ToastHost` + `ToastState` (a `FloatingNotice`; `ToastKind` Done = check, Info, Problem = red alert for 5.5 s; one at a time; the scaffold places it where the content starts), `CircasoSheet` (scrim, grabber, 34 dp corners, closes on scrim tap, back or drag down) and `ConfirmSheet` (the design's confirm pattern: title, line, main button, Cancel), `Modifier.glass()`. The offline banner itself is `core/sync/ConnectionBanner` (reads `ConnectionState`; wording from designs G1/G3b).

`CircasoTextField(secret = true)` uses the password keyboard and never triggers `/asha`. `LocalAshaLauncher` is null until Asha exists, so `/asha` currently does nothing and stays in the field.

## What diverges per platform
| Area | iOS | Android | Status |
| --- | --- | --- | --- |
| Press feedback | scale + dim | ripple | done (`pressable`) |
| Back icon | chevron | arrow | done |
| High contrast | Increase Contrast | contrast level (API 34+) | done |
| Connectivity | NWPathMonitor | ConnectivityManager (VALIDATED) | done (`ConnectivityMonitor`) |
| Nav bar | centred title | start-aligned title | done (collapsing large title not built) |
| Sheet | spring slide-in | tween slide-in | done (single detent) |
| Back navigation | edge swipe | predictive back | todo (navigation) |
| Haptics | UIKit generators | HapticFeedback | todo |
| Fonts | SF (system) | Roboto (system) | system default both; rounded amount font open |
| Biometrics, share, contacts | LocalAuthentication etc. | BiometricPrompt etc. | todo |

## Planned
- PIN components are always secret (no `/asha`, no logging): wire this when the PIN screens are built.
- Collapsing large title, sheet detents, alert-style dialogs (the design only uses sheets).

## In-app browser (no frame in the canvas; decided 2026-11-06)
`core/web/InAppBrowserScreen.kt`, over everything from `App` (`InAppBrowserHost`); platform views are `PlatformWebView`
(Android `WebView`, iOS `WKWebView`), no library.
- Rises full screen as a modal (the sheet spring on iOS, `BaseMs` tween on Android, a fade with Reduce Motion), like
  Safari's and Chrome's in-app views: a page on top of Circaso, not a new place in the app.
- Top bar: Close, Back on iOS only (fades in once there is page history; it holds its place so the centred title doesn't
  jump; Android leaves it out, as Chrome's custom tabs do, since system back goes back in the page), the page title over
  the domain with a lock (or a warning and "Not secure" for http), always two lines so the bar never changes height, More. The domain is the real host (user info
  stripped): in a money app the address is what you trust. iOS centres the title, Android starts it.
- A 2 dp progress line in `action` under the bar, never starting empty, only moving forward (each load restarts from a
  sliver), drawn rather than laid out, fading once loaded.
- The web view is built once the slide-in has settled (the first one loads the browser engine on the main thread), and
  it takes every touch and holds screen readers: nothing reaches the app underneath. The page's own background is white
  (pages that set none assume it); our opening state covers it until the page draws.
- More opens a sheet in the menu pattern (as Asha's): the title and the full address, then Forward (when there is one),
  Open in Browser, Copy Link (toast "Link copied"), Share.
- System back goes back in the page's history first, then closes. WebKit's own edge swipe is off on iOS so one swipe
  never goes back twice.
- States on the page background in the app's voice: "Opening basescan.org" with a spinner until the page first draws
  (no white flash); offline "You're offline / The page opens by itself when you're back online." with Try Again (it also
  retries by itself); failed "This page didn't load / {domain} isn't answering right now..." with Try Again and Open in
  Browser.
- Security: JavaScript on (explorers need it), no file or content access, no JavaScript bridge, third-party cookies off
  (Android `CookieManager`; WebKit blocks them by default), no new windows (they open in place), no downloads, mixed
  content blocked, bad certificates never proceed. Another app's link inside a page opens only on a tap
  (`decideNavigation`); `intent:` links never launch, only their web fallback.

## Design gaps found while building the chrome
Fixed in code where I could; the rest need a design or product decision.
- **Changed 2026-11-13 (Pasaka: pop-ups float, never push the screen down, and look as considered as iOS's own):** the offline banner and toasts are one `FloatingNotice`, a glass capsule floating at the top of the content, under the top bar and the strip, centred in the readable column. Departures from `cx-banner`/G4: a 30 dp tinted icon badge instead of a bare icon; a bold title with a quieter detail line instead of one sentence; pill corners for one line, `lg` once it wraps (concentric with the badge); it takes its own taps, swipes up to dismiss, and is read first by screen readers. The offline notice settles into a small "Offline" capsule after 4 s (a tap opens it again), so it never sits over the screen for minutes. Toasts say what they are (`ToastKind`): only a confirmed success wears a check, problems are red and stay 5.5 s, and long errors wrap so their support reference is never cut. Motion: `CircasoSpring.notice()` in a short 12 dp drop from 94 %, out in `QuickMs`; a fade with reduced motion. The banner has no "Try again" button in the design.
- **No backdrop blur.** The design's glass uses `backdrop-filter: blur(24px)`. Compose Multiplatform has no cross-platform backdrop blur, so floating glass (tab bar, toast) is made nearly opaque (94%) to stay legible. A blur library (for example Haze) would restore it; needs your approval.
- **The design is iOS-flavoured only.** There is no Android-specific nav bar, sheet or tab bar. I chose start-aligned titles and a tween sheet on Android; confirm or send an Android design.
- **No scroll behaviour specified:** the large title does not collapse; no spec for it.
- **Sheet:** only one height is designed; no detents or full-screen expansion.
- **No retry affordance** when "offline" is caused by an unreachable server; it clears on the next successful request or a network change.

## Known gaps
Checked on a Redmi (24117RN76G, dark) and the iPhone 17 Pro simulator (light). On the Redmi (dark), driven by `adb input` (needs Developer options > USB debugging (Security settings)): confirm sheet, back key closing the sheet without leaving the app, toast, tab switching and the offline banner with waiting messages all behave as designed. Not yet exercised by touch: sheet drag-to-dismiss, the plain `Sheet`, iOS interactions (no tap injection for the simulator), and Android light mode. Android light, iOS dark and high contrast still need a visual check against `assets/Circaso-Screens.html`.
