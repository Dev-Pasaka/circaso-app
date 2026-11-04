---
name: circaso-mobile
description: The Circaso mobile app spec for this Kotlin Multiplatform project - the design canvas (107 screens, tokens, themes) and the backend integration guide (auth, device key, endpoints, realtime, Asha, what is and is not built). Use when building or reviewing any screen, theme, ViewModel, repository, Ktor client call, sign-in or token code, or when deciding whether a feature has a backend yet.
---

# Circaso mobile

Three sources, kept in this skill so they travel with the repo. Open only what the task touches; both are large.

## Start here when resuming

Read `references/project-status.md` first: where the code is, every design decision, what is built, pending tasks,
known bugs and open questions. The next feature (Spaces tab: Moments, spaces with shops and live, group wallets, fees
and limits) is planned in `references/spaces-plan.md`, with all decisions taken; its next step is phase 0, design.
Update both files when things change.

| Need | Open |
| --- | --- |
| Project status: decisions, built, pending, bugs, open questions (2026-11-03) | `references/project-status.md` |
| Asha next: settings inside Asha, the animated mascot (Rive; scenes, moods, activities), support and tickets through Asha (decided 2026-11-04) | `references/asha-next.md` |
| Plan for Spaces, Moments, group wallets, shops, fees and limits (decided 2026-11-03) | `references/spaces-plan.md` |
| How to call the backend: headers, tokens, device key, flows, every endpoint, encrypted chats and messages, realtime, Asha, request/response types, build order (updated 2026-10-28; read its "Changed on" notes in section 1 first) | `references/mobile-integration.md` |
| What a screen says and does: copy, buttons, components, states, per flow; design decisions and open questions | `references/design-spec.md` (99 KB; read the contents table, then only the section you need; section 15 has decisions) |
| Exact markup of a screen | `references/design-screens.md` (index), then `python3 scripts/extract_screen.py <id>` |
| Offline behaviour, outbox, reconnect order, what may work offline, trust rules, the designed offline states (G1-G4) | `references/offline-first.md` |
| Logo, app icon, Asha icon files and where they live | `references/brand-assets.md` |
| Where code goes, layers, DI, state, navigation rules, build order | `references/app-architecture.md` |
| Building UI: the design-system rules, components, and what differs per platform | `references/design-system.md` |
| Colours, spacing, radius, type, themes | `references/design-tokens.md` |
| The full design canvas | `assets/Circaso-Screens.html` (1 MB, mostly base64 photos) and `assets/Circaso-Offline-States.html` (the G3/G4 offline states; **never read it whole**, use the script or grep) |

## Rules that apply to all mobile work

1. **Backend contract: the integration guide wins.** If the design shows something the backend lacks, it is MISSING: build the UI behind a feature flag, do not invent an endpoint. If the guide and the live server disagree, the server wins; tell Pasaka and fix the guide.
2. **Look and copy: the design wins.** Build every screen from its frame, including its error, loading, offline and empty variants (they are separate cards). Use the tokens by role, never raw hex. Support all four themes (light, dark, light-hc, dark-hc) and Dynamic Type.
3. **The name is Circaso.** Older frames and CSS comments say CoinX; do not ship that string.
4. **Stack:** Kotlin Multiplatform, one `shared` module, Ktor client (OkHttp on Android, Darwin on iOS), kotlinx.serialization, one version catalog, no second networking library. Platform code goes behind `expect`/`actual` or injected interfaces (section 4 of the guide).
5. **Non-negotiables from the guide:** `X-Client-Version` on every request; a fresh `Idempotency-Key` on every non-`GET` and the **same** key on retries; single-flight token refresh; UI text keyed on the error `code`, never `message`; never log tokens, codes, keys or phone numbers; show `requestId` on error screens.
6. **Offline-first and trust (a product requirement, not polish).** A signed-in person uses the app normally with no connection; only truly online-only actions need the server. Read from Room, queue safe writes in an outbox, tell the person plainly when they are offline and when it is back, and never fake success, lose data or sign anyone out because of a network error. Money actions are never queued or optimistic. Read `references/offline-first.md` before building any screen or repository.
7. **Asha is one `/asha` away.** Typing `/asha` in a text field on any screen opens the Asha widget, except on screens that collect a secret (PIN, recovery key words, anything password-like). Secret inputs must be marked `secret` in the design system so the command never fires there and the text never reaches Asha or any log. The typed `/asha` is removed from the field, not sent. Asha needs the server: offline, the widget still opens and shows history, and says it needs a connection (see `offline-first.md`). Open question to confirm with Pasaka: is `/asha` meant for any text field, or only a chat composer?
8. **Device key formats are where bugs hide** (ES256, SPKI public key, DER signature, exact payload strings, iOS 26-byte SPKI header, and the key-upload payload `timestamp + "\n" + hex(SHA-256(body))` signed over the exact body bytes sent, uncompressed). Re-read section 5 before touching `DeviceKeyStore` or the key uploads.
9. **Retries:** `503` `service.unavailable`, `5xx` and timeouts retry with backoff and the **same** `Idempotency-Key`; `409` `request.in_progress` means the first attempt is still running (on device registration, keep the other answer and do not retry).

## Known gaps between design and backend (as of 2026-10-28)

The design canvas is ahead of the backend, and in one place it disagrees.

- **Recovery key and payment PIN (design ID-08 to ID-11, ID-16 to ID-20, ID-23, ID-24):** decided 2026-10-25 (Pasaka delegated this to "the industry standard and what a premium app should be"). **Sign-up follows the backend model:** Google/Apple, verified phone, hardware device key. No recovery key or PIN screens in sign-up. **Why:** a recovery key and a payment PIN protect a wallet, and the wallet does not exist yet ("Payments, wallet MISSING"); showing a 12-word key during sign-up with nothing behind it would be theatre, and for a trust-critical app that is the shady kind. **When the wallet is designed:** the on-device wallet key and a recovery phrase are created at wallet creation (first money action), not at sign-up; a payment PIN or biometrics approve each send (industry standard for fintech); an optional app lock (biometric/PIN) is a local feature. Lost-account recovery today is the backend's support case (`POST /v1/recovery/requests`). The design screens stay as the target for the wallet phase.
- **Superseded design screens:** A3, A4, A5, A7, A8 are replaced by the ID pages.
- **Built in backend since 2026-10-27 and in the app since 2026-10-28:** end-to-end encrypted chats (C1, C2, C8, D1, D2 without the money parts; G2/G3 states): encryption keys (M1-M3), direct chats and groups with roles (M4-M11), sending, inbox, acks, receipts, typing, delete for everyone (M12-M14, M22), the `messaging` realtime frames, Android push. Encryption is libsignal (PQXDH) on the phone; the server only sees public keys and ciphertext. See `app-architecture.md` → "Chats and messages". Not yet: iOS notification extension, Asha's chat tools, media. The wallet and payments (W1–W5, W9–W12, E1/E4/E5/E7, C3–C7) are in the app since 2026-10-29 with the Circle SDK in its own module (`:circlewallet`, needs GitHub Packages credentials; iOS needs CocoaPods): see `app-architecture.md` → "Wallet and payments". Asha's live voice (A11, hands-free, barge-in) is in the app since 2026-10-29: see `app-architecture.md` → "Asha live voice".
- **Chamas (D2–D6) are in the app since 2026-10-29** on the backend's `chama` module (CH1–CH6, treasurer model, PR awaiting merge): see `app-architecture.md` → "Chamas". Phase-1 gaps closed the same day (ID-23 hold banner, A10 Meet Asha, F4 local notifications, ID-28b delete blocked, C6 quick replies): see "Phase 1 design gaps".
- **MISSING in backend (design exists, no API):** chat media, chat history and its key backup; add money, KYC and cash out (E2, E3, E6); calls (H); a server notification inbox (F4 is built locally); business groups (D7). Push is PARTIAL: new messages, group adds, payments and chama requests push. Support chat (S) is not in the guide either.
- **Built in backend and in design:** launch/welcome/sign-in (ID-01 to ID-03), phone verification (ID-04 to ID-07), new phone (ID-13 to ID-15), account and security (ID-24 to ID-28 map to `/v1/account/*`, `/v1/auth/signout`, `DELETE /v1/devices/current`, `DELETE /v1/account`), force update (ID-30, `426`), contacts (A9), Asha (A10, F1), profile (F2), chats (C, D; text only until media ships).
- **Support accounts and the admin site (decided 2026-10-31):** support is a dedicated, verified **support account kind** with **no wallet** (no sending, no receiving; money is off in support chats) that **can't sign in on the phones**. The team works from an **admin site** (Next.js on Cloudflare, Google Workspace sign-in, the backend's `ops` API). The site covers support chats **and** general admin: users (including who is signed in now), transactions, groups. Support chats are decrypted by a support service on the server that holds the support account's Signal keys (official `org.signal:libsignal-client`); the app tells people "Support chats are read by the Circaso support team." **The admin site is on hold: mobile first** (Pasaka, 2026-10-31). Until a support account exists, `Support.UserId` is null and no chat is treated as support (Pasaka's own account is an ordinary account again).
- **Account deletion is final:** 30 days after `DELETE /v1/account` the server erases the account; there is no restore route, so the app must not promise one.

## Working a screen

1. Find it in `references/design-screens.md`; extract its markup with the script (pass the card id or a substring, `--list` for all ids).
2. Check the guide for the calls behind it (section 7 catalogue; section 6 for the flow it belongs to; section 11 for types).
3. Map every colour/size in the markup to a token role. If you need a value the tokens lack, ask rather than add one.
4. Cover each error `code` in the catalogue's *Errors* column with copy, and the common failures table in section 3.
5. Definition of done: section 10 of the guide.

## Refreshing this skill

- `references/mobile-integration.md` is a copy of `circaso/docs/mobile-integration.md` in the backend repo (the project root keeps the same copy as `mobile-integration.md`). When Pasaka hands over a new version, replace both and delete the old one; last replaced 2026-10-28.
- `references/design-spec.md` is a copy of `Circaso-Design-Spec.md` at the project root. Re-copy it when the spec changes.
- `assets/Circaso-Screens.html` is a copy of the exported canvas at the project root. After a new export, replace it and regenerate `references/design-screens.md` and `references/design-tokens.md` (both were extracted from it by script).
