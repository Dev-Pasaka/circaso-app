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
`CircasoScaffold` (top bar, banner slot, content, floating bottom bar, `overlay` slot for sheets, `toast` slot; content gets the bottom padding the tab bar needs), `LargeTitleBar` (top-level screens, actions on the right), `CircasoNavBar` (pushed screens: back, title, subtitle, trailing; centred on iOS, start-aligned on Android), `CircasoTabBar` + `TabItem` (floating glass pill, unread dot), `Banner` (inline glass card, tones Neutral/Warning/Critical), `ToastHost` + `ToastState` (glass pill, 3 s, one at a time), `CircasoSheet` (scrim, grabber, 34 dp corners, closes on scrim tap, back or drag down) and `ConfirmSheet` (the design's confirm pattern: title, line, main button, Cancel), `Modifier.glass()`. The offline banner itself is `core/sync/ConnectionBanner` (reads `ConnectionState`; wording from designs G1/G3b).

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

## Design gaps found while building the chrome
Fixed in code where I could; the rest need a design or product decision.
- **Fixed:** the design's offline banner is an inline glass card under the title, not a bar over the status bar (rebuilt to match). The banner has no "Try again" button in the design, so I removed mine.
- **No backdrop blur.** The design's glass uses `backdrop-filter: blur(24px)`. Compose Multiplatform has no cross-platform backdrop blur, so floating glass (tab bar, toast) is made nearly opaque (94%) to stay legible. A blur library (for example Haze) would restore it; needs your approval.
- **The design is iOS-flavoured only.** There is no Android-specific nav bar, sheet or tab bar. I chose start-aligned titles and a tween sheet on Android; confirm or send an Android design.
- **No scroll behaviour specified:** the large title does not collapse; no spec for it.
- **Sheet:** only one height is designed; no detents or full-screen expansion.
- **No retry affordance** when "offline" is caused by an unreachable server; it clears on the next successful request or a network change.

## Known gaps
Checked on a Redmi (24117RN76G, dark) and the iPhone 17 Pro simulator (light). On the Redmi (dark), driven by `adb input` (needs Developer options > USB debugging (Security settings)): confirm sheet, back key closing the sheet without leaving the app, toast, tab switching and the offline banner with waiting messages all behave as designed. Not yet exercised by touch: sheet drag-to-dismiss, the plain `Sheet`, iOS interactions (no tap injection for the simulator), and Android light mode. Android light, iOS dark and high contrast still need a visual check against `assets/Circaso-Screens.html`.
