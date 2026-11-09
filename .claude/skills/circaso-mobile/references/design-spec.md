# Circaso · Product Design Spec

**Version:** 25 October 2026 · **Status:** Phase 1 designs · **Owner:** Pasaka

Circaso (formerly CoinX) is a chat-first social money app: you send money the way you send a message, to people you know, and every payment carries the feeling behind it. This document gathers every design from the Circaso design canvas into one place: the brand mark, the design system, and every screen and state, grouped by flow, with its copy, actions and components.

> **Naming.** The new **Circaso mark and wordmark** are applied to the logo spots. Body copy in many screens still says "CoinX" (for example "CoinX Assistant", "CoinX Support"); the full rename of copy is a separate, pending pass. Component names keep the `CoinX.` namespace until the design system is renamed.

> **Spaces is archived (2026-11-05).** This spec has no Spaces section; the Spaces frames (SD, SS, SN, SM, SI, SH, SR,
> SW and the Spaces tab S1) live in `assets/Circaso-Spaces.html` and are **archived**, not to be built: see
> `spaces-plan.md` → "STOPPED 2026-11-05". The Moments frames there (S1's ring states, M, V, P) and the group wallet
> frames (GW) stay current; Moments now show in Chats under a Moments chip, not on a Spaces tab.

**How to read a screen entry**

- The heading is the screen's ID and name from the canvas; the line under it gives the source file, size and default theme. Every phone screen also has a Dark appearance.
- Items are listed top to bottom in the order they appear on screen. **Title** is the main heading; `Component` names refer to the design system (section 2); **Buttons** show their style (primary = the one main action, secondary, plain, destructive).
- Sample people, amounts and dates are illustrative.

## Contents

1. [Brand and logo](#1-brand-and-logo)
2. [Design system](#2-design-system)
3. [Screen index](#3-screen-index)
4. [A · First launch](#4-a--first-launch)
5. [ID · Identity and sign-in](#5-id--identity-and-sign-in)
6. [C · Chats and money](#6-c--chats-and-money)
7. [D · Groups and chamas](#7-d--groups-and-chamas)
8. [E · Wallet](#8-e--wallet)
9. [F · Assistant and settings](#9-f--assistant-and-settings)
10. [S · Support](#10-s--support)
11. [G · System states](#11-g--system-states)
12. [H · Calls and live](#12-h--calls-and-live)
13. [W · Website](#13-w--website)
14. [Website, emails and admin (built)](#14-website-emails-and-admin-built)
15. [Decisions and open questions](#15-decisions-and-open-questions)

## 1. Brand and logo

**Name:** Circaso. *Circa* (a circle) + *casa* (a home): the home of your circle. Domain: circaso.com.

**The mark — "Circle and centre."** A silver coin with a C cut into it and a silver centre inside it: you, at the middle of your circle. It also reads as the first letter of the name.

| Part | Specification (SVG, `viewBox 0 0 200 200`) |
| --- | --- |
| Coin | Circle `cx 100, cy 100, r 92` |
| Ring cut | Annulus between `r 20` and `r 48` around the centre (leaves the centre dot, r 20) |
| Opening | Wedge `M143.6,80 L240,40 L240,160 L143.6,120 Z` cut from the outer ring on the right |
| Silver fill | Linear gradient, top-left to bottom-right: `#fbfbfd` 0 · `#d4d5d9` .32 · `#8f9298` .55 · `#dcdde1` .78 · `#f4f4f6` 1, plus a soft white radial highlight at 32%/26% |
| Rims | Inner rim `r 83`, white at 45%, 1.4; outer edge `r 91`, `#5d6066` at 35%, 1.2 |
| Cut edges | `#3b3d42` at 35%, 2.4, drawn only on real edges (never across the opening) |
| One colour | Same cut-outs, solid `currentColor` (ink on light, white on dark) |

**Usage**
- Silver on black (`#000`) or on a dark tile (`linear-gradient(160deg, #1c1c1e, #000)`) for the app icon and splash; silver or one-colour ink on white.
- App icon: dark tile, mark at about 70% of the tile.
- Lockup: mark + "Circaso" in the system face, bold, tight tracking (−1.4 to −2px at display sizes), mark height about 1.4× the cap height.
- Small sizes (32 px and below): use the one-colour mark.
- The earlier engraved coin (globe and ring of people) is retired; it doesn't fit the C cut.

**Files in the website project:** `public/favicon.svg`, `public/apple-touch-icon.png` (180), `public/icon-512.png`, `public/og.png` (1200×630 share image), `public/email/coin.png` (email header, 192 px).

**History:** L1 records the CoinX exploration (the three-bite coin that read as half an X, its engraved variant, and rejected bite options). L2 shows the three Circaso options; option 1 was chosen on 18 Oct 2026.

## 2. Design system

The design system is unchanged by the rename: monochrome, native, with money marked by a titanium material rather than colour. Source: the CoinX design system artifact (`window.CoinX` components, `tokens.json`).

CoinX is money sent as a message. The interface is entirely monochrome, quiet and native. Money arriving is marked by material, not colour: a titanium card with a soft sheen. Build every screen so it could sit beside Apple's own apps, then let the money moment be the one thing people remember.

### Principles

- **No colour, one material.** The UI is ink on white, or white on black. The only special surface is the received money card: `money-card` with its `money-card-sheen`, graphite in Light and brushed silver in Dark. Primary buttons use `action`, which is ink. Status colours (`critical`, `warning`) are the only hues, and only for problems.
- **Native first.** Navigation, sheets, pickers, toggles, alerts and permission prompts are the platform's own components. CoinX restyles nothing a person already knows how to use.
- **Two layers.** Content (messages, cards, lists) sits flat on `bg` or `bg-grouped` with no shadow. The functional layer (tab bar, navigation bar, composer, sheets) floats above it in glass. Nothing else floats.
- **One moment.** The received money card gets the only animation that isn't a system transition. Everything else moves the way the platform moves.
- **Blockchain stays invisible.** Never show the words wallet address, gas, network, chain or hash outside transaction detail's Advanced row and the external-send screen.

### Voice and words

- Write like a person texting a friend about money: short, warm, plain. "Mum sent you KES 2,000", not "Incoming transfer received".
- Lead with the local currency, coin second and smaller: "KES 500" over "3.88 USDT".
- Buttons say what happens, in title case on iOS: "Send KES 500", "Add Money", "Join Group". The result keeps the word: Send → "Sent".
- Errors say what happened, whether money moved, and what to do: "Couldn't send. Your money hasn't moved. Try again." Never apologise, never show an error code.
- No emoji in interface text. People can use them in messages.
- Use "you" for the user. Never "we" in a status message; "we" is fine for promises ("We never show your number to strangers").

### Colour

- Plain screens: `bg`. Grouped lists and wallet cards: `bg-grouped` with cells on `surface`. Inside a sheet, lift cells with `surface-raised`.
- Text: `ink` for everything that matters, `ink-secondary` for supporting text and placeholders. `ink-tertiary` is for disabled labels and decorative glyphs only.
- Secondary buttons, text fields, incoming bubbles, chips and PIN keys use `fill`; pressed states use `fill-strong`. Only `ink` and `ink-secondary` go on `fill`.
- Money cards: received is titanium (`money-card` blended with `money-card-sheen`, `on-money` text, + amount); sent is a quiet `fill` card with `ink` text. The sheen is the only gradient in the system. `money` and `money-surface` are aliases of `ink` and `fill`, for + amounts, goal bars and the money chip.
- `critical` for errors and destructive labels, always with an icon and words. `warning` for pending states that need the user. Neither is ever a background.
- Money is never signalled by colour: amounts in always carry + or "Received"; errors always carry an icon and words.
- Increased contrast is two added themes, `light-hc` and `dark-hc`, where every text pair holds 7:1 and `separator` becomes a real 3:1 border. Follow the system setting; never offer an in-app appearance switch.

### Type

- The interface uses the platform's own text styles at Large (default) Dynamic Type size, from `large-title` down to `caption-2`. Never set text below 11pt.
- Amounts use the `Amount` group in the rounded face, always with tabular figures (`font-variant-numeric: tabular-nums`) so digits don't jump while typing or counting up. The currency code is set one step down in `ink-secondary`: `amount-hero` digits beside a `title-2` "KES".
- Weight and size carry hierarchy; colour does not. A row is `headline` over `subhead` in `ink-secondary`.
- Every layout must survive the largest accessibility text size: rows grow, amounts wrap below names, nothing truncates an amount.
- Fonts: iOS uses the system face (SF Pro and SF Pro Rounded), which cannot be shipped to other platforms. Android needs a licensed substitute; Inter is the closest match and the current proposal. The stacks in tokens fall back to it.

### Space, shape and layers

- Side margin is `space-4`. Cards and sheets pad with `space-5`. Sections are `space-6` apart.
- Corners: `radius-pill` for anything you tap that isn't a row (buttons, chips, composer, tab bar); `radius-lg` for cards and bubbles; `radius-md` for fields and inset lists; `radius-sheet` for sheet tops.
- Controls: `control-lg` (50) for the main button at the bottom of a screen or sheet, `control-md` (44) for everything else. Nothing tappable has a hit area under 44 by 44.
- Glass: `glass` tint, 24px backdrop blur, a 1px `glass-edge`, and `shadow-float`. Used on the tab bar, the navigation bar once content scrolls under it, the composer bar and sheets. Never on cards, bubbles or rows. Under Reduce Transparency, use the `-hc` glass values, which are near-opaque.
- One primary button per view. A second action is `secondary` or `plain`.

### Motion and haptics

- System transitions for navigation and sheets, with springs at about `duration-base`.
- The money moment: when a received card first scrolls into view, the amount counts up from zero and one soft light sweep crosses the card, over `duration-moment`, with a success haptic. Once per card, never on sent cards, never replayed on scroll. Under Reduce Motion the card simply appears with its final value.
- Typing `#500` turns into a chip with a light haptic and slides up the preview strip over `duration-quick`.
- Haptics: success on a completed send or a money arrival, warning on a failed send, light impact on chip conversion. Nothing else vibrates.

### Iconography

- iOS: SF Symbols only, at the weight of the adjacent text, filled in the tab bar, outline elsewhere. Common choices: `bubble.left.and.bubble.right.fill` (Chats), `creditcard.fill` (Wallet), `person.crop.circle.fill` (You), `sparkle` (assistant), `arrow.up` (send), `plus` (add money), `qrcode` (receive), `exclamationmark.circle.fill` (errors).
- SF Symbols can't ship on Android. An Android icon set matched to SF Symbols' regular weight is still to be chosen.
- No illustrations, mascots or emoji in the interface. 
- The logo is the Circaso mark (section 1). The welcome screen shows the mark with the wordmark.

### Accessibility

- Every text pair in this system holds 4.5:1 in Light and Dark and 7:1 in the increased-contrast themes on the grounds its token note names.
- Focus ring: 2px `focus`, 2px offset, on every interactive element when using a keyboard or switch control.
- Every icon-only button carries a spoken label. Money cards speak as a sentence: "Received 500 shillings from Mum, for lunch."
- Amounts never rely on colour: + and − signs, and the words Sent and Received.

### Components

Button, TextField, PinPad, Composer, SegmentedControl, ListRow, Avatar, Badge, AmountDisplay, MoneyCard, GoalProgress, ChatBubble, Banner, TabBar and Sheet. Each has a live preview and guidelines in its own section. All are built for the `CoinX` namespace in `components/bundle.js` and styled from `components/bundle.css`, using only the tokens above. To use them on a page: load React 18, then `tokens.css`, `components/bundle.css` and `components/bundle.js`, in that order; set `data-theme` (`light`, `dark`, `light-hc`, `dark-hc`) on any container to theme everything inside it.

### Colour tokens

| Token | Light | Dark | Light HC | Dark HC | Use |
| --- | --- | --- | --- | --- | --- |
| `bg` | `#ffffff` | `#000000` | `#ffffff` | `#000000` | Page background for plain screens: chat threads, sheets' outer ground, onboarding. |
| `bg-grouped` | `#f2f2f7` | `#000000` | `#f2f2f7` | `#000000` | Background behind grouped lists and cards: Wallet, You, settings. |
| `surface` | `#ffffff` | `#1c1c1e` | `#ffffff` | `#1c1c1e` | Cards and grouped list cells sitting on bg-grouped; sent money cards; the send sheet body. |
| `surface-raised` | `#ffffff` | `#2c2c2e` | `#ffffff` | `#2c2c2e` | Cells inside a sheet, and anything that must lift off surface in Dark. |
| `fill` | `#e9e9eb` | `#2c2c2e` | `#e4e4e8` | `#3a3a3c` | Secondary buttons, text field backgrounds, incoming chat bubbles, chips, PIN keys. Only ink and ink-secondary text sit on fill. |
| `fill-strong` | `#dcdce0` | `#3a3a3c` | `#d1d1d6` | `#48484a` | Pressed state of fill; the empty track of a progress bar. |
| `fill-selected` | `#ffffff` | `#636366` | `#ffffff` | `#48484a` | The selected segment of a segmented control, raised above fill. ink reads at 5.6:1 on it in Dark, 9:1 in Dark increased contrast. |
| `separator` | `#e3e3e8` | `#38383a` | `#8a8a8e` | `#8e8e93` | Hairlines between list rows. Decorative in Light and Dark; in the increased-contrast themes it is strong enough (3:1) to separate controls. |
| `ink` | `#0b0b0c` | `#f5f5f7` | `#000000` | `#ffffff` | Primary text and icons on every ground: bg, bg-grouped, surface, surface-raised, fill, money-surface. 16:1 or better in every theme. |
| `ink-secondary` | `#636366` | `#98989f` | `#3a3a3c` | `#c7c7cc` | Supporting text: timestamps, subtitles, coin equivalents, captions, placeholders. 4.9:1 or better on bg, bg-grouped, surface and fill; 7:1+ in the increased-contrast themes. |
| `ink-tertiary` | `#7c7c80` | `#8e8e93` | `#48484a` | `#aeaeb2` | Disabled labels and decorative glyphs only. Never body text: it holds 3:1, not 4.5:1, on fill in Light. |
| `action` | `{ink}` | `{ink}` | `{ink}` | `{ink}` | Fill of the one primary button per view (Send, Continue, Create Group). Monochrome by design: CoinX keeps colour for money. |
| `on-action` | `#ffffff` | `#0b0b0c` | `#ffffff` | `#000000` | Label and spinner on an action fill. 18:1 in both appearances. |
| `money` | `{ink}` | `{ink}` | `{ink}` | `{ink}` | Money arriving, where it isn't a card: the + amount in activity rows, goal progress, the money chip's text. Monochrome by design (an alias of ink); the + sign and the word Received carry the meaning, never colour. |
| `money-surface` | `{fill}` | `{fill}` | `{fill}` | `{fill}` | Background of the money chip in the composer and the goal badge. An alias of fill. |
| `money-card` | `#2b2c30` | `#c9cacf` | `#1c1d20` | `#e0e1e5` | Base of the received money card: graphite in Light, brushed silver in Dark, always the opposite of the background. The card's material, not a colour, is what marks money arriving. |
| `money-card-sheen` | `#4a4c52` | `#f2f3f6` | `#3a3c41` | `#ffffff` | The reflective edge of the received money card's sheen, blended into money-card from the corners. |
| `on-money` | `#ffffff` | `#0b0b0c` | `#ffffff` | `#000000` | Text and icons on the titanium money card. 8.6:1 or better across the whole sheen in every theme. |
| `critical` | `#c8102e` | `#ff5a50` | `#8c0a1e` | `#ff928b` | Errors, failed sends, destructive buttons' labels, Delete Account. Text on bg, bg-grouped and surface. Always with an icon and words; it shares money's lightness, so hue alone must never tell them apart. |
| `warning` | `#9a5800` | `#ffb340` | `#6e3f00` | `#ffc266` | Pending states that need attention: KYC in review, approval waiting, rate about to expire. Text on bg, bg-grouped and surface. |
| `focus` | `{ink}` | `{ink}` | `{ink}` | `{ink}` | Keyboard and switch-control focus ring: 2px solid, 2px offset. Holds 16:1 on every surface. |
| `glass` | `rgba(255,255,255,0.72)` | `rgba(30,30,32,0.72)` | `rgba(255,255,255,0.96)` | `rgba(28,28,30,0.96)` | Tint of the floating functional layer only: tab bar, navigation bar at scroll, composer bar. Paired with a 24px backdrop blur. The increased-contrast values are near-opaque, which is also the Reduce Transparency fallback. Never on cards or rows. |
| `glass-edge` | `rgba(0,0,0,0.08)` | `rgba(255,255,255,0.12)` | `rgba(0,0,0,0.45)` | `rgba(255,255,255,0.5)` | 1px edge on glass surfaces so they hold their shape over white or black content. |
| `scrim` | `rgba(0,0,0,0.32)` | `rgba(0,0,0,0.56)` | `rgba(0,0,0,0.5)` | `rgba(0,0,0,0.7)` | Dims the screen behind a sheet. |

### Type

Families: sans `-apple-system, BlinkMacSystemFont, SF Pro Text, SF Pro Display, Inter, Segoe UI, Roboto, sans-serif`; rounded `ui-rounded, SF Pro Rounded, -apple-system, BlinkMacSystemFont, Inter, sans-serif`.

| Style | Group | Size / line | Weight | Tracking | Use |
| --- | --- | --- | --- | --- | --- |
| `large-title` | Text | 34px / 41px | 700 | 0.37px | Root screen titles: Chats, Wallet, You. |
| `title-1` | Text | 28px / 34px | 700 | 0.36px | Onboarding headlines. |
| `title-2` | Text | 22px / 28px | 700 | -0.26px | Sheet titles, group names on D4. |
| `title-3` | Text | 20px / 25px | 600 | -0.45px | Empty-state and section headlines. |
| `headline` | Text | 17px / 22px | 600 | -0.43px | List row titles, button labels, names in chat headers. |
| `body` | Text | 17px / 22px | 400 | -0.43px | Messages, paragraphs, field text. The default. |
| `callout` | Text | 16px / 21px | 400 | -0.31px | Notes on money cards, banner text. |
| `subhead` | Text | 15px / 20px | 400 | -0.23px | List row previews and subtitles. |
| `footnote` | Text | 13px / 18px | 400 | -0.08px | Rate lines, legal lines, coin equivalents, section headers in grouped lists. |
| `caption-1` | Text | 12px / 16px | 400 | 0px | Timestamps, tab bar labels. |
| `caption-2` | Text | 11px / 13px | 400 | 0.06px | Badge counts only. The platform minimum; never smaller. |
| `amount-hero` | Amount | 56px / 60px | 600 | -0.8px | The amount being typed in the send sheet. Tabular figures. |
| `amount-large` | Amount | 40px / 44px | 600 | -0.5px | Wallet total, group wallet balance, transaction detail. |
| `amount-medium` | Amount | 28px / 32px | 600 | -0.3px | The amount on a money card. |
| `amount-small` | Amount | 17px / 22px | 600 | -0.2px | Amounts in list rows and activity. |

### Spacing

| Token | Value | Use |
| --- | --- | --- |
| `space-1` | `4px` | Gap between an icon and its label; between stacked caption lines. |
| `space-2` | `8px` | Gap between chat bubbles from one sender; chip padding. |
| `space-3` | `12px` | List row vertical padding; gap between bubbles from different senders. |
| `space-4` | `16px` | Screen side margin; card padding; gap between avatar and text. |
| `space-5` | `20px` | Money card and sheet padding. |
| `space-6` | `24px` | Gap between sections in a screen. |
| `space-8` | `32px` | Gap above a screen's primary button; onboarding block spacing. |
| `space-10` | `40px` | Top padding of onboarding headlines. |
| `space-14` | `56px` | Space around the hero amount in the send sheet. |

### Radius

| Token | Value | Use |
| --- | --- | --- |
| `radius-xs` | `6px` | Small inline tags. |
| `radius-sm` | `10px` | PIN keys, code boxes, thumbnails. |
| `radius-md` | `14px` | Text fields, inset grouped lists, segmented controls. |
| `radius-lg` | `22px` | Money cards, wallet cards, chat bubbles. |
| `radius-sheet` | `34px` | Top corners of sheets, matching the device's display corners. |
| `radius-pill` | `999px` | Buttons, the composer, money chips, badges, the tab bar. |

### Sizes

| Token | Value | Use |
| --- | --- | --- |
| `control-lg` | `50px` | Height of primary and secondary buttons at the bottom of a screen or sheet. |
| `control-md` | `44px` | Height of inline buttons, text fields, list rows' minimum, and the minimum hit area of anything tappable. |
| `control-sm` | `34px` | Height of compact buttons in toolbars and on cards (Approve, Decline). Hit area still padded to 44px. |
| `avatar-sm` | `28px` | Avatars in group member chips and money card headers. |
| `avatar-md` | `44px` | Avatars in list rows. |
| `avatar-lg` | `64px` | Recipient in the send sheet. |
| `avatar-xl` | `96px` | Profile and contact headers. |

### Shadow

| Token | Value | Use |
| --- | --- | --- |
| `shadow-float` | `light: 0 8px 30px rgba(0,0,0,0.10) / dark: 0 8px 30px rgba(0,0,0,0.55) / light-hc: 0 0 0 1px rgba(0,0,0,0.45) / dark-hc: 0 0 0 1px rgba(255,255,255,0.5)` | Floating functional layer only: tab bar, composer bar, sheets. Content never casts a shadow. |

### Motion durations

| Token | Value | Use |
| --- | --- | --- |
| `duration-quick` | `150ms` | Press states, chip conversion. |
| `duration-base` | `280ms` | Sheets, pushes, preview strip sliding up. Use a spring, not an ease curve. |
| `duration-moment` | `900ms` | The money moment on a received card: count-up plus light sweep. Once per card. Zero under Reduce Motion. |

### Components

Loaded as `window.CoinX` from `components/bundle.js` with `components/bundle.css` and `tokens.css` (React 18). Props:

```ts
import type { ReactNode } from 'react';

type IconName = 'arrowUp' | 'arrowDown' | 'plus' | 'close' | 'check' | 'wifiOff' | 'alert' | 'clock' | 'del' | 'utensils' | 'qr' | 'chevron' | 'chat' | 'card' | 'person' | 'sparkle';

export interface ButtonProps {
  /** primary: the one main action per view. secondary, plain, destructive otherwise. */
  variant?: 'primary' | 'secondary' | 'plain' | 'destructive';
  /** lg 50pt (bottom of screen or sheet), md 44pt, sm 34pt with a 44pt hit area. */
  size?: 'lg' | 'md' | 'sm';
  block?: boolean;
  disabled?: boolean;
  loading?: boolean;
  loadingText?: ReactNode;
  icon?: IconName;
  label?: string;
  onClick?: () => void;
  children: ReactNode;
}

export interface TextFieldProps {
  label?: string;
  value?: string;
  placeholder?: string;
  prefix?: string;
  type?: string;
  inputMode?: 'text' | 'tel' | 'decimal' | 'numeric' | 'email';
  hint?: string;
  /** Replaces hint, shows the critical ring and an alert icon. Phrase it as how to fix the problem. */
  error?: string;
  id?: string;
}

export interface PinPadProps { length?: number; filled?: number; }

export interface ComposerProps {
  placeholder?: string;
  text?: string;
  /** The recognised money token, e.g. "#500". */
  chip?: string;
  /** The preview strip above the bar. warn = over balance. */
  preview?: { title: string; sub?: string; warn?: boolean; actionLabel?: string };
}

export interface SegmentedControlProps { options: string[]; value: string; label?: string; }

export interface ListRowProps {
  title: ReactNode;
  subtitle?: ReactNode;
  leading?: ReactNode;
  meta?: ReactNode;
  badge?: ReactNode;
  /** Signed amount text, e.g. "+KES 150" or "−KES 640". */
  amount?: string;
  amountIn?: boolean;
  chevron?: boolean;
  tappable?: boolean;
  onClick?: () => void;
}

export interface AvatarProps { name: string; src?: string; size?: 'sm' | 'md' | 'lg' | 'xl'; assistant?: boolean; }

export interface BadgeProps { tone?: 'count' | 'money' | 'warning' | 'critical'; count?: number; children?: ReactNode; }

export interface AmountDisplayProps {
  amount: string;
  currency?: string;
  sub?: ReactNode;
  size?: 'hero' | 'large' | 'medium';
  direction?: 'in' | 'none';
  align?: 'center' | 'left';
}

export interface MoneyCardProps {
  direction: 'in' | 'out';
  amount: string;
  currency?: string;
  note?: string;
  symbol?: IconName;
  from?: string;
  foot?: string;
  label?: string;
  /** Group chats: the sender's name; shows their picture beside the card. */
  avatar?: string;
  avatarSrc?: string;
  /** Play the money moment. First appearance of a received card only. */
  moment?: boolean;
}

export interface GoalProgressProps { raised: number; goal: number; name?: string; currency?: string; meta?: string; }

export interface ChatBubbleProps { direction: 'in' | 'out'; time?: string; /** Group chats: the sender's name; shows their picture beside an incoming message. */ avatar?: string; avatarSrc?: string; children: ReactNode; }

export interface BannerProps { tone?: 'offline' | 'critical' | 'warning'; action?: string; children: ReactNode; }

export interface TabBarProps { active?: string; items?: { id: string; label: string; icon: IconName; dot?: boolean }[]; }

export interface SheetProps { title?: string; children: ReactNode; }

export interface IconProps { name: IconName; size?: number; }
```

## 3. Screen index

| Page | Screen | File | Size |
| --- | --- | --- | --- |
| L | L1 · CoinX mark, bitten coin and engraved variant | `L1-Logo.dc.html` | 1440×1720 |
| L | L2 · Circaso mark, three options | `L2-Circaso-options.dc.html` | 1440×1180 |
| A | A1 · Launch screen | `Main.dc.html` | 390×844 |
| A | A2 · Welcome | `A2-Welcome.dc.html` | 390×844 |
| A | A3 · Sign in *(superseded)* | `A3-Sign-in.dc.html` | 390×844 |
| A | A4 · Your phone number *(superseded)* | `A4-Phone.dc.html` | 390×844 |
| A | A5 · Verify code *(superseded)* | `A5-Verify.dc.html` | 390×844 |
| A | A6 · Your profile | `A6-Profile.dc.html` | 390×844 |
| A | A7 · Create your PIN *(superseded)* | `A7-PIN.dc.html` | 390×844 |
| A | A8 · Face ID *(superseded)* | `A8-Face-ID.dc.html` | 390×844 |
| A | A9 · Find your people | `A9-Contacts.dc.html` | 390×844 |
| A | A10 · Meet CoinX | `A10-Meet-CoinX.dc.html` | 390×844 |
| ID | ID-01 · Launch | `ID01-Launch.dc.html` | 390×844 |
| ID | ID-01 · Launch, offline | `ID01b-Launch-offline.dc.html` | 390×844 |
| ID | ID-02 · Welcome, iPhone | `ID02-Welcome.dc.html` | 390×844 |
| ID | ID-02 · Welcome, Android, loading | `ID02b-Welcome-Android.dc.html` | 390×844 |
| ID | ID-02 · Welcome, error | `ID02c-Welcome-error.dc.html` | 390×844 |
| ID | ID-02 · Welcome, signed out | `ID02d-Welcome-signed-out.dc.html` | 390×844 |
| ID | ID-03 · Signing you in | `ID03-Signing-in.dc.html` | 390×844 |
| ID | ID-04 · Your number, Android one tap | `ID04a-Phone-one-tap.dc.html` | 390×844 |
| ID | ID-04 · Your number, Android number picker | `ID04b-Phone-number-hint.dc.html` | 390×844 |
| ID | ID-04 · Your number, iPhone | `ID04c-Phone-iPhone.dc.html` | 390×844 |
| ID | ID-04 · Finish setting up | `ID04d-Phone-finish.dc.html` | 390×844 |
| ID | ID-05 · Carrier check didn't work | `ID05-Carrier-fallback.dc.html` | 390×844 |
| ID | ID-06 · Enter the code, verifying | `ID06-Enter-code.dc.html` | 390×844 |
| ID | ID-06 · Wrong code | `ID06b-Wrong-code.dc.html` | 390×844 |
| ID | ID-06 · Too many tries | `ID06c-Code-locked.dc.html` | 390×844 |
| ID | ID-07 · Number already registered | `ID07-Number-taken.dc.html` | 390×844 |
| ID | ID-08 · Your recovery key | `ID08-Recovery-intro.dc.html` | 390×844 |
| ID | ID-09 · Recovery key | `ID09-Key-reveal.dc.html` | 390×844 |
| ID | ID-09 · Save the key as a file | `ID09b-Key-save-file.dc.html` | 390×844 |
| ID | ID-10 · Confirm the key | `ID10-Confirm-key.dc.html` | 390×844 |
| ID | ID-10 · Confirm, wrong word | `ID10b-Confirm-wrong.dc.html` | 390×844 |
| ID | ID-11 · Create your payment PIN | `ID11-Protect.dc.html` | 390×844 |
| ID | ID-11 · Confirm your payment PIN | `ID11d-Confirm-PIN.dc.html` | 390×844 |
| ID | ID-11 · PINs didn't match | `ID11b-Protect-declined.dc.html` | 390×844 |
| ID | ID-11 · Face ID, optional | `ID11c-Payment-PIN.dc.html` | 390×844 |
| ID | ID-11 · Fingerprint, optional, Android | `ID11e-Fingerprint.dc.html` | 390×844 |
| ID | ID-12 · You're in | `ID12-Handoff.dc.html` | 390×844 |
| ID | ID-13 · New phone | `ID13-New-phone.dc.html` | 390×844 |
| ID | B4 · Wrong number on a new phone | `ID13b-Wrong-number.dc.html` | 390×844 |
| ID | ID-14 · Restoring your chats | `ID14-Restoring.dc.html` | 390×844 |
| ID | ID-15 · Signed out on this phone | `ID15-Signed-out-here.dc.html` | 390×844 |
| ID | ID-16 · What do you still have? | `ID16-What-you-have.dc.html` | 390×844 |
| ID | ID-17 · Enter your recovery key | `ID17-Enter-key.dc.html` | 390×844 |
| ID | ID-17 · Recovery key imported from a file | `ID17c-Key-imported.dc.html` | 390×844 |
| ID | ID-17 · Import failed | `ID17d-Import-failed.dc.html` | 390×844 |
| ID | ID-17 · Wrong recovery key | `ID17b-Key-wrong.dc.html` | 390×844 |
| ID | ID-18 · Choose your new sign-in | `ID18-New-sign-in.dc.html` | 390×844 |
| ID | ID-19 · Confirm recovery | `ID19-Confirm-recovery.dc.html` | 390×844 |
| ID | ID-20 · Your new recovery key | `ID20-New-key.dc.html` | 390×844 |
| ID | ID-22 · Lost both | `ID22-Lost-both.dc.html` | 390×844 |
| ID | ID-23 · Hold banner on Home | `ID23-Home-hold.dc.html` | 390×844 |
| ID | ID-23 · Send during the hold | `ID23b-Send-paused.dc.html` | 390×844 |
| ID | ID-24 · Account and security, Face ID off | `ID24-Account-security.dc.html` | 390×960 |
| ID | ID-24 · Change your payment PIN | `ID24b-Set-PIN.dc.html` | 390×844 |
| ID | ID-24 · Account and security, Face ID on | `ID24c-PIN-on.dc.html` | 390×960 |
| ID | ID-25 · Security activity | `ID25-Security-activity.dc.html` | 390×844 |
| ID | ID-26 · Sign out | `ID26-Sign-out.dc.html` | 390×844 |
| ID | ID-27 · Remove this phone | `ID27-Remove-phone.dc.html` | 390×844 |
| ID | ID-28 · Delete account | `ID28-Delete-account.dc.html` | 390×844 |
| ID | ID-28 · Delete blocked, money in wallet | `ID28b-Delete-has-money.dc.html` | 390×844 |
| ID | ID-21 · Suspended | `ID21-Suspended.dc.html` | 390×844 |
| ID | ID-29 · Blocked | `ID29-Blocked.dc.html` | 390×844 |
| ID | ID-30 · Force update | `ID30-Force-update.dc.html` | 390×844 |
| ID | Error · CoinX is having trouble | `ID31-Backend-down.dc.html` | 390×844 |
| C | C1 · Chats | `C1-Chats.dc.html` | 390×844 |
| C | C2 · Chat thread | `C2-Chat-thread.dc.html` | 390×844 |
| C | C3 · #amount shortcut | `C3-Money-shortcut.dc.html` | 390×844 |
| C | C4 · Send sheet | `C4-Send-sheet.dc.html` | 390×844 |
| C | C5 · Authorise | `C5-Authorise.dc.html` | 390×844 |
| C | C6 · Money moment | `C6-Money-moment.dc.html` | 390×844 |
| C | C7 · Transaction detail | `C7-Transaction.dc.html` | 390×844 |
| C | C8 · New chat | `C8-New-chat.dc.html` | 390×844 |
| D | D1 · New group | `D1-New-group.dc.html` | 390×844 |
| D | D2 · Group thread | `D2-Group-thread.dc.html` | 390×844 |
| D | D3 · Contribute | `D3-Contribute.dc.html` | 390×844 |
| D | D4 · Group wallet | `D4-Group-wallet.dc.html` | 390×844 |
| D | D5 · Pay from the group | `D5-Pay-from-group.dc.html` | 390×844 |
| D | D6 · Join a group | `D6-Join-group.dc.html` | 390×844 |
| D | D7 · Business group | `D7-Business-group.dc.html` | 390×844 |
| E | E1 · Wallet | `E1-Wallet.dc.html` | 390×960 |
| E | E2 · Add money | `E2-Add-money.dc.html` | 390×844 |
| E | E3 · Verify identity | `E3-Verify-identity.dc.html` | 390×844 |
| E | E4 · Receive | `E4-Receive.dc.html` | 390×844 |
| E | E5 · Send to a wallet | `E5-External-send.dc.html` | 390×844 |
| E | E6 · Cash out (coming) | `E6-Cash-out.dc.html` | 390×844 |
| E | E7 · Activity | `E7-Activity.dc.html` | 390×844 |
| F | F1 · Ask CoinX | `F1-Ask-CoinX.dc.html` | 390×844 |
| F | F2 · You | `F2-You.dc.html` | 390×1400 |
| F | F3 · Security and privacy | `F3-Security.dc.html` | 390×844 |
| F | F4 · Notifications | `F4-Notifications.dc.html` | 390×844 |
| S | SP-01 · Help | `SP01-Help.dc.html` | 390×960 |
| S | SP-02 · Support chat | `SP02-Support-chat.dc.html` | 390×844 |
| S | SP-03 · Calling support, in the queue | `SP03-Calling-support.dc.html` | 390×844 |
| S | SP-04 · On a call with support | `SP04-On-call.dc.html` | 390×844 |
| S | SP-05 · Conversation ended | `SP05-Chat-ended.dc.html` | 390×844 |
| S | SP-06 · Help without signing in | `SP06-Help-signed-out.dc.html` | 390×844 |
| G | G1 · Offline | `G1-Offline.dc.html` | 390×844 |
| G | G2 · Send failed | `G2-Send-failed.dc.html` | 390×844 |
| H | H1 · Incoming call | `H1-Incoming-call.dc.html` | 390×844 |
| H | H2 · Voice call, money mid-call | `H2-Voice-call.dc.html` | 390×844 |
| H | H3 · Video call | `H3-Video-call.dc.html` | 390×844 |
| H | H4 · Chama group call, live goal | `H4-Group-call.dc.html` | 390×844 |
| H | H5 · Contribute during the call | `H5-Contribute-on-call.dc.html` | 390×844 |
| H | H6 · Business live selling (after pilot) | `H6-Business-live.dc.html` | 390×844 |
| W | W1 · Waitlist site, desktop | `W1-Landing.dc.html` | 1440×8000 |
| W | W2 · Waitlist site, phone | `W2-Landing-phone.dc.html` | 390×8000 |

**108 artboards** across 11 pages.

## 4. A · First launch

The original first-launch sequence. **A3 to A8 are superseded by the Identity flow (page ID)**: sign-in, phone, code, PIN and Face ID now live there with the agreed rules. A1 (launch), A2 (welcome), A9 (contacts) and A10 (assistant intro) still stand.

### A1 · Launch screen

`Main.dc.html` · 390×844 · default light

- Circaso

### A2 · Welcome

`A2-Welcome.dc.html` · 390×844 · default light

- Circaso mark
- Circaso
- `ChatBubble` (direction: in): "Did you get lunch yet?"
- `MoneyCard` (direction: in, amount: 500, note: for lunch, from: Mum, foot: 3.88 USDT · 12:41)
- `ChatBubble` (direction: out, time: 12:42): "Asante, Mum!"
- **Title:** Money, with meaning.
- Send money to the people you love, as easily as a message.
- `Button` (variant: primary, size: lg): "Get Started"
- `Button` (variant: plain): "I already have an account"

### A3 · Sign in — superseded

`A3-Sign-in.dc.html` · 390×844 · default light

> Replaced by the Identity flow (page ID). Kept for reference.

- Icon button: Back
- **Title:** Let's get you in.
- One tap with the account you already use. No passwords to remember.
- Button: **Sign in with Apple**
- Button (secondary): **Continue with Google**
- By continuing you agree to our
- Link: Terms
- and
- Link: Privacy Policy
- .

### A4 · Your phone number — superseded

`A4-Phone.dc.html` · 390×844 · default light

> Replaced by the Identity flow (page ID). Kept for reference.

- Icon button: Back
- **Title:** What's your number?
- Friends find you on CoinX by your number. We never show it to strangers. · Country
- Button: **KE +254**
- `TextField` (label: Phone number, value: 712 345 678)
- `Button` (variant: primary, size: lg): "Send Code"

### A5 · Verify code — superseded

`A5-Verify.dc.html` · 390×844 · default light

> Replaced by the Identity flow (page ID). Kept for reference.

- Icon button: Back
- **Title:** Enter the code
- Sent to +254 712 345 678 ·
- Link: Edit → A4-Phone.dc.html
- Six-digit code boxes: 4 1 7 9 _ _
- Resend code in 0:24 · The code fills in by itself when the SMS arrives.

### A6 · Your profile

`A6-Profile.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** How should people see you?
- `Avatar` (name: Pasaka Mutuku, size: xl)
- Icon button: Add a photo
- `TextField` (label: Name, value: Pasaka Mutuku)
- `TextField` (label: Handle (optional), prefix: @, value: pasaka, hint: @pasaka is available. People can send to you by handle.)
- `Button` (variant: primary, size: lg): "Continue"

### A7 · Create your PIN — superseded

`A7-PIN.dc.html` · 390×844 · default light

> Replaced by the Identity flow (page ID). Kept for reference.

- Icon button: Back
- **Title:** Create a 6-digit PIN
- You'll use it to unlock CoinX and confirm money you send.
- `PinPad`

### A8 · Face ID — superseded

`A8-Face-ID.dc.html` · 390×844 · default light

> Replaced by the Identity flow (page ID). Kept for reference.

- **Title:** Use Face ID for CoinX?
- Unlock and confirm payments with a glance. Your PIN still works.
- `Button` (variant: primary, size: lg): "Turn On Face ID"
- `Button` (variant: plain): "Not Now"

### A9 · Find your people

`A9-Contacts.dc.html` · 390×844 · default light

- `Avatar` (name: Mum Wanjiku, size: lg)
- `Avatar` (name: Otieno Odhiambo, size: lg)
- `Avatar` (name: Achieng, size: lg)
- `Avatar` (name: Juma Kariuki, size: lg)
- **Title:** See who's already on CoinX
- We check your contacts' numbers against CoinX so you can send money in a tap. · Numbers are matched securely and never stored as your contact list.
- `Button` (variant: primary, size: lg): "Allow Access"
- `Button` (variant: plain): "Skip"

### A10 · Meet CoinX

`A10-Meet-CoinX.dc.html` · 390×844 · default light

- Chats · Search
- `ListRow` (title: CoinX Assistant, subtitle: Ask me anything about your money)
- **Sheet / overlay: Meet CoinX**
  - `Avatar` (name: CoinX Assistant, size: md)
  - CoinX Assistant
  - Icon button: Close
  - **Heading:** Habari, Pasaka. I'm your CoinX assistant.
  - Two tricks worth knowing. · 1 of 2 · Send money as a message
  - `MoneyCard` (direction: out, amount: 500, note: for lunch, from: Mum, foot: To Mum · 3.88 USDT)
  - `Composer` (chip: #500, text: for lunch)
  - `Button` (variant: primary, size: lg): "Try It"
  - `Button` (variant: plain, size: lg): "Skip"
  - Icon button: Ask by voice

## 5. ID · Identity and sign-in

Every screen and state for sign-up, sign-in on a new phone, recovery, account security and account status. Built from the *Identity & Sign-in Flow* doc (18 Oct 2026). Screen IDs (ID-xx) match that doc.

**Rules these screens follow**
- Sign-in is Google or Apple only. No passwords anywhere.
- One phone number per account; a number already in use is blocked, never merged.
- One active phone per account; signing in on a new phone signs the old one out.
- An account is usable only once its phone is verified.
- The **payment PIN is mandatory**. Face ID / fingerprint is optional and the PIN takes over when it fails.
- Recovery needs the 12-word key **plus** one other factor (phone or the original Google/Apple account). After recovery, sending is paused for 48 hours.
- The recovery key can be written down, **saved as a file, and imported from a file**.

### Flow A · First-time sign-up, Welcome to an active account

#### ID-01 · Launch

`ID01-Launch.dc.html` · 390×844 · default light

- Circaso mark

#### ID-01 · Launch, offline

`ID01b-Launch-offline.dc.html` · 390×844 · default light

- Circaso mark
- No connection · CoinX needs the internet to check it's you.
- Button (secondary): **Try Again**

#### ID-02 · Welcome, iPhone

`ID02-Welcome.dc.html` · 390×844 · default light

- Circaso mark
- **Title:** Money, with meaning.
- Send money to the people you love, as easily as a message.
- Button (primary): **Continue with Apple**
- Button (secondary): **Continue with Google**
- Link: Lost access to your account? → ID16-What-you-have.dc.html
- By continuing you agree to our
- Link: Terms
- and
- Link: Privacy Policy
- .

#### ID-02 · Welcome, Android, loading

`ID02b-Welcome-Android.dc.html` · 390×844 · default light

- Circaso mark
- **Title:** Money, with meaning.
- Send money to the people you love, as easily as a message.
- Button (secondary): **Continue with Google (loading)**
- Button (primary): **Continue with Apple (disabled)**
- Link: Lost access to your account? → ID16-What-you-have.dc.html
- By continuing you agree to our
- Link: Terms
- and
- Link: Privacy Policy
- .

#### ID-02 · Welcome, error

`ID02c-Welcome-error.dc.html` · 390×844 · default light

- Circaso mark
- **Title:** Money, with meaning.
- Send money to the people you love, as easily as a message. · Couldn't reach Google. Try again.
- Button (secondary): **Continue with Google**
- Button (primary): **Continue with Apple**
- Link: Lost access to your account? → ID16-What-you-have.dc.html
- By continuing you agree to our
- Link: Terms
- and
- Link: Privacy Policy
- .

#### ID-02 · Welcome, signed out

`ID02d-Welcome-signed-out.dc.html` · 390×844 · default light

- Circaso mark
- **Title:** Money, with meaning.
- Send money to the people you love, as easily as a message. · You were signed out. Sign in again to carry on.
- Button (primary): **Continue with Apple**
- Button (secondary): **Continue with Google**
- Link: Lost access to your account? → ID16-What-you-have.dc.html
- By continuing you agree to our
- Link: Terms
- and
- Link: Privacy Policy
- .

#### ID-03 · Signing you in

`ID03-Signing-in.dc.html` · 390×844 · default light

- Circaso mark
- Signing you in…

#### ID-04 · Your number, Android one tap

`ID04a-Phone-one-tap.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Add your number
- Friends find and pay you by your number. We'll verify it once. · Your phone can confirm the number on its SIM. No code to type.
- Button (primary): **Verify with One Tap**
- Button (plain): **Enter a Different Number**

#### ID-04 · Your number, Android number picker

`ID04b-Phone-number-hint.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Add your number
- Friends find and pay you by your number. We'll verify it once. · Country
- Button: **KE +254**
- Phone number
- Field: placeholder "712 345 678"
- Button (primary): **Send Code (disabled)**
- **Sheet / overlay: Choose a phone number**
  - Shown by Android · Continue with a phone number
  - Row: +254 712 345 678 · SIM 1 · Safaricom
  - Row: +254 733 210 456 · SIM 2 · Airtel
  - Button: **None of the above**

#### ID-04 · Your number, iPhone

`ID04c-Phone-iPhone.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Add your number
- Friends find and pay you by your number. We'll verify it once. · Country
- Button: **KE +254**
- Phone number
- Field: "712 345 678" · placeholder "712 345 678"
- Button (primary): **Send Code**

#### ID-04 · Finish setting up

`ID04d-Phone-finish.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Finish setting up
- One step left. Add your number so friends can find and pay you. · Country
- Button: **KE +254**
- Phone number
- Field: "712 34" · placeholder "712 345 678"
- That number looks too short. Kenyan numbers have 9 digits after +254.
- Button (primary): **Send Code (disabled)**

#### ID-05 · Carrier check didn't work

`ID05-Carrier-fallback.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Add your number
- Friends find and pay you by your number. We'll verify it once. · Country
- Button: **KE +254**
- Phone number
- Field: "712 345 678" · placeholder "712 345 678"
- We couldn't confirm the number automatically, so we'll text you a code instead.
- Button (primary): **Send Code**

#### ID-06 · Enter the code, verifying

`ID06-Enter-code.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Enter the code
- Sent to +254 7•• ••• 678 ·
- Link: Edit number → ID04c-Phone-iPhone.dc.html
- Six-digit code boxes: 4 1 7 9 0 2
- Checking… · The code fills in by itself when the SMS arrives.

#### ID-06 · Wrong code

`ID06b-Wrong-code.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Enter the code
- Sent to +254 7•• ••• 678 ·
- Link: Edit number → ID04c-Phone-iPhone.dc.html
- Six-digit code boxes: 4 1 7 9 0 3
- That code didn't match. 2 tries left.
- Button (plain): **Send a New Code**
- The code fills in by itself when the SMS arrives.

#### ID-06 · Too many tries

`ID06c-Code-locked.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Enter the code
- Sent to +254 7•• ••• 678 ·
- Link: Edit number → ID04c-Phone-iPhone.dc.html
- Six-digit code boxes: _ _ _ _ _ _
- Too many tries. Try again in 10 minutes. · This keeps your number safe if someone else is guessing.

#### ID-07 · Number already registered

`ID07-Number-taken.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** This number already has a CoinX account
- Sign in with the Google or Apple account you used before, and you'll go straight in.
- Button (primary): **Sign In with a Different Account**
- Button (secondary): **Use My Recovery Key**
- Button (plain): **Use a Different Number**

#### ID-08 · Your recovery key

`ID08-Recovery-intro.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Your recovery key
- If you ever lose your Google or Apple account, or your SIM, this key gets you back in. CoinX can't recover it for you. · CoinX will never ask you for this key. Anyone who does is trying to steal your account.
- Button (primary): **Show My Key**

#### ID-09 · Recovery key

`ID09-Key-reveal.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Write down your key
- Keep the paper somewhere safe, away from your phone. · 1 · orbit · 2 · maple · 3 · canvas · 4 · tiger · 5 · humble · 6 · river · 7 · solar · 8 · fabric · 9 · lemon · 10 · anchor · 11 · violet · 12 · kettle · Write these 12 words on paper, in order, or save them as a file. Screenshots are turned off on this screen.
- Button (primary): **I've Saved My Key**
- Button (secondary): **Save as a File**

#### ID-09 · Save the key as a file

`ID09b-Key-save-file.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Write down your key
- Keep the paper somewhere safe, away from your phone. · 1 · orbit · 2 · maple · 3 · canvas · 4 · tiger · 5 · humble · 6 · river · 7 · solar · 8 · fabric · 9 · lemon · 10 · anchor · 11 · violet · 12 · kettle · Write these 12 words on paper, in order, or save them as a file. Screenshots are turned off on this screen.
- Button (primary): **I've Saved My Key**
- Button (secondary): **Save as a File**
- **Sheet / overlay: Save your key as a file**
  - **Heading:** Save your key as a file
  - You'll choose where it goes: a USB drive, your laptop, or a folder only you can open.
  - Row: coinx-recovery-key.txt · 12 words · 1 KB
  - Anyone who opens this file can take your account. Keep it off shared folders and never send it in a chat.
  - Button (primary): **Choose Where to Save**
  - Button (plain): **Cancel**

#### ID-10 · Confirm the key

`ID10-Confirm-key.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Check your key
- Tap the right word for each position. · Word 3 · canvas · Word 7 · solar · Word 11
- Chip: tiger
- Chip: violet
- Chip: canvas
- Chip: orbit
- Chip: river
- Chip: anchor
- Chip: lemon
- Chip: humble
- Chip: solar
- Button (primary): **Continue (disabled)**

#### ID-10 · Confirm, wrong word

`ID10b-Confirm-wrong.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Check your key
- Tap the right word for each position. · Word 3 · canvas · Word 7 · river · Word 11 · violet · That doesn't match your key.
- Button (plain): **View My Key Again**
- Chip: tiger
- Chip: violet
- Chip: canvas
- Chip: orbit
- Chip: river
- Chip: anchor
- Chip: lemon
- Chip: humble
- Chip: solar
- Button (primary): **Continue (disabled)**

#### ID-11 · Create your payment PIN

`ID11-Protect.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Create your payment PIN
- You'll use it to approve every payment. Pick 6 digits only you know.
- `PinPad`

#### ID-11 · Confirm your payment PIN

`ID11d-Confirm-PIN.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Enter it again
- So we know you've got it right.
- `PinPad`

#### ID-11 · PINs didn't match

`ID11b-Protect-declined.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Enter it again
- So we know you've got it right. · Those PINs didn't match. Try again.
- `PinPad`

#### ID-11 · Face ID, optional

`ID11c-Payment-PIN.dc.html` · 390×844 · default light

- **Title:** Approve payments with Face ID?
- Faster than typing your PIN. If it doesn't work, your PIN takes over.
- Button (primary): **Turn On Face ID**
- Button (plain): **Not Now**
- You can turn it on later in Settings.

#### ID-11 · Fingerprint, optional, Android

`ID11e-Fingerprint.dc.html` · 390×844 · default light

- **Title:** Approve payments with your fingerprint?
- Faster than typing your PIN. If it doesn't work, your PIN takes over.
- Button (primary): **Turn On Fingerprint**
- Button (plain): **Not Now**
- You can turn it on later in Settings.

#### ID-12 · You're in

`ID12-Handoff.dc.html` · 390×844 · default light

- **Title:** Your account is ready
- Number verified, key saved, payments protected with your PIN. Now let's set up how people see you.
- Button (primary): **Set Up My Profile**

### Flow B · Returning on a new phone

#### ID-13 · New phone

`ID13-New-phone.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Verify your number on this phone
- CoinX works on one phone at a time. Verifying here signs you out on your other phone.
- Row: Your number · +254 7•• ••• 678
- Button (primary): **Verify My Number**
- Button (plain): **Cancel**

#### B4 · Wrong number on a new phone

`ID13b-Wrong-number.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Verify your number
- Use the number on your CoinX account. · Country
- Button: **KE +254**
- Phone number
- Field: "733 210 456" · placeholder "712 345 678"
- This isn't the number on your account (+254 7•• ••• 678).
- Button (primary): **Try Again**
- Button (plain): **I Changed My Number**

#### ID-14 · Restoring your chats

`ID14-Restoring.dc.html` · 390×844 · default light

- **Title:** Welcome back, Pasaka
- Bringing your conversations and money to this phone. · Phone verified · Payments protected · Restoring your chats · Loading balances

#### ID-15 · Signed out on this phone

`ID15-Signed-out-here.dc.html` · 390×844 · default light

- **Title:** CoinX moved to another phone
- You're signed out here because CoinX is now active on another phone.
- Row: Samsung A54 · Today, 14:02
- Button (primary): **OK**
- Button (destructive): **This Wasn't Me**

### Flow C · Recovery with the 12-word key

#### ID-16 · What do you still have?

`ID16-What-you-have.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Recover your account
- You'll need your 12-word recovery key and one of these.
- Row: My phone number · I lost my Google or Apple account → ID17-Enter-key.dc.html
- Row: My Google or Apple account · I lost my SIM or changed my number → ID17-Enter-key.dc.html
- Row: I've lost both · We'll help you another way → ID22-Lost-both.dc.html
- Don't have your recovery key? Choose "I've lost both".

#### ID-17 · Enter your recovery key

`ID17-Enter-key.dc.html` · 390×844 · default light

- Icon button: Back
- Button (plain): **Paste**
- **Title:** Enter your recovery key
- The 12 words you wrote down when you set up CoinX.
- Field: 1 orbit
- Field: 2 maple
- Field: 3 canvas
- Field: 4 tiger
- Field: 5 humble
- Field: 6 rive
- Field: 7
- Field: 8
- Field: 9
- Field: 10
- Field: 11
- Field: 12
- Button (secondary): **Import from a File**
- Chip: river
- Chip: rival
- Button (primary): **Continue (disabled)**

#### ID-17 · Recovery key imported from a file

`ID17c-Key-imported.dc.html` · 390×844 · default light

- Icon button: Back
- Button (plain): **Clear**
- **Title:** Enter your recovery key
- The 12 words you wrote down when you set up CoinX.
- Field: 1 orbit
- Field: 2 maple
- Field: 3 canvas
- Field: 4 tiger
- Field: 5 humble
- Field: 6 river
- Field: 7 solar
- Field: 8 fabric
- Field: 9 lemon
- Field: 10 anchor
- Field: 11 violet
- Field: 12 kettle
- Filled in from coinx-recovery-key.txt. Check the words, then continue.
- Button (primary): **Continue**

#### ID-17 · Import failed

`ID17d-Import-failed.dc.html` · 390×844 · default light

- Icon button: Back
- Button (plain): **Paste**
- **Title:** Enter your recovery key
- The 12 words you wrote down when you set up CoinX.
- Field: 1
- Field: 2
- Field: 3
- Field: 4
- Field: 5
- Field: 6
- Field: 7
- Field: 8
- Field: 9
- Field: 10
- Field: 11
- Field: 12
- Button (secondary): **Import from a File**
- That file doesn't contain a CoinX key.
- Button (primary): **Continue (disabled)**

#### ID-17 · Wrong recovery key

`ID17b-Key-wrong.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Enter your recovery key
- The 12 words you wrote down when you set up CoinX.
- Field: 1 orbit
- Field: 2 maple
- Field: 3 canvas
- Field: 4 tiger
- Field: 5 humble
- Field: 6 river
- Field: 7 solar
- Field: 8 fabric
- Field: 9 lemon
- Field: 10 anchor
- Field: 11 violet
- Field: 12 kitten
- That key doesn't match. 3 tries left today.
- Button (primary): **Try Again**
- Button (plain): **I Don't Have My Key**

#### ID-18 · Choose your new sign-in

`ID18-New-sign-in.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Choose your new sign-in
- From now on you'll sign in to CoinX with this account. The one you lost stops working. · Number and recovery key confirmed.
- Button (primary): **Continue with Apple**
- Button (secondary): **Continue with Google**

#### ID-19 · Confirm recovery

`ID19-Confirm-recovery.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Here's what will change
- New sign-in · Google · pasaka.m@gmail.com · Other phones signed out · Samsung A54 will be signed out · New recovery key · Your old key stops working · Sending paused for 48 hours · For your safety. You can still chat and receive money.
- Button (primary): **Recover My Account**
- Button (plain): **Cancel**

#### ID-20 · Your new recovery key

`ID20-New-key.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Your new recovery key
- Your old key no longer works. Write these new words down. · 1 · pilot · 2 · sugar · 3 · canvas · 4 · window · 5 · glide · 6 · river · 7 · solar · 8 · coral · 9 · lemon · 10 · anchor · 11 · violet · 12 · bronze · Write these 12 words on paper, in order, or save them as a file. Screenshots are turned off on this screen.
- Button (primary): **I've Saved My Key**
- Button (secondary): **Save as a File**

#### ID-22 · Lost both

`ID22-Lost-both.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** We can't recover this in the app
- Our support team can still help. They'll check who you are with your ID before giving the account back.
- Button (primary): **Contact Support**
- Button (plain): **Back to Sign In**

#### ID-23 · Hold banner on Home

`ID23-Home-hold.dc.html` · 390×844 · default light

- Icon button: Ask CoinX
- Icon button: New chat
- **Title:** Chats
- Search
- Banner: Payments resume in 31 h. Your account was recovered, so sending is paused. Details
- `ListRow` (title: CoinX Assistant, subtitle: Ask me anything about your money)
- `ListRow` (title: Mum, subtitle: Mum sent you KES 2,000 · for lunch, meta: 21:14)
- `ListRow` (title: Holiday Fund, subtitle: Wanjiru added KES 1,000 · 65% of goal, meta: 20:02)
- `ListRow` (title: Otieno, subtitle: You sent KES 150 · for fare, meta: 18:40)
- `ListRow` (title: Mama Njeri, subtitle: Thank you, karibu tena!, meta: Yesterday)
- `ListRow` (title: Achieng, subtitle: See you on Saturday then, meta: Yesterday)
- `ListRow` (title: School Fees, subtitle: Juma: Term 3 is fully paid, meta: Mon)
- `ListRow` (title: Kevin, subtitle: You sent KES 300 · for airtime, meta: Sun)
- `TabBar` (active: chats)

#### ID-23 · Send during the hold

`ID23b-Send-paused.dc.html` · 390×844 · default dark

- Icon button: Ask CoinX
- Icon button: New chat
- **Title:** Chats
- Search
- Banner: Payments resume in 31 h. Your account was recovered, so sending is paused. Details
- `ListRow` (title: CoinX Assistant, subtitle: Ask me anything about your money)
- `ListRow` (title: Mum, subtitle: Mum sent you KES 2,000 · for lunch, meta: 21:14)
- `ListRow` (title: Holiday Fund, subtitle: Wanjiru added KES 1,000 · 65% of goal, meta: 20:02)
- `ListRow` (title: Otieno, subtitle: You sent KES 150 · for fare, meta: 18:40)
- `ListRow` (title: Mama Njeri, subtitle: Thank you, karibu tena!, meta: Yesterday)
- `ListRow` (title: Achieng, subtitle: See you on Saturday then, meta: Yesterday)
- `ListRow` (title: School Fees, subtitle: Juma: Term 3 is fully paid, meta: Mon)
- `ListRow` (title: Kevin, subtitle: You sent KES 300 · for airtime, meta: Sun)
- `TabBar` (active: chats)
- **Sheet / overlay: Sending is paused**
  - **Heading:** Sending resumes in 31 h
  - Your account was recovered on this phone, so sending is paused for 48 hours to keep your money safe. You can still chat and receive money.
  - Button (primary): **OK**
  - Button (plain): **This Wasn't Me**

### Settings · Account and security

#### ID-24 · Account and security, Face ID off

`ID24-Account-security.dc.html` · 390×960 · default light

- Icon button: Back
- Account and Security
- *Section — This phone*
- Row: Phone number · +254 7•• ••• 678 · Verified
- Row: iPhone 16 · Active since 12 Oct 2026
- *Section — Sign-in accounts*
- Row: Google · pasaka.m@gmail.com · Linked
- Row: Apple · Link
- *Section — Payments*
- Row: Payment PIN · Set 12 Oct 2026 · Change
- Row: Face ID · Approve without typing your PIN · Turn On
- *Section — Recovery*
- Row: Recovery key · Created 12 Oct 2026 · Create new
- Row: Security activity
- Row: Sign out
- Row: Remove this phone
- Row: Delete account
- Changes here ask for your PIN first.

#### ID-24 · Change your payment PIN

`ID24b-Set-PIN.dc.html` · 390×844 · default light

- Icon button: Back
- Payment PIN
- **Title:** Enter your current PIN
- Then you'll choose a new one.
- `PinPad`
- Button (plain): **Forgot Your PIN?**

#### ID-24 · Account and security, Face ID on

`ID24c-PIN-on.dc.html` · 390×960 · default light

- Icon button: Back
- Account and Security
- *Section — This phone*
- Row: Phone number · +254 7•• ••• 678 · Verified
- Row: iPhone 16 · Active since 12 Oct 2026
- *Section — Sign-in accounts*
- Row: Google · pasaka.m@gmail.com · Linked
- Row: Apple · Link
- *Section — Payments*
- Row: Payment PIN · Set 12 Oct 2026 · Change
- Row: Face ID · Your PIN takes over if it fails · On
- *Section — Recovery*
- Row: Recovery key · Created 12 Oct 2026 · Create new
- Row: Security activity
- Row: Sign out
- Row: Remove this phone
- Row: Delete account
- Changes here ask for your PIN first.

#### ID-25 · Security activity

`ID25-Security-activity.dc.html` · 390×844 · default light

- Icon button: Back
- Security Activity
- *Section — This month*
- Row: Signed in · iPhone 16 · Today, 09:12
- Row: Recovery key changed · iPhone 16 · 14 Oct, 18:40
- Row: Google linked · iPhone 16 · 12 Oct, 10:05
- Row: New phone · iPhone 16 · 12 Oct, 10:02
- Row: Number verified · iPhone 16 · 12 Oct, 10:01
- Row: Signed out · Samsung A54 · 12 Oct, 10:02
- Row: Something you don't recognise? · Tell us, and we'll help secure your account.

#### ID-26 · Sign out

`ID26-Sign-out.dc.html` · 390×844 · default light

- Icon button: Back
- Account and Security
- *Section — Phone*
- Row: Phone number · +254 7•• ••• 678 · Verified
- *Section — This phone*
- Row: iPhone 16 · Active since 12 Oct 2026
- Row: Sign out
- Row: Remove this phone
- Row: Delete account
- **Sheet / overlay: Sign out of CoinX?**
  - **Heading:** Sign out of CoinX?
  - You can sign back in with Google or Apple. Your chats and money stay safe, and this phone stays yours.
  - Button (primary): **Sign Out**
  - Button (plain): **Cancel**

#### ID-27 · Remove this phone

`ID27-Remove-phone.dc.html` · 390×844 · default light

- Icon button: Back
- Account and Security
- *Section — Phone*
- Row: Phone number · +254 7•• ••• 678 · Verified
- *Section — This phone*
- Row: iPhone 16 · Active since 12 Oct 2026
- Row: Sign out
- Row: Remove this phone
- Row: Delete account
- **Sheet / overlay: Remove this phone?**
  - **Heading:** Remove this phone?
  - For when you sell or give it away. Your CoinX data is wiped from this phone, and the next sign-in on any phone will verify your number again.
  - Button (destructive): **Remove This Phone**
  - Button (plain): **Cancel**

#### ID-28 · Delete account

`ID28-Delete-account.dc.html` · 390×844 · default light

- Icon button: Back
- Delete Account
- **Title:** Delete your account?
- This can't be undone. · Your chats are deleted for good. · Your number and @pasaka are released after 30 days. · Payment records we're required to keep stay with us, locked. · Type DELETE to confirm
- Field: "DELE"
- Button (destructive): **Delete My Account (disabled)**

#### ID-28 · Delete blocked, money in wallet

`ID28b-Delete-has-money.dc.html` · 390×844 · default light

- Icon button: Back
- Delete Account
- **Title:** Send out your money first
- Your wallet still holds KES 3,420. Move it to someone or to an external wallet, then come back to delete your account.
- Row: Wallet balance · Available now · KES 3,420
- Button (primary): **Send My Money**
- Button (plain): **Not Now**

### Account status and errors

#### ID-21 · Suspended

`ID21-Suspended.dc.html` · 390×844 · default light

- **Title:** Your account is paused
- We're reviewing some activity on your account. You can read your chats, but you can't send money or messages until the review is done. · Most reviews finish within 2 working days. We'll message you here and by email.
- Button (primary): **Contact Support**
- Button (plain): **View My Chats**

#### ID-29 · Blocked

`ID29-Blocked.dc.html` · 390×844 · default light

- **Title:** This account can no longer use CoinX
- It broke our terms of use. If you think this is a mistake, our support team can look again.
- Button (primary): **Contact Support**

#### ID-30 · Force update

`ID30-Force-update.dc.html` · 390×844 · default light

- Circaso mark
- **Title:** Update CoinX to continue
- This version is no longer supported. The update keeps your money and chats safe.
- Button (primary): **Update**

#### Error · CoinX is having trouble

`ID31-Backend-down.dc.html` · 390×844 · default light

- **Title:** CoinX is having trouble
- It's on our side, not yours. Try again in a few minutes. Your money is safe.
- Button (secondary): **Try Again**

## 6. C · Chats and money

The heart of the product: chats, and money sent as a message (`#500` in the composer becomes a money chip), the authorise step, the money moment and transaction detail.

### C1 · Chats

`C1-Chats.dc.html` · 390×844 · default dark

- Icon button: Ask CoinX
- Icon button: New chat
- **Title:** Chats
- Search
- `ListRow` (title: CoinX Assistant, subtitle: Ask me anything about your money)
- `ListRow` (title: Mum, subtitle: Mum sent you KES 2,000 · for lunch, meta: 21:14)
- `ListRow` (title: Holiday Fund, subtitle: Wanjiru added KES 1,000 · 65% of goal, meta: 20:02)
- `ListRow` (title: Otieno, subtitle: You sent KES 150 · for fare, meta: 18:40)
- `ListRow` (title: Mama Njeri, subtitle: Thank you, karibu tena!, meta: Yesterday)
- `ListRow` (title: Achieng, subtitle: See you on Saturday then, meta: Yesterday)
- `ListRow` (title: School Fees, subtitle: Juma: Term 3 is fully paid, meta: Mon)
- `ListRow` (title: Kevin, subtitle: You sent KES 300 · for airtime, meta: Sun)
- `TabBar` (active: chats)

### C2 · Chat thread

`C2-Chat-thread.dc.html` · 390×844 · default dark

- Icon button: Back
- `Avatar` (name: Mum Wanjiku, size: sm)
- Mum · Online
- Icon button: Voice call Mum
- Icon button: Video call Mum
- Icon button: Send money to Mum
- Today
- `ChatBubble` (direction: in): "Habari ya leo? Did you eat?"
- `ChatBubble` (direction: out, time: 12:30): "Not yet, still in a meeting"
- `MoneyCard` (direction: in, amount: 2,000, note: for lunch, from: Mum, foot: 15.50 USDT · 12:41)
- `ChatBubble` (direction: out, time: 12:42): "Asante, Mum! I will call you tonight"
- `ChatBubble` (direction: in, time: 18:05): "Sawa. Greet everyone for me"
- `Composer`

### C3 · #amount shortcut

`C3-Money-shortcut.dc.html` · 390×844 · default dark

- Icon button: Back
- `Avatar` (name: Mum Wanjiku, size: sm)
- Mum · Online
- Icon button: Voice call Mum
- Icon button: Video call Mum
- Icon button: Send money to Mum
- Today
- `ChatBubble` (direction: in): "Habari ya leo? Did you eat?"
- `ChatBubble` (direction: out, time: 12:30): "Not yet, still in a meeting"
- `MoneyCard` (direction: in, amount: 2,000, note: for lunch, from: Mum, foot: 15.50 USDT · 12:41)
- `ChatBubble` (direction: out, time: 12:42): "Asante, Mum! I will call you tonight"
- `ChatBubble` (direction: in, time: 18:05): "Sawa. Greet everyone for me"
- `Composer` (chip: #500, text: for lunch tomorrow)

### C4 · Send sheet

`C4-Send-sheet.dc.html` · 390×844 · default light

- Icon button: Back
- `Avatar` (name: Mum Wanjiku, size: sm)
- Mum · Online
- Icon button: Voice call Mum
- Icon button: Video call Mum
- Icon button: Send money to Mum
- Today
- `ChatBubble` (direction: in): "Habari ya leo? Did you eat?"
- `ChatBubble` (direction: out, time: 12:30): "Not yet, still in a meeting"
- `MoneyCard` (direction: in, amount: 2,000, note: for lunch, from: Mum, foot: 15.50 USDT · 12:41)
- `ChatBubble` (direction: out, time: 12:42): "Asante, Mum! I will call you tonight"
- `ChatBubble` (direction: in, time: 18:05): "Sawa. Greet everyone for me"
- **Sheet / overlay: Send**
  - Send
  - Icon button: Close
  - `Avatar` (name: Mum Wanjiku, size: lg)
  - Mum
  - `AmountDisplay` (amount: 500, sub: 3.88 USDT)
  - `TextField` (label: Note, value: for lunch tomorrow)
  - From · USDT wallet · Fee · No fee · Mum receives · KES 500
  - `Button` (variant: primary, size: lg): "Send KES 500"
  - 1 USDT = KES 129.00 · updates in 0:28

### C5 · Authorise

`C5-Authorise.dc.html` · 390×844 · default light

- Icon button: Back
- `Avatar` (name: Mum Wanjiku, size: sm)
- Mum · Online
- Icon button: Voice call Mum
- Icon button: Video call Mum
- Icon button: Send money to Mum
- Today
- `ChatBubble` (direction: in): "Habari ya leo? Did you eat?"
- `ChatBubble` (direction: out, time: 12:30): "Not yet, still in a meeting"
- `MoneyCard` (direction: in, amount: 2,000, note: for lunch, from: Mum, foot: 15.50 USDT · 12:41)
- `ChatBubble` (direction: out, time: 12:42): "Asante, Mum! I will call you tonight"
- `ChatBubble` (direction: in, time: 18:05): "Sawa. Greet everyone for me"
- **Sheet / overlay: Enter your PIN to send**
  - Icon button: Cancel
  - Enter your PIN to send · KES 500 to Mum · for lunch tomorrow
  - `PinPad`
  - Button (plain): **Use Face ID instead**

### C6 · Money moment

`C6-Money-moment.dc.html` · 390×844 · default light

- Icon button: Back
- `Avatar` (name: Mum Wanjiku, size: sm)
- Mum · Online
- Icon button: Voice call Mum
- Icon button: Video call Mum
- Icon button: Send money to Mum
- Today
- `ChatBubble` (direction: out, time: 12:30): "Not yet, still in a meeting"
- `ChatBubble` (direction: in): "Wait, sending you something"
- `MoneyCard` (direction: in, amount: 2,000, note: for lunch, from: Mum, foot: 15.50 USDT · 12:41)
- Chip: Say Thanks
- Chip: View Details
- `Composer`

### C7 · Transaction detail

`C7-Transaction.dc.html` · 390×844 · default light

- Icon button: Back
- Payment
- Icon button: Share receipt
- `Avatar` (name: Mum Wanjiku, size: lg)
- `AmountDisplay` (amount: 2,000, sub: Received from Mum · 16 Oct, 12:41)
- From · Mum · Note · for lunch · Date · 16 Oct 2026, 12:41 · Amount in coin · 15.50 USDT · Rate · 1 USDT = KES 129.00 · Fee · No fee · Status · Completed
- Button: **Advanced Network, transaction ID**
- `Button` (variant: secondary, size: lg): "Share Receipt"
- `Button` (variant: plain): "Report a Problem"

### C8 · New chat

`C8-New-chat.dc.html` · 390×844 · default light

- Button (plain): **Cancel**
- New Chat · Name, number or @handle
- `ListRow` (title: New Group, subtitle: Chat and save together)
- `ListRow` (title: Invite a Friend, subtitle: Share your invite link)
- On CoinX
- `ListRow` (title: Achieng Akinyi, subtitle: @achieng)
- `ListRow` (title: Juma Kariuki, subtitle: @juma.k)
- `ListRow` (title: Kevin Mwangi, subtitle: @kevo)
- `ListRow` (title: Wanjiru Kamau, subtitle: @wanjiru)
- Invite to CoinX
- `ListRow` (title: Baraka Otieno, subtitle: +254 722 104 556)
- `ListRow` (title: Faith Njoki, subtitle: +254 701 889 230)

## 7. D · Groups and chamas

Groups and chamas: creating a group, the group thread, contributing to a goal, the shared group wallet, paying out with approval, joining by link, and business groups.

### D1 · New group

`D1-New-group.dc.html` · 390×844 · default light

- Button (plain): **Back**
- New Group
- Button: **Add group photo**
- `TextField` (label: Group name, value: Holiday Fund)
- 4 members
- `Avatar` (name: Pasaka Mutuku, size: md)
- You
- `Avatar` (name: Otieno Odhiambo, size: md)
- Otieno
- `Avatar` (name: Wanjiru Kamau, size: md)
- Wanjiru
- `Avatar` (name: Achieng Akinyi, size: md)
- Achieng
- Button: **Add members**
- Add · Shared wallet · Members can contribute. Everyone sees every contribution and payment.
- Button: **Shared wallet**
- `TextField` (label: Goal (optional), prefix: KES, value: 50,000)
- `TextField` (label: By (optional), value: 14 Dec)
- Who can spend from the wallet
- `SegmentedControl`
- `Button` (variant: primary, size: lg): "Create Group"

### D2 · Group thread

`D2-Group-thread.dc.html` · 390×844 · default dark

- Icon button: Back
- `Avatar` (name: Holiday Fund, size: sm)
- Holiday Fund · 8 members
- Icon button: Start a group call
- Icon button: Group info
- `GoalProgress`
- `Button` (variant: primary, size: sm): "Add"
- Today · Otieno
- `ChatBubble` (direction: in): "Bus deposit is due Friday. Coast Bus wants KES 12,000"
- `MoneyCard` (direction: in, amount: 1,000, note: Wanjiru added to Holiday Fund, foot: 7.75 USDT · 20:02)
- `ChatBubble` (direction: out, time: 20:05): "Adding mine tonight"
- `Composer`

### D3 · Contribute

`D3-Contribute.dc.html` · 390×844 · default light

- Icon button: Back
- `Avatar` (name: Holiday Fund, size: sm)
- Holiday Fund · 8 members
- Icon button: Group info
- `GoalProgress`
- `Button` (variant: primary, size: sm): "Add"
- Today · Otieno
- `ChatBubble` (direction: in): "Bus deposit is due Friday. Coast Bus wants KES 12,000"
- `MoneyCard` (direction: in, amount: 1,000, note: Wanjiru added to Holiday Fund, foot: 7.75 USDT · 20:02)
- `ChatBubble` (direction: out, time: 20:05): "Adding mine tonight"
- **Sheet / overlay: Add money to Holiday Fund**
  - Add to Holiday Fund
  - Icon button: Close
  - `AmountDisplay` (amount: 1,000, sub: 7.75 USDT)
  - From · USDT wallet · Visible to · All 8 members · Fee · No fee
  - `GoalProgress`
  - `Button` (variant: primary, size: lg): "Add KES 1,000 to Holiday Fund"

### D4 · Group wallet

`D4-Group-wallet.dc.html` · 390×844 · default light

- Icon button: Back
- Holiday Fund
- Icon button: Export statement
- `AmountDisplay` (amount: 32,400, sub: 250.70 USDT · held by the group)
- `GoalProgress`
- `SegmentedControl`
- `ListRow` (title: Wanjiru, subtitle: Yesterday, 20:02, amount: +KES 1,000)
- `ListRow` (title: Otieno, subtitle: 14 Oct, amount: +KES 5,000)
- `ListRow` (title: You, subtitle: 12 Oct, amount: +KES 4,000)
- `ListRow` (title: Achieng, subtitle: 10 Oct, amount: +KES 2,500)
- `ListRow` (title: Juma, subtitle: 8 Oct, amount: +KES 3,000)
- `Button` (variant: primary, size: lg): "Make a Payment"

### D5 · Pay from the group

`D5-Pay-from-group.dc.html` · 390×844 · default dark

- Icon button: Back
- `Avatar` (name: Holiday Fund, size: sm)
- Holiday Fund · 8 members
- Icon button: Group info
- `GoalProgress`
- `Button` (variant: primary, size: sm): "Add"
- Today · Otieno
- `ChatBubble` (direction: in): "Requesting the bus deposit now"
- Payment request
- `Badge` (tone: warning): "1 of 2 approvals"
- KES 12,000 · Otieno wants to pay · Coast Bus · for the bus deposit. · Approved by Otieno · Expires in 47 hours
- `Button` (variant: primary, size: md): "Approve"
- `Button` (variant: secondary, size: md): "Decline"
- `Composer`

### D6 · Join a group

`D6-Join-group.dc.html` · 390×844 · default light

- Icon button: Close
- `Avatar` (name: Holiday Fund, size: xl)
- **Title:** Holiday Fund
- 8 members · Shared wallet
- `Avatar` (name: Otieno Odhiambo, size: md)
- `Avatar` (name: Wanjiru Kamau, size: md)
- `Avatar` (name: Achieng Akinyi, size: md)
- `Avatar` (name: Juma Kariuki, size: md)
- +4 more
- `GoalProgress`
- `Button` (variant: primary, size: lg): "Join Group"
- Otieno invited you. Contributing is always your choice.

### D7 · Business group

`D7-Business-group.dc.html` · 390×844 · default light

- Icon button: Back
- Mama Njeri
- Icon button: Business info
- `Avatar` (name: Mama Njeri, size: md)
- Fresh produce · Kangemi Market · Open today 7:00 to 20:00
- `Button` (variant: primary, size: sm): "Pay"
- Tomatoes KES 20 each · Sukuma KES 30 a bunch · Onions KES 15 each · Avocados KES 25 · Today
- `ChatBubble` (direction: out, time: 08:12): "Habari Mama Njeri, do you have ripe avocados today?"
- `ChatBubble` (direction: in, time: 08:15): "Yes, very good ones. Come before 10"
- `MoneyCard` (direction: out, amount: 140, note: 4 tomatoes, 2 sukuma, foot: To Mama Njeri · 1.09 USDT · 09:40)
- `ChatBubble` (direction: in, time: 09:41): "Thank you, karibu tena!"
- `Composer`

## 8. E · Wallet

The wallet: balance, adding money, identity verification (only needed for fiat on/off-ramp), receiving, sending to an external wallet, cash-out (coming soon, Binance/Coinbase meanwhile) and activity.

### E1 · Wallet

`E1-Wallet.dc.html` · 390×960 · default light

- Icon button: Hide balances
- Icon button: Add money
- **Title:** Wallet
- Total balance
- `AmountDisplay` (amount: 32,180, sub: 249.46 USDT)
- Button: **Add**
- Button: **Send**
- Button: **Receive**
- Button: **Cash Out**
- Coins
- `ListRow` (title: USDT, subtitle: Tether USD · stable to the dollar, meta: KES 32,180, amount: 249.46)
- Recent
- Link: See All → E7-Activity.dc.html
- `ListRow` (title: Mum, subtitle: Received · for lunch, meta: 12:41, amount: +KES 2,000)
- `ListRow` (title: Holiday Fund, subtitle: Contribution, meta: Yesterday, amount: −KES 1,000)
- `ListRow` (title: Mama Njeri, subtitle: Sent · 4 tomatoes, 2 sukuma, meta: Yesterday, amount: −KES 140)
- `ListRow` (title: Added from M-Pesa, subtitle: 0712 345 678, meta: Mon, amount: +KES 5,000)
- `TabBar` (active: wallet)

### E2 · Add money

`E2-Add-money.dc.html` · 390×844 · default light

- Icon button: Back
- Add Money
- `AmountDisplay` (amount: 2,000, sub: You get 15.31 USDT)
- Chip: KES 500
- Chip: KES 2,000
- Chip: KES 5,000
- Pay with
- `ListRow` (title: M-Pesa, subtitle: 0712 345 678)
- `ListRow` (title: Card, subtitle: Visa or Mastercard)
- Rate · 1 USDT = KES 129.00 · Fee · KES 20 · You pay · KES 2,020
- `Button` (variant: primary, size: lg): "Continue to M-Pesa"
- You'll confirm with your M-Pesa PIN on your phone.

### E3 · Verify identity

`E3-Verify-identity.dc.html` · 390×844 · default light

- Icon button: Close
- **Title:** Verify once, add money any time
- Sending and receiving on CoinX never needs this. Moving money in or out of CoinX does, by law. · Your national ID or passport · A photo of the front and back · A quick selfie · To check it is really you holding it · About 3 minutes · Most checks finish in under 10 minutes · Your documents go to our verification partner, encrypted, and are never shown to other people.
- `Button` (variant: primary, size: lg): "Start Verification"
- `Button` (variant: plain): "Not Now"

### E4 · Receive

`E4-Receive.dc.html` · 390×844 · default light

- Icon button: Hide balances
- Icon button: Add money
- **Title:** Wallet
- Total balance
- `AmountDisplay` (amount: 32,180, sub: 249.46 USDT)
- Button: **Add**
- Button: **Send**
- Button: **Receive**
- Button: **Cash Out**
- **Sheet / overlay: Receive**
  - Receive
  - Icon button: Close
  - `SegmentedControl`
  - @pasaka · Anyone on CoinX can scan this or pay @pasaka
  - `Button` (variant: primary, size: lg): "Share"
  - `Button` (variant: secondary, size: lg): "Copy Handle"

### E5 · Send to a wallet

`E5-External-send.dc.html` · 390×844 · default light

- Icon button: Back
- Send to a Wallet · Wallet address
- Field: "0x7a3F…9c21E4b0"
- Icon button: Paste address
- Icon button: Scan a QR code
- Coin · USDT · Network · Base
- `TextField` (label: Amount, prefix: USDT, value: 50.00, hint: About KES 6,450 · You have 249.46 USDT)
- `Banner` (tone: warning): "Make sure the receiving wallet supports USDT on Base. Crypto sent to the wrong network can’t be recovered."
- Network fee · 0.02 USDT · They receive · 49.98 USDT
- `Button` (variant: primary, size: lg): "Review"

### E6 · Cash out (coming)

`E6-Cash-out.dc.html` · 390×844 · default light

- Icon button: Back
- Cash Out
- **Title:** Cash out is coming
- We're waiting on regulatory approval to let you move money from CoinX straight to M-Pesa or your bank. We'd rather do it properly than quickly. · Until then, you can send your USDT to an exchange such as Binance or Coinbase and withdraw from there.
- `Button` (variant: primary, size: lg): "Send to an Exchange"
- `Button` (variant: secondary, size: lg): "Notify Me When It’s Ready"

### E7 · Activity

`E7-Activity.dc.html` · 390×844 · default light

- Icon button: Back
- Activity
- Icon button: Filter by date
- Search people, notes, amounts
- Chip: All
- Chip: Sent
- Chip: Received
- Chip: Groups
- Chip: Added
- Today
- `ListRow` (title: Mum, subtitle: Received · for lunch, meta: 12:41, amount: +KES 2,000)
- `ListRow` (title: Otieno, subtitle: Sent · for fare, meta: 08:20, amount: −KES 150)
- Yesterday
- `ListRow` (title: Holiday Fund, subtitle: Contribution, meta: 20:10, amount: −KES 1,000)
- `ListRow` (title: Mama Njeri, subtitle: Sent · 4 tomatoes, 2 sukuma, meta: 09:40, amount: −KES 140)
- `ListRow` (title: Kevin, subtitle: Received · thanks for Saturday, meta: 07:55, amount: +KES 600)
- Monday
- `ListRow` (title: Added from M-Pesa, subtitle: 0712 345 678, meta: 18:02, amount: +KES 5,000)

## 9. F · Assistant and settings

The CoinX/Circaso assistant, the You tab, security and privacy settings, and notifications.

### F1 · Ask CoinX

`F1-Ask-CoinX.dc.html` · 390×844 · default light

- Icon button: Back
- `Avatar` (name: CoinX Assistant, size: sm)
- CoinX Assistant · Reads your activity · can't move money · Today
- `ChatBubble` (direction: out, time: 21:20): "How much did I send Mum this month?"
- `ChatBubble` (direction: in): "You sent Mum KES 6,500 this month, across 4 payments. That’s KES 1,500 more than September."
- `ListRow` (title: for lunch, subtitle: 16 Oct, amount: KES 500)
- `ListRow` (title: Rent top-up, subtitle: 10 Oct, amount: KES 3,000)
- `ListRow` (title: for church, subtitle: 6 Oct, amount: KES 1,000)
- `ListRow` (title: Groceries, subtitle: 2 Oct, amount: KES 2,000)
- From your CoinX activity · tap a payment to see it
- Chip: How is Holiday Fund doing?
- Chip: What did I spend last week?
- `Composer`

### F2 · You

`F2-You.dc.html` · 390×1400 · default light

- Icon button: Share profile
- **Title:** You
- `Avatar` (name: Pasaka Mutuku, size: lg)
- Pasaka Mutuku · @pasaka · +254 712 345 678 · Account
- `ListRow` (title: Profile)
- `ListRow` (title: Verification)
- Money
- `ListRow` (title: Display currency, meta: KES)
- `ListRow` (title: Default coin, meta: USDT)
- `ListRow` (title: Limits)
- Security
- `ListRow` (title: Security and Privacy)
- Preferences
- `ListRow` (title: Notifications)
- `ListRow` (title: Language, meta: English)
- `ListRow` (title: CoinX Assistant, subtitle: Ask about your money by text or voice)
- Help
- `ListRow` (title: Help Centre)
- `ListRow` (title: Replay the Welcome Tour)
- `ListRow` (title: Contact Support)
- `ListRow` (title: Terms and Privacy)
- Row: Sign Out
- `Button` (variant: destructive, size: sm): "Delete Account"
- `TabBar` (active: you)

### F3 · Security and privacy

`F3-Security.dc.html` · 390×844 · default light

- Icon button: Back
- Security and Privacy · Unlock
- `ListRow` (title: Face ID, subtitle: Unlock and confirm payments)
- `ListRow` (title: Change PIN)
- `ListRow` (title: Auto-lock, meta: After 2 minutes)
- `ListRow` (title: Hide balances on open)
- Active devices
- `ListRow` (title: iPhone 16, subtitle: This device · Nairobi)
- `ListRow` (title: Pixel 9, subtitle: Nairobi · 2 days ago)
- Privacy
- `ListRow` (title: Find me by number, meta: My contacts)
- `ListRow` (title: Blocked people, meta: None)
- Every change here asks for your PIN or Face ID first.

### F4 · Notifications

`F4-Notifications.dc.html` · 390×844 · default light

- Circaso mark
- Mum · now · Sent you KES 2,000 · for lunch
- Circaso mark
- Holiday Fund · 2m ago · Wanjiru added KES 1,000 · 65% of goal
- Circaso mark
- Holiday Fund · 1h ago · Otieno needs your approval to pay KES 12,000
- Circaso mark
- CoinX · 3h ago · You’re verified. You can add money now.
- Circaso mark
- Kevin · Yesterday · Sent you money

## 10. S · Support

Customer support as a normal chat, with free in-app audio calls to a real person, plus a way in for people who can't sign in.

### SP-01 · Help

`SP01-Help.dc.html` · 390×960 · default light

- Icon button: Back
- Help
- **Title:** How can we help?
- Real people in Nairobi, every day from 7am to 10pm.
- Row: Chat with us · Replies in about 2 min → SP02-Support-chat.dc.html
- Row: Call us · Free over the internet, about 4 min wait → SP03-Calling-support.dc.html
- *Section — What's it about?*
- Row: A payment · Sent, received, stuck or missing
- Row: Signing in or my phone · New phone, number, Google or Apple
- Row: PIN or recovery key · Forgot it, lost it, want to change it
- Row: Something else
- *Section — Your conversations*
- Row: A payment to Otieno · Wanjiru: It's on its way now. Anything else? · Yesterday
- CoinX will never ask for your PIN, recovery key or SMS code. Not in chat, not on a call.

### SP-02 · Support chat

`SP02-Support-chat.dc.html` · 390×844 · default light

- Icon button: Back
- Circaso mark
- CoinX Support · Verified CoinX team
- Icon button: Call CoinX Support
- Today · Case CX-48213
- `ChatBubble` (direction: out, time: 10:02): "Hi, I sent money to Otieno an hour ago and it still says pending"
- KES 2,000 to Otieno · Pending · Today, 08:58 · Wanjiru from CoinX Support joined · Wanjiru · CoinX Support
- `ChatBubble` (direction: in): "Habari Pasaka! I can see it. The network was slow this morning, so it's waiting to confirm."
- `ChatBubble` (direction: in, time: 10:04): "I've pushed it through. Otieno should get it in a few minutes. Want me to stay on until it lands?"
- Chip: Yes, please
- Chip: Call me instead
- Chip: That's all
- `Composer`

### SP-03 · Calling support, in the queue

`SP03-Calling-support.dc.html` · 390×844 · default light

- Encrypted call · Free over the internet
- Circaso mark
- **Title:** CoinX Support
- You're 2nd in line · about 3 min · We'll never ask for your PIN, recovery key or SMS code.
- Icon button: Mute
- Icon button: Speaker
- Icon button: Chat instead
- Icon button: End

### SP-04 · On a call with support

`SP04-On-call.dc.html` · 390×844 · default light

- Encrypted call · Free over the internet
- `Avatar` (name: Wanjiru Kamau, size: xl)
- **Title:** Wanjiru
- Circaso mark
- CoinX Support · Verified · 03:41 · Case CX-48213 · We'll never ask for your PIN, recovery key or SMS code.
- Icon button: Mute
- Icon button: Speaker
- Icon button: Open chat
- Icon button: End

### SP-05 · Conversation ended

`SP05-Chat-ended.dc.html` · 390×844 · default light

- Icon button: Back
- CoinX Support
- `Avatar` (name: Wanjiru Kamau, size: xl)
- **Title:** Did Wanjiru sort it out?
- Your payment to Otieno arrived at 10:09. Case CX-48213 is closed.
- Button (secondary): **Yes**
- Button (secondary): **Not really**
- Button (primary): **Back to Chats**
- Button (plain): **Reopen This Case**
- A copy of the conversation stays in Help.

### SP-06 · Help without signing in

`SP06-Help-signed-out.dc.html` · 390×844 · default light

- Icon button: Back
- **Title:** Get help without signing in
- Tell us the number on your CoinX account. We'll text a code so we know it's you, then connect you. · Country
- Button: **KE +254**
- Phone number
- Field: "712 345 678" · placeholder "712 345 678"
- No access to that number either? Choose Chat with us and an agent will check your ID instead.
- Button (primary): **Send Code and Chat**
- Button (secondary): **Call Us Instead**

## 11. G · System states

System states: offline and a failed send.

### G1 · Offline

`G1-Offline.dc.html` · 390×844 · default light

- Icon button: Ask CoinX
- Icon button: New chat
- **Title:** Chats
- `Banner` (tone: offline): "You’re offline · Showing chats as of 21:05"
- `ListRow` (title: Mum, subtitle: Mum sent you KES 2,000 · for lunch, meta: 21:14)
- `ListRow` (title: Holiday Fund, subtitle: Wanjiru added KES 1,000 · 65% of goal, meta: 20:02)
- `ListRow` (title: Otieno, subtitle: You sent KES 150 · for fare, meta: 18:40)
- `ListRow` (title: Achieng, subtitle: See you on Saturday then, meta: Yesterday)
- `TabBar` (active: chats)

### G2 · Send failed

`G2-Send-failed.dc.html` · 390×844 · default light

- Icon button: Back
- `Avatar` (name: Mum Wanjiku, size: sm)
- Mum · Online
- Icon button: Send money to Mum
- **Sheet / overlay: Send**
  - Send
  - Icon button: Close
  - `Avatar` (name: Mum Wanjiku, size: lg)
  - Mum
  - `AmountDisplay` (amount: 500, sub: 3.88 USDT)
  - Couldn't send · Your money hasn't moved. Check your connection and try again. · From · USDT wallet · Fee · No fee · Mum receives · KES 500
  - `Button` (variant: primary, size: lg): "Try Again"

## 12. H · Calls and live

Calls: incoming, 1:1 voice with money mid-call, video, chama group calls with live contributions, contributing during a call, and business live selling (after the pilot).

### H1 · Incoming call

`H1-Incoming-call.dc.html` · 390×844 · default dark

- CoinX voice call
- **Title:** Mum
- is calling you…
- Icon button: Message
- Icon button: Send money
- Icon button: Decline
- Icon button: Accept

### H2 · Voice call, money mid-call

`H2-Voice-call.dc.html` · 390×844 · default dark

- End-to-end encrypted
- **Title:** Mum
- 04:12 · You sent KES 500 · for lunch
- Icon button: Mute
- Icon button: Speaker
- Icon button: Video
- Icon button: Send money
- Icon button: Add people
- Icon button: End

### H3 · Video call

`H3-Video-call.dc.html` · 390×844 · default dark

- Mum · 12:48 · Mum sent you KES 2,000 · for lunch
- Icon button: Flip camera
- Icon button: Mute
- Icon button: Turn camera off
- Icon button: Send money
- Icon button: End call

### H4 · Chama group call, live goal

`H4-Group-call.dc.html` · 390×844 · default light

- Icon button: Minimise call
- Holiday Fund · 6 on the call · 18:20
- Icon button: Add people
- Otieno · Wanjiru · Achieng · Juma · Faith · You · LIVE · Contributions land as they happen
- `GoalProgress`
- Juma +KES 2,500 · Wanjiru +1,000
- Icon button: Mute
- Icon button: Turn camera on
- Button: **Contribute**
- Icon button: Leave call

### H5 · Contribute during the call

`H5-Contribute-on-call.dc.html` · 390×844 · default dark

- Otieno · Wanjiru · Achieng · Juma
- **Sheet / overlay: Contribute to Holiday Fund**
  - `Avatar` (name: Holiday Fund, size: md)
  - Contribute to Holiday Fund · Everyone on the call sees it land
  - Icon button: Close
  - `GoalProgress`
  - `AmountDisplay` (amount: 1,000, sub: 7.75 USDT)
  - Chip: 500
  - Chip: 1,000
  - Chip: 2,500
  - Chip: Other
  - `Button` (variant: primary, size: lg): "Contribute KES 1,000"
  - Confirm with Face ID

### H6 · Business live selling (after pilot)

`H6-Business-live.dc.html` · 390×844 · default dark

- Mama Njeri's Kibanda · LIVE · 128 watching
- Icon button: Leave live
- Faith · Are the tomatoes from Kirinyaga? · Kevin · Nimeagiza mbili · Achieng · Do you deliver to Kilimani? · Baraka bought Sukuma bundle · S · SHOWING NOW · Sukuma bundle · KES 50
- Button: **Buy**
- T · Tomatoes, 1 kg · KES 120
- Button: **Buy**
- Ask Mama Njeri…
- Icon button: Open catalogue

## 13. W · Website

The pre-launch waitlist website. W1 is the desktop page (also live in the `coinx-web` project); W2 renders the same page at phone width.

### W1 · Waitlist site, desktop

`W1-Landing.dc.html` · 1440×8000 · default light

- Link: Circaso
- Link: How it works
- Link: Groups
- Link: Calls
- Link: Shop live
- Link: Safety
- Link: FAQ
- Button (primary): **Join the Waitlist**
- Something new is coming to your chats
- **Title:** Money that says something.
- Not a transaction. Not a confirmation code. Lunch money from Mum, fare for a friend, the whole chama cheering as the goal fills up. CoinX puts the feeling back into sending money. · Phone number or email
- Field: placeholder "0712 345 678 or you@email.com"
- Button (primary): **Join the Waitlist**
- Invites go out in waves. Get yours early. · You're on the list. · We'll be in touch when your invite is ready.
- Shows the D2-Group-thread screen inside a phone
- Shows the C2-Chat-thread screen inside a phone
- **Section `#how`**
- Three taps. One feeling.
- **Heading:** Say it with money.
- If you can send a message, you already know how. The rest is just how it feels.
- `Composer` (chip: #500, text: for lunch)
- 1
- **Heading:** Type it like you'd say it
- #500 for lunch. That's it. The amount and the reason travel together.
- `Button` (variant: primary, size: md): "Send KES 500 to Mum"
- 2
- **Heading:** Mean it
- A glance or your PIN, and it's on its way. Nothing moves without you.
- `MoneyCard` (direction: in, amount: 500, note: for lunch, from: You, foot: 3.88 USDT · just now)
- 3
- **Heading:** Watch it land
- It arrives inside the conversation, with your words on it. Not a code in an SMS. · Not a receipt. A moment.
- **Heading:** Every shilling carries who sent it, and why.
- Money has always been personal. The way we send it never was. On CoinX it lands like a message from someone who cares: their face, their words, and a small moment when it arrives. · And between CoinX friends, it's free.
- `ChatBubble` (direction: in): "Habari ya leo? Did you eat?"
- `ChatBubble` (direction: out, time: 12:30): "Not yet, still in a meeting"
- `MoneyCard` (direction: in, amount: 2,000, note: for lunch, from: Mum, foot: 15.50 USDT · 12:41)
- `ChatBubble` (direction: out, time: 12:42): "Asante, Mum! I will call you tonight"
- **Section `#calls`**
- Voice and video calls
- **Heading:** Call them. Send it without hanging up.
- Voice and video calls with the people you already know, right from the chat. Hear Mum say asante while the lunch money lands on her screen. · Voice and video, one-to-one · Send money mid-call, it lands on their screen · Answer, decline, or reply with money
- Shows the H2-Voice-call screen inside a phone
- Shows the H3-Video-call screen inside a phone
- **Section `#groups`**
- `Avatar` (name: Holiday Fund, size: md)
- Holiday Fund · 8 members · Group wallet
- `GoalProgress`
- `ListRow` (title: Wanjiru, subtitle: for the bus deposit, amount: +KES 1,000)
- `ListRow` (title: Otieno, subtitle: my share, asante, amount: +KES 2,500)
- `ListRow` (title: Achieng, subtitle: for the beach day, amount: +KES 1,500)
- Chamas, finally in one place
- **Heading:** Watch your people show up.
- Every group gets its own wallet. Every contribution lands in the chat for everyone to see, and the goal fills up one "I've sent mine" at a time. · A shared wallet for every group · Contributions everyone can see · Goals that fill up in real time
- **Section `#chama-calls`**
- Shows the H4-Group-call screen inside a phone
- Chama calls
- **Heading:** The meeting and the money, finally in one room.
- Start a voice or video call with the whole group. Someone says "I'm sending mine", a glance at Face ID, and everyone watches it land and the goal bar jump. Harambee, in real time.
- **Section `#live`**
- Community shops
- **Heading:** Your favourite shop, live in your pocket.
- Every business gets a community: its catalogue, its regulars, and a chat where questions get answered. When the shop goes live, you watch, ask, and buy in one tap, while everyone sees the orders roll in. · A catalogue inside every business community · Go live to show the goods and answer questions · Buy in one tap, confirm with Face ID
- Shows the H6-Business-live screen inside a phone
- **Section `#safety`**
- Safety
- **Heading:** Feels light. Built serious.
- **Heading:** You approve every send
- Face ID or your PIN, every time money leaves your wallet.
- **Heading:** Free between CoinX friends
- Sending to anyone on CoinX costs nothing. Sending to an outside wallet has a network fee, shown before you confirm.
- **Heading:** An assistant that can't move money
- Ask "how much did I send Mum this month?" It can read your history. It can never send.
- **Heading:** Only people you know
- You can reach people in your contacts, or who shared their CoinX code with you. No strangers in your chats. · Coming next
- **Heading:** And then, no phone number at all.
- The boda rider. The mama fua. The kibanda on your corner. Soon you'll pay the person right in front of you with a tap, even when your data runs out. We'll say more soon. · Nearby · Verified only
- `ListRow` (title: Juma · Boda, subtitle: Verified · right next to you)
- `ListRow` (title: Mama Njeri's Kibanda, subtitle: Verified · a few steps away)
- `ListRow` (title: Kevin's Car Wash, subtitle: Verified · across the road)
- **Section `#faq`**
- **Heading:** Questions
- When does CoinX launch? · + · We're opening in Kenya first, in small waves. Join the waitlist and you'll get an invite as soon as your spot comes up. · Is CoinX crypto? · + · Under the hood, yes. CoinX moves USDT, a digital dollar. In the app you see shillings and names, never wallet addresses. · How do I add money? · + · For now, buy USDT on Binance or Coinbase and send it to your CoinX wallet. Adding money straight from M-PESA is coming in our next phase. · Do the people I send to need CoinX? · + · To get money in a chat, yes. Invite them from the app and they're in your chats the moment they join. · Can I call people on CoinX? · + · Yes. Voice and video calls, one-to-one and with your whole group. You can send or contribute money without leaving the call. · Can I withdraw to M-PESA? · + · Not yet. We're building cash-out to M-PESA and bank for our next phase. Until then, send your USDT to Binance or Coinbase and convert it there. · How much does it cost? · + · Signing up is free, and sending to anyone on CoinX is free. Sending to an outside wallet, like Binance or Coinbase, has a network fee that you see before you confirm.
- Circaso mark
- **Heading:** Someone's about to send you something.
- Get on the list, and bring the people you'd send money to. · Phone number or email
- Field: placeholder "Phone number or email"
- Button (primary): **Join the Waitlist**
- You're on the list. We'll be in touch.
- Circaso mark
- Circaso · © 2026 · Nairobi, Kenya
- Link: Privacy
- Link: Terms
- Link: info@coinx.co.ke

### W2 · Waitlist site, phone

`W2-Landing-phone.dc.html` · 390×8000 · default light

- Same content as W1, rendered at 390 px wide.

## 14. Website, emails and admin (built)

The waitlist site is built and lives in the `coinx-web` project (GitHub `Coinx-Inc/CoinxWebsite`). It is one Cloudflare Worker with static assets, a D1 database (`coinx-waitlist`), Turnstile against bots, Resend for email, and an admin page behind Cloudflare Access. `public/index.html` is exported from the W1 artboard.

### How the waitlist works

1. Someone enters an email or phone number. They are saved as **Waiting to confirm** and get a "One tap to save your spot" email.
2. Tapping the link (`/confirm`) makes them **Confirmed**, gives them a place in line and sends "You're #N in line".
3. From the admin, invites go to the next N in line or to selected people; they become **Invited** and get the invite email with the app link.
4. Every email has a leave-the-waitlist link (`/unsubscribe`), which asks for confirmation first so email link scanners can't remove people.

### Emails

All share one layout (`emails/layout.html`): the Circaso mark and wordmark at the top, a footer with the leave-the-waitlist link and `info@coinx.co.ke`. Money cards are titanium as in the app; Apple Mail and iOS Mail switch to dark mode.

| Template | Sent when | Subject |
| --- | --- | --- |
| `confirm` | Right after someone joins | One tap to save your spot |
| `reminder` | Admin presses Resend for someone unconfirmed and last emailed over a day ago | Your spot is still waiting |
| `welcome` | They confirm | You're #N in line |
| `invite` | Admin sends invites | Your CoinX invite is here |

Every email carries an idempotency key, `List-Unsubscribe` headers and a `category` tag.

### Admin (`/admin`)

- **Waitlist:** counts by status, a 30-day sign-up chart, top referral tags and countries, search, status filter and paging; per row change status, add a note, resend the right email, delete with a second tap; add an email by hand; export CSV; send invites.
- **Website:** open or close the waitlist with a closed message, an announcement bar, override the headline and intro without redeploying, and set the invite link.
- **Emails:** preview each email (designed or plain text) and send a test to yourself.
- **Activity:** the last 100 admin actions.

### Brand files

`favicon.svg`, `apple-touch-icon.png`, `icon-512.png`, `og.png` (share image: the mark with "Circaso · Money that says something. Coming soon to your chats.") and `email/coin.png`, all using the Circaso mark.

## 15. Decisions and open questions

### Decided

- **Name and mark:** Circaso, with the "Circle and centre" mark (18 Oct 2026). Brand otherwise unchanged from CoinX.
- **Sign-in:** Google or Apple only, then a verified phone number. No passwords.
- **Payment PIN is mandatory; biometrics are optional** and fall back to the PIN.
- **Recovery key at sign-up** (ID-08 to ID-10), with save-as-file and import-from-file.
- **Recovery** = key + one other factor; 48-hour sending pause afterwards.
- **One phone per account;** a new phone signs the old one out.
- **Support** is a chat with free in-app audio calls; calls are labelled "Encrypted call", not end-to-end, because they run through Circaso's servers.
- **Cash-out** is "coming soon" in-app; users go via Binance or Coinbase meanwhile. Circaso-to-Circaso sends are free; external wallet sends have fees.
- **`#amount` in the composer (Pasaka, 2026-11-09):** `#` followed by a digit is money wherever it is typed, straight after a word too ("lunch#500"); a link's anchor (`site.com/page#12`) stays text. While typed it reads as money in the person's unit ("KES 500", `#` hidden); once something follows it, it becomes C3's money chip in place, reading the amount ("KES 500", not the frame's "#500"), and the rest of the text stays as typed.
- **Money during a call (Pasaka, 2026-11-09):** the money button on H2/H3 and H4's contribute open a sheet over the call with the call chat's composer and money card (the same `#500 for lunch` flow as the chat). It replaces H5's preset form and leaving the call for Pay; the group sheet keeps H5's head (goal, "Everyone on the call sees it land", progress). H1's "Send money instead" declines and opens the chat with `#` ready.

### Open

- **Copy rename:** body copy still says "CoinX" in many screens (assistant, support, notifications, emails' sender name and invite subject).
- **Forgot your PIN:** the link on ID-24b has no flow yet. Suggested: recovery key plus number check, then a new PIN.
- **Recovery key file is unencrypted:** anyone with the file can take the account. Options: encrypt it with the payment PIN, or warn and leave it as is.
- **Large payments:** should Face ID be required above an amount, given the PIN alone can approve any payment?
- **Support links:** "Contact Support" on ID-21, ID-22 and ID-29 should point to SP-06; Help needs an entry in the You tab.
- **H2 voice call** still says "End-to-end encrypted"; change to "Encrypted" if calls go through the SFU.
- **Old first-launch screens** A3 to A8 can be removed once the Identity flow is final.
- **Rooted or jailbroken phones:** warn, limit payments, or allow silently.
- **W2 artboard** is capped at 8,000 px on the canvas, so the phone render of the website is cut off there (the live site is fine).
