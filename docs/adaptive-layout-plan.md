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

Left: the back swipe following the finger inside the detail pane (it completes as a pop today); a darkening scrim
under pushed screens; checking the signed-in flows on a real tablet (previews were checked on iPad Pro 13" and 11"
simulators: `-preview=split`, `-preview=split-wallet`, `-preview=home`).

## Order of work

1. Foundation: window classes, readable width in the scaffold, physical push/pop transitions. **(done first)**
2. Two-pane scene for Home on Medium/Expanded, with the detail pane's own transitions.
3. Short-height layouts: Welcome, Voice, Calls, Pay, Wallet, You.
4. Tablet polish: Chats selection highlight, call tiles grid, wallet two columns at Expanded.
5. Check every screen on: phone portrait, phone landscape, 8" tablet, 11" iPad (both orientations), iPad split
   view; design review pass.
