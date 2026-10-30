# Circaso design tokens

Extracted from `assets/Circaso-Screens.html` (generated there from `tokens.json`; CSS comments still say CoinX). Map these to the Compose theme; do not invent new values. Colors are semantic roles, so screens switch themes by swapping the whole set.

## Themes (colour roles)

Four themes: `light`, `dark`, `light-hc`, `dark-hc` (high contrast). The product is monochrome: `--action` is the ink colour, `--critical` is the only red, `--warning` the only amber.

### :root, [data-theme="light"]
```css
--bg: #ffffff;
--bg-grouped: #f2f2f7;
--surface: #ffffff;
--surface-raised: #ffffff;
--fill: #e9e9eb;
--fill-strong: #dcdce0;
--fill-selected: #ffffff;
--separator: #e3e3e8;
--ink: #0b0b0c;
--ink-secondary: #636366;
--ink-tertiary: #7c7c80;
--action: var(--ink);
--on-action: #ffffff;
--money: var(--ink);
--money-surface: var(--fill);
--money-card: #2b2c30;
--money-card-sheen: #4a4c52;
--on-money: #ffffff;
--critical: #c8102e;
--warning: #9a5800;
--focus: var(--ink);
--glass: rgba(255,255,255,0.72);
--glass-edge: rgba(0,0,0,0.08);
--scrim: rgba(0,0,0,0.32);
--shadow-float: 0 8px 30px rgba(0,0,0,0.10);
```

### [data-theme="dark"]
```css
--bg: #000000;
--bg-grouped: #000000;
--surface: #1c1c1e;
--surface-raised: #2c2c2e;
--fill: #2c2c2e;
--fill-strong: #3a3a3c;
--fill-selected: #636366;
--separator: #38383a;
--ink: #f5f5f7;
--ink-secondary: #98989f;
--ink-tertiary: #8e8e93;
--action: var(--ink);
--on-action: #0b0b0c;
--money: var(--ink);
--money-surface: var(--fill);
--money-card: #c9cacf;
--money-card-sheen: #f2f3f6;
--on-money: #0b0b0c;
--critical: #ff5a50;
--warning: #ffb340;
--focus: var(--ink);
--glass: rgba(30,30,32,0.72);
--glass-edge: rgba(255,255,255,0.12);
--scrim: rgba(0,0,0,0.56);
--shadow-float: 0 8px 30px rgba(0,0,0,0.55);
```

### [data-theme="light-hc"]
```css
--bg: #ffffff;
--bg-grouped: #f2f2f7;
--surface: #ffffff;
--surface-raised: #ffffff;
--fill: #e4e4e8;
--fill-strong: #d1d1d6;
--fill-selected: #ffffff;
--separator: #8a8a8e;
--ink: #000000;
--ink-secondary: #3a3a3c;
--ink-tertiary: #48484a;
--action: var(--ink);
--on-action: #ffffff;
--money: var(--ink);
--money-surface: var(--fill);
--money-card: #1c1d20;
--money-card-sheen: #3a3c41;
--on-money: #ffffff;
--critical: #8c0a1e;
--warning: #6e3f00;
--focus: var(--ink);
--glass: rgba(255,255,255,0.96);
--glass-edge: rgba(0,0,0,0.45);
--scrim: rgba(0,0,0,0.5);
--shadow-float: 0 0 0 1px rgba(0,0,0,0.45);
```

### [data-theme="dark-hc"]
```css
--bg: #000000;
--bg-grouped: #000000;
--surface: #1c1c1e;
--surface-raised: #2c2c2e;
--fill: #3a3a3c;
--fill-strong: #48484a;
--fill-selected: #48484a;
--separator: #8e8e93;
--ink: #ffffff;
--ink-secondary: #c7c7cc;
--ink-tertiary: #aeaeb2;
--action: var(--ink);
--on-action: #000000;
--money: var(--ink);
--money-surface: var(--fill);
--money-card: #e0e1e5;
--money-card-sheen: #ffffff;
--on-money: #000000;
--critical: #ff928b;
--warning: #ffc266;
--focus: var(--ink);
--glass: rgba(28,28,30,0.96);
--glass-edge: rgba(255,255,255,0.5);
--scrim: rgba(0,0,0,0.7);
--shadow-float: 0 0 0 1px rgba(255,255,255,0.5);
```

## Scale: spacing, radius, controls, avatars, motion, fonts

```css
--space-1: 4px;
--space-2: 8px;
--space-3: 12px;
--space-4: 16px;
--space-5: 20px;
--space-6: 24px;
--space-8: 32px;
--space-10: 40px;
--space-14: 56px;
--radius-xs: 6px;
--radius-sm: 10px;
--radius-md: 14px;
--radius-lg: 22px;
--radius-sheet: 34px;
--radius-pill: 999px;
--control-lg: 50px;
--control-md: 44px;
--control-sm: 34px;
--avatar-sm: 28px;
--avatar-md: 44px;
--avatar-lg: 64px;
--avatar-xl: 96px;
--duration-quick: 150ms;
--duration-base: 280ms;
--duration-moment: 900ms;
--font-sans: -apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Inter", "Segoe UI", Roboto, sans-serif;
--font-rounded: ui-rounded, "SF Pro Rounded", -apple-system, BlinkMacSystemFont, "Inter", sans-serif;
```

## Type styles

iOS-style scale; `amount-*` use the rounded font and are for money only.

```css
.large-title { font-family: var(--font-sans); font-size: 34px; line-height: 41px; font-weight: 700; letter-spacing: 0.37px; }
.title-1 { font-family: var(--font-sans); font-size: 28px; line-height: 34px; font-weight: 700; letter-spacing: 0.36px; }
.title-2 { font-family: var(--font-sans); font-size: 22px; line-height: 28px; font-weight: 700; letter-spacing: -0.26px; }
.title-3 { font-family: var(--font-sans); font-size: 20px; line-height: 25px; font-weight: 600; letter-spacing: -0.45px; }
.headline { font-family: var(--font-sans); font-size: 17px; line-height: 22px; font-weight: 600; letter-spacing: -0.43px; }
.body { font-family: var(--font-sans); font-size: 17px; line-height: 22px; font-weight: 400; letter-spacing: -0.43px; }
.callout { font-family: var(--font-sans); font-size: 16px; line-height: 21px; font-weight: 400; letter-spacing: -0.31px; }
.subhead { font-family: var(--font-sans); font-size: 15px; line-height: 20px; font-weight: 400; letter-spacing: -0.23px; }
.footnote { font-family: var(--font-sans); font-size: 13px; line-height: 18px; font-weight: 400; letter-spacing: -0.08px; }
.caption-1 { font-family: var(--font-sans); font-size: 12px; line-height: 16px; font-weight: 400; letter-spacing: 0px; }
.caption-2 { font-family: var(--font-sans); font-size: 11px; line-height: 13px; font-weight: 400; letter-spacing: 0.06px; }
.amount-hero { font-family: var(--font-rounded); font-size: 56px; line-height: 60px; font-weight: 600; letter-spacing: -0.8px; }
.amount-large { font-family: var(--font-rounded); font-size: 40px; line-height: 44px; font-weight: 600; letter-spacing: -0.5px; }
.amount-medium { font-family: var(--font-rounded); font-size: 28px; line-height: 32px; font-weight: 600; letter-spacing: -0.3px; }
.amount-small { font-family: var(--font-rounded); font-size: 17px; line-height: 22px; font-weight: 600; letter-spacing: -0.2px; }
```

## Components seen in the canvas

Button (primary/secondary/plain/destructive; lg 50, md 44, sm 34; pill radius; busy state shows a spinner), icon button (36 round), text field (50 high, `--fill` box, radius-md, label above in footnote), segmented control, tab bar, list row (`cx-row`: title, sub, meta, end), detail row (key/value), PIN keypad and dots, glass bars (`--glass`), bottom sheets (radius-sheet 34), money card (`--money-card`). Pull the exact markup with `scripts/extract_screen.py`.
