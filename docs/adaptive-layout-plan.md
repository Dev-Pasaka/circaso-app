# Adaptive layout plan: phones, landscape, tablets and iPads

Started 2026-10-31 at Pasaka's request: every screen adapts to its window, in either orientation, following platform
practice (Apple HIG size classes, Material window size classes), with motion that behaves physically.

## Rules every screen follows

**Window classes** (`designsystem/adaptive/WindowLayout.kt`, provided once at the app root, from the window's size
in dp, so split screen, Stage Manager, foldables and rotation all just work):

| Width | Class | Typical |
| --- | --- | --- |
| < 600 dp | Compact | phone portrait |
| 600–839 dp | Medium | phone landscape, small tablet, iPad split view |
| ≥ 840 dp | Expanded | tablet / iPad, either orientation |

Height < 480 dp is **short**: a phone on its side.

1. **Readable width.** Lines of text and forms never stretch across a tablet. Content in `CircasoScaffold` is
   centred at most 680 dp wide (Apple's readable content guide, Material's 600–840 body width). Bars and
   backgrounds still reach the edges.
2. **Two panes from Medium up** (iPad Messages, Mail, Settings). With the window ≥ 700 dp wide, Home sits in a
   380 dp list pane on the left and whatever is opened from it (a chat, a payment, a settings page) in the detail
   pane on the right. Opening the next item replaces the detail without moving the list. Full-screen flows (calls,
   voice with Asha, the pay sheet, maps, sign-in) still take the whole window.
3. **Short height (phone landscape).** Heroes shrink, two-column layouts where a column would scroll for nothing,
   the keyboard never covers the field being typed in (already true through `imePadding`).
4. **Motion follows physics.** Pushes slide in from the trailing edge on a critically damped spring while the
   screen underneath drifts a third of the way back and dims (UIKit's push), pops reverse it, interruptible
   mid-flight; pane changes in a split slide inside their pane only. Springs, not fixed durations, so a
   rotation or a resize mid-animation settles naturally. Reduce Motion swaps every movement for a cross-fade.
5. **Rotation keeps state.** View models and saved state already survive configuration changes; scroll positions
   are kept with the rotation (no screen resets to the top).

## Per screen

| Screen | Compact (phone portrait) | Short (phone landscape) | Medium / Expanded (tablet, iPad) |
| --- | --- | --- | --- |
| Splash, Force update | as is | as is | centred, readable width |
| Welcome | as is | logo left, buttons right | centred column, readable width |
| Phone, Code, Registering, Lost access | as is | scrolls, field and button stay visible over the keyboard | centred card, readable width |
| **Home (tabs)** | floating tab bar | floating tab bar, compact | list pane (380 dp) with the floating tab bar inside it; detail pane beside |
| Chats tab | list | list | list pane; the open chat beside it, the selected row highlighted |
| Thread (chat) | as is | composer stays visible, bubbles keep their max width | detail pane; bubbles keep their max width |
| Chat info, Chama, New chat, New group | as is | readable width | detail pane, readable width |
| Location map | full screen | full screen | full screen (full-screen flow) |
| Asha | as is | readable width | detail pane, readable width |
| Voice with Asha | full screen | orb left, captions right | full screen, orb centred, captions readable width |
| Calls | full screen | tiles side by side | full screen, tiles in a grid |
| Wallet tab | cards, actions, assets, recent | cards left, assets and recent right | list pane; payments and assets open in the detail pane |
| Pay, Send to wallet | full sheet | full width, scrolls with the system keyboard (there is no on-screen keypad) | full-screen flow, readable width |
| Payment detail, Activity, Asset statement, Network assets, Networks | as is | readable width | detail pane |
| You tab | hero + settings | hero beside the settings | list pane; each setting opens in the detail pane |
| Edit profile, Contacts, Blocked, Security, Security log, Notifications, Currency, Asha's voice, Needs attention, Help | as is | readable width | detail pane, readable width |
| Gallery, Previews (debug) | as is | as is | as is |

## Status (2026-10-31)

Done: window classes; split view from 840 dp with a 360 dp list (Home as list, detail push/pop/swap, no Back at the
detail's root, the open chat highlighted); spring push/pop (opaque screens, no slide on rotation); readable width on
tablets and full width on a phone on its side; edge to edge under the camera cut-out; tabs as a left rail on short or
wide screens; rotation without recreating the screen; Welcome side by side; sign-in, voice scroll when they don't
fit; You and Wallet in two columns on their side; voice calls side by side on their side; group call grid adapts;
sheets as centred cards; map controls clear the cut-out; You's glow stays in its column on a phone on its side.

Also done: content is always framed, never stretched (the user, 2026-10-31: everything intentional): one column
at 680 dp, two-column landscape layouts at 900 dp, while backgrounds and glows are drawn edge to edge by the outermost
scaffold (`HostedBackdrop`); the back swipe follows the finger inside the detail pane; the screen underneath dims as
one is pushed over it. Checked on iPhone 17 Pro (portrait and on its side), iPad Pro 11" and 13".

Left: checking the signed-in flows on a real tablet and on the Redmi on its side.

## Second pass (2026-11-08)

Pasaka asked again for every screen to adapt to phones and tablets in both orientations, premium and never stretched.
Audited on the Redmi on its side, iPad Pro 13" and 11" (portrait, and landscape through the debug frame below), and
fixed:

- **Sheets** are a centred 540 dp card with every corner rounded on a tablet (regular width and height), rising from
  the bottom of the screen as an iPadOS form sheet does; phones keep the bottom sheet.
- **Floating bottom bars** (Make a Payment…) are centred over the content, not the screen, so the top bar or tabs down
  the side never sit under them; on a phone on its side the page colour fades in behind the bar (scroll edge).
- **Android on its side** hides the status bar (a swipe brings it back), as an iPhone does in landscape.
- **Group calls** lay everyone out at the largest tile that fits without scrolling (FaceTime); on a phone on its side
  the live goal moves beside the tiles. Call controls keep a hand's width on tablets. The video self-view turns with the
  screen and is larger on a tablet.
- **Message bubbles** grow to 62% of the pane, at most 440 dp, on wide screens (292 dp on a phone, as designed).
- **Moments**: the viewer, composer and chat camera are full-screen flows (never squeezed into the detail pane); a
  photo shows whole over a blur of itself on wide screens instead of being cropped. The gallery adds columns (112 dp
  minimum) instead of three huge tiles.
- **Voice with Asha** on a phone on its side: the mark and controls left, the transcript right.
- **Tab labels** grow at most 1.2x with the text size, as UIKit's don't grow at all, so they never clip.
- `readableWidth()` now fills up to its max (it used to wrap its content).

- **Over the window, not the pane.** Sheets and photo viewers draw in a window-level layer
  (`designsystem/chrome/WindowOverlay.kt`): one opened from the 360 dp list pane (You's photo, a tab's sheet) covers
  the whole window, centred, never the narrow pane (Pasaka, 2026-11-08). Debug `-window-overlay=off` shows the old way.
- **Each tab keeps its own screens** (`core/navigation/TabStacks.kt`, iOS tab-bar rule): on two panes, changing tab
  sets aside what the old tab opened in the detail pane and brings back what the new tab had; Back never shows another
  tab's sub-screens. On one pane nothing is kept. Signing out (Home leaving the stack) forgets them all; a notification
  opens its tab fresh.
- Tablet sign-in is one centred group, buttons at `WindowLayout.FormWidth` (440 dp); the tablet wallet is one centred
  column a card's width; small call tiles step the avatar down and fade in and out as people join and leave.

- **Deferred: set-aside screens restart.** A tab's screens set aside on two panes are taken off the back stack, and
  navigation3 clears their view model and saved state when they leave it (its decorators' `onPop` is internal, so it
  can't be wrapped). They come back reloaded (from Room, so instantly), but a half-typed form loses its text and scroll
  positions reset. Fix next: drafts of forms that can be set aside (Edit Profile, amounts, support) live in a
  repository or Room, as chat drafts already do; or a custom ViewModelStore/SavedState decorator that skips kept keys.

**Checking landscape from a script.** Simulators can't be rotated from the command line (iPadOS refuses
`requestGeometryUpdate` in windowed mode), so debug builds take `-frame=1376x1032` (iOS) or `--es frame 1280x800`
(Android): the whole app is laid out in a window of that many dp, scaled to fit. Useful sizes: iPad Pro 13" 1376x1032,
11" 1210x834, Android tablet 1280x800, iPhone on its side 874x402. Safe-area insets stay the real device's.

## Order of work

1. Foundation: window classes, readable width in the scaffold, physical push/pop transitions. **(done first)**
2. Two-pane scene for Home on Medium/Expanded, with the detail pane's own transitions.
3. Short-height layouts: Welcome, Voice, Calls, Pay, Wallet, You.
4. Tablet polish: Chats selection highlight, call tiles grid, wallet two columns at Expanded.
5. Check every screen on: phone portrait, phone landscape, 8" tablet, 11" iPad (both orientations), iPad split
   view; design review pass.
