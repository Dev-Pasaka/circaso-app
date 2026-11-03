# Offline-first and trust

Circaso is a money app. People must be able to trust it, so it must never feel shady: no spinner that never ends, no fake success, no data that vanishes, no surprise sign-out. **A signed-in person uses the app normally without a connection**, except for actions that truly need the server. When the connection drops they are told plainly; when it returns everything catches up on its own and nothing is lost.

## Principles
1. **Launch never waits for the network.** A signed-in person sees cached data at once (Room), then it refreshes quietly. No blocking splash on a request.
2. **Reads come from Room.** Repositories expose a `Flow` from the database and refresh from the network in the background. Screens never show an empty state just because the phone is offline.
3. **Say what is true.** A global offline indicator appears when the phone is offline and goes away when it is back. Anything not confirmed by the server says so ("Waiting to send", "Saved on this phone"). Never show a queued action as done. Where staleness matters, show "Updated 5 min ago".
4. **Never silently lose or fake anything.** If a queued action fails after reconnect, tell the person what happened in plain words and offer retry or discard. No optimistic success for anything that matters.
5. **A network failure is not a sign-out.** Timeouts, no connection and `5xx` never clear tokens or local data. Sign out only on an explicit server answer (`identity.session_revoked`, or the refresh being rejected with a session error).
6. **No endless waiting.** Every request has a timeout and every wait ends in a clear state (loading, offline, failed with retry).

## Designed states (design: G1, G2, G3, G3b, G4)
`assets/Circaso-Offline-States.html` designs the two states that were missing. Use this copy and behaviour.
- **Banner, chat list (G3b):** "You're offline. 2 messages waiting to send." The count is of what is waiting. With nothing waiting it is the G1 form: "You're offline · Showing chats as of 21:05".
- **Chat with unsent messages:** the list preview shows "Waiting to send: <text>" with a **Waiting** badge. Inside the chat the header says **"Waiting for network"** (back online: "Online"). An unsent message sits faded with a clock and "Waiting to send". The composer strip says "You're offline. Messages send when you reconnect."
- **Messages typed offline are kept on the phone and send automatically, in order**, when the connection returns. This is the outbox for chat messages.
- **Money never queues.** Wallets are user-controlled, so a payment can only be signed and sent while the phone is online. Offline, typing `#500` still shows the money chip, but the send button is dimmed and the strip says "You're offline. Money sends only while you're connected."
- **Back online (G4):** once waiting messages go out, a glass toast says "Back online · 2 messages sent". Shown once, about 3 seconds, **only if something was waiting**. Messages switch to the normal sent state with their real times. If a send fails after reconnecting, G2 Send failed applies.
Messaging's backend exists since 2026-10-28 (keys, chats, sending, inbox, receipts); the encryption keys themselves are **online-only** (published, rotated and topped up by `KeyMaintainer` whenever the phone is signed in and online, never queued), while sending a text message is the queueable write these states describe.

## Which actions may work offline
Classify every action before building its screen. Record the class next to the use case.

| Class | Rule | Examples |
| --- | --- | --- |
| **Read** | Always works from cache. | Profile, contacts, connections, blocks, Asha history, security log |
| **Queueable write** | Safe to apply later, idempotent, low stakes. Goes in the outbox, shows "Waiting to send". | Edit profile, block/unblock, remove a contact, contact sync batches, push token update, avatar upload (two steps), post a moment, moment reply (a chat message), mute someone's moments, moments privacy (a phone setting) |
| **Online-only** | Needs a live answer. Disable the control with "Needs a connection" before the tap, never fail after it. | Sign-in, phone verification, device registration, handle availability and claim, connecting through invite/QR, linking logins, account deletion, recovery request, Asha chat/voice/speech, create a group, set up a group or space wallet (Circle PIN), add a target, request or approve a payout |
| **Money (future)** | **Never queued, never optimistic.** Needs live confirmation; if the send did not reach the server the screen says "Not sent" and nothing is taken. | Sending, receiving, cash-out, group contributions and payouts, paid space entry; the fee quote is live and held 15 minutes |

## The outbox (queueable writes)
- A Room table in `core/sync`: action type, payload, `idempotencyKey`, createdAt, state (`pending`, `sending`, `failed`, `done`), attempts, last error `code`.
- **Mint the `Idempotency-Key` when the action is queued** and reuse it on every retry. The server keeps the first answer for **24 hours**, so a queued action older than about 20 hours must not be replayed blindly: refetch the real state first, then decide or ask the person.
- Run in order per entity. Backoff with jitter, obey `Retry-After`. Stop retrying on a permanent `4xx` and surface it (`users.version_conflict` means reload and ask; never overwrite).
- Survives process death and reboot (Room). Draining is idempotent, so a crash mid-send is safe: rows left in `sending` are put back in `pending` at every app start (`Outbox.requeueInterrupted`), keeping their original key.
- Phone-book names never leave the phone and are not stored in Room.

## Connectivity
- Platform connectivity monitor behind `expect/actual` (Android `ConnectivityManager`, iOS `NWPathMonitor`); no library. Expose `Online | Offline` as a flow, debounced so a flapping network does not flicker the banner.
- "Connected" is not "reachable" (captive portals, dead Wi-Fi). Treat real request outcomes as the truth and move to Offline after repeated network failures even if the OS says connected.

## On reconnect, in this order
1. If the access token expired while offline, refresh first (single-flight, signed with a **fresh** timestamp; the 5-minute clock rule applies).
2. Reconnect the realtime socket with the stored `last` seq per module; a `resync` frame means reload that module over HTTP.
3. Drain the outbox.
4. Refresh the visible lists.
The socket is an optimisation, never required for the app to work.

## Sessions offline
- The access token lives 15 minutes; a person offline longer keeps using the app from cache. Do not treat an expired token as "signed out"; refresh when the network returns.
- A wrong phone clock gives `identity.device_proof_invalid`: show "check your date and time", do not sign out.
- Profile picture URLs expire in 1 hour: keep the last image in the Coil disk cache and treat an expired link as "refresh the profile", not as an error.

## Testing matrix (run before calling a feature done)
Airplane mode from launch; losing the connection mid-request; slow and flaky network; switching Wi-Fi to mobile; killing the app with items in the outbox; token expired while offline; wrong phone clock; a queued item older than 24 hours; `version_conflict` on replay; server `5xx` then recovery. Check wording on both platforms and in Light and Dark.

## Definition of done (adds to the integration guide, section 10)
- The screen works offline for its Read and Queueable actions and is honest for the rest.
- Every action is classified (Read / Queueable / Online-only / Money) and the class matches this file.
- No path clears tokens or local data on a network error.
- The states "offline", "waiting to send", "failed, retry or discard" and "caught up" follow the designs G1, G3, G3b, G4 and G2.
