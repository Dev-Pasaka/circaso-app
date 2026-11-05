# Spaces API (backend `spaces` module): contract for Spaces phase 3 (Spaces core)

Written 2026-11-04, before either side was built, so the backend module and both apps build against the same thing.
Source of decisions: `spaces-plan.md` (§5, §5b, §7, §7b, §8, §9 phase 3) and the approved phase 0b frames in
`Circaso-Spaces.html` (SD, SS, SN, SM, SI, SH, SR, SW and "Design decisions in the gaps" → Phase 3). When the
backend's `docs/mobile-integration.md` gains its Spaces section, that copy wins (rule 1 of the skill) and this file is
updated to match. Backend branch `feat/spaces` (merged with phase 2F, so fees are live for space payments).

Conventions as everywhere: base `/v1`, scope `app` unless marked `ops`, `DefaultResponse` envelope, `Idempotency-Key`
on every non-GET (the gateway replays the first answer for 24 h), ids are UUIDv7 strings, times ISO-8601 UTC, money
`{ asset: "USDC", amountMinor: "5000000", amount: "5" }` (strings, as the wallet), pages `?cursor=&limit=` (default 50,
max 200) answering `next` (null on the last page).

Error `details` are the shared shape: `fields` is a list of `{ field, problem }`, so "`details.fields.availableAt`"
below means the entry whose `field` is `availableAt` (its `problem` holds the value, an ISO time or a list).

## Model

- A **space** has a unique **@handle** (its own namespace, separate from people's handles; 3–30 characters `a-z`, `0-9`,
  `.` and `_`, starting with a letter, stored lowercase; link `circaso.app/s/{handle}`, deep link
  `circaso://space/@{handle}`), a **name** (1–60), **about** (0–300), one **topic**, an optional **photo** and
  **cover**, and an **owner** (exactly one).
- **Topics** (one list everywhere): `BUSINESS`, `SHOPPING`, `FARMING`, `HEALTH`, `FAITH`, `SPORT`, `BOOKS`, `CHAMAS`,
  `MUSIC`, `OTHER`.
- **Visibility** (who can join): `PUBLIC` (in search and suggestions; anyone **follows** to join, free),
  `MEMBERS_ONLY` (in search with its price; **pays** to join; `price` required), `INVITE_ONLY` (never in search or
  suggestions; joins by **invite** or an accepted **request**; free).
- **Price** (members-only only): `{ asset: "USDC", amountMinor, amount, period: ONE_TIME | MONTHLY | YEARLY }`, 0.10 to
  10,000 USDC. Set in USDC; the app shows the payer's currency first.
- **State**: `SETTING_UP` (made, its wallet not ready: seen only by its owner and team, nobody can find, follow or
  pay it), `LIVE`, `DELETED` (by its owner), `REMOVED` (by Circaso). A space goes `LIVE` the moment its wallet is
  `ACTIVE` (plan §7: every space has a wallet).
- **Posting mode**: `EVERYONE` (default) or `ADMINS_ONLY` (owner and admins post; everyone reads and reacts).
- **Private messages**: `ANYONE` (anyone who can see the space may message the owner, default) or `MEMBERS`. An
  invite-only space is always `MEMBERS`.
- **Roles** (SR2): `OWNER` (one; everything; the only signer of the space wallet; price and who can join; appoints
  admins; deletes) ⊃ `ADMIN` (remove and ban members, invite, answer requests, edit details and who can post, see the
  space wallet, appoint and unappoint moderators) ⊃ `MODERATOR` (remove posts, mute, handle reports) ⊃ `MEMBER`
  (post if allowed, react, report, message the owner privately, pay the space).
- **Membership** (per person and space): `role`, `joinedAt`, `invitedBy`, `notifications` (`ALL` default,
  `MENTIONS` = replies to you and posts by the team, `OFF`), `mutedUntil`, and for a paid space `paidUntil`
  (null for one-time) and `state`: `ACTIVE` → `GRACE` (from `paidUntil` for **3 days**: everything still works) →
  `ENDED` (chat, and later shop and lives, locked; own posts stay; the space stays in "Your spaces" with Rejoin).
- **Space chat is not end-to-end encrypted** (plan §5b): posts are stored on the server (encrypted at rest), so the
  server can search, moderate and page them. The **private chat with the owner** is an ordinary end-to-end encrypted
  direct chat (messaging, M4) marked `viaSpaceId`.

## Spaces (create, read, change)

| Id | Route | Body → answer | Notes |
| --- | --- | --- | --- |
| SP1 | `GET /spaces/handles/{handle}/availability` | → `{ handle, available, suggestions: [handle] }` | `handle` without `@`, any case. `suggestions`: three free handles when taken or invalid (SN1b). Errors: `spaces.handle_invalid` (400). |
| SP2 | `POST /spaces` | `CreateSpace` → `Space` | SN1–SN3. Online only. Makes the space `SETTING_UP` with the caller as `OWNER` and the team as members with their roles; then set up its wallet with **W18** and add networks with W13 `?walletId=` (SN4, SN5). The gateway's `Idempotency-Key` replay makes Try Again safe (SN7b). Errors: `spaces.account_too_new` (403, `details.fields.availableAt`), `spaces.too_many_spaces` (429, `Retry-After`; 3 in 24 h), `spaces.account_restricted` (403), `spaces.handle_invalid`, `spaces.handle_taken` (409, `details.fields.suggestions` comma-separated), `spaces.details_invalid` (400, `details.fields.<field>`), `spaces.price_invalid` (400), `spaces.media_not_found` (404), `spaces.team_invalid` (400: more than 20, yourself, a repeat, `OWNER` role, or someone you can't reach). |
| SP3 | `GET /spaces/{spaceId}` | → `Space` | Anyone the space is visible to: `LIVE` public and members-only spaces to everyone; an invite-only one to anyone (SS3 opens from a link) with only its outside (`about` null, `known` only) unless they're in it or invited (SI2b sees `about`); `SETTING_UP` only to its owner and team. Errors: `spaces.space_not_found` (404: also when not visible), `spaces.space_unavailable` (404, `details.fields.reason` = `DELETED` or `REMOVED`, `details.fields.name`, SS6d). |
| SP4 | `GET /spaces/by-handle/{handle}` | → `Space` | The same as SP3, for links and QR codes. |
| SP5 | `PATCH /spaces/{spaceId}` | `UpdateSpace` → `{ space: Space, notified: Int }` | Each field left out stays. Admins: `name`, `about`, `topic`, `photoMediaId`, `coverMediaId` (`removePhoto: true` / `removeCover: true` take them away), `postingMode`. Owner only: `handle`, `visibility`, `price`, `privateMessages`. A **price** (or period) change applies to each member from their next period and is announced at once (`space.price_changed` frame and push to every member; `notified` counts them, SN8b). Changing to members-only keeps everyone already in as a member with no paid period (`membership.complimentary: true`), until they leave or are removed. Errors: `spaces.not_admin`, `spaces.not_owner` (403), `spaces.handle_invalid`, `spaces.handle_taken`, `spaces.details_invalid`, `spaces.price_invalid`, `spaces.media_not_found`, `spaces.space_not_found`. |
| SP6 | `DELETE /spaces/{spaceId}` | → `{ deleted: true }` | Owner only. The space wallet must be empty first (`spaces.wallet_not_empty`, 409). The space becomes `DELETED`: every member gets `space.updated`, SP3 answers `spaces.space_unavailable`; payments stay in everyone's wallet history. |
| SP7 | `GET /spaces/mine?cursor=&limit=` | → `{ spaces: [MySpace], next }` | "Your spaces" (SD1): every space you're in (including an `ENDED` membership, which keeps its place) and your own `SETTING_UP` spaces, newest activity first. |
| SP8 | `GET /spaces/suggested?topic=&cursor=&limit=` | → `{ spaces: [Suggestion], next }` | SD1, SD3: `LIVE` public and members-only spaces you're not in and not banned from: the ones your contacts are in first, then popular in your country (`CF-IPCountry`), then popular. Default `limit` 20. |
| SP9 | `GET /spaces/search?q=&topic=&cursor=&limit=` | → `{ exact: SpaceSummary?, spaces: [SpaceSummary], next }` | SD2. `q` 1–60 characters. Words match name, handle, about and topic (PostgreSQL full text, then prefix). A `q` starting with `@` searches handles only: `exact` is the exact handle (SD2b's "Exact match"), `spaces` the similar ones (prefix). Only `LIVE` public and members-only spaces; invite-only never. People are searched on the phone (contacts and moments), not here. Errors: `request.malformed`. |

`CreateSpace`:
```json
{ "name": "Kilimani Yoga Collective", "handle": "kilimaniyoga", "about": "Sunrise yoga…", "topic": "HEALTH",
  "photoMediaId": "…?", "coverMediaId": "…?", "visibility": "MEMBERS_ONLY",
  "price": { "asset": "USDC", "amountMinor": "10000000", "period": "MONTHLY" },
  "postingMode": "EVERYONE", "privateMessages": "ANYONE",
  "team": [ { "userId": "…", "role": "ADMIN" }, { "userId": "…", "role": "MODERATOR" } ] }
```
`price` (`amountMinor` or `amount`, both only if they agree) is required for `MEMBERS_ONLY` and refused otherwise.

`SpaceSummary`: `{ spaceId, handle, name, about?, topic, photoUrl?, coverUrl?, visibility, price?, memberCount,
state }`. Photo and cover URLs are pre-signed GETs that live 1 hour: cache the bytes, never the URL.

`Space` = `SpaceSummary` + `{ owner: Person, postingMode, privateMessages, createdAt, version, known: { names:
[String], count }, wallet?: { walletId, status: NONE | SETTING_UP | ACTIVE, canSign }, me: Me }`. `known` is the
people in your contacts who are in the space (up to 3 names, and how many, SS1 "Juma, Faith and 1 other contact
follow", SS3 "Wanjiru is a member"). `wallet` only for the owner and admins (SN8, SW1c). `version` grows with every
change (`space.updated` carries it).

`Person`: `{ userId, displayName, handle?, avatarUrl? }` (users' public profile; never a phone number).

`Me`:
```json
{ "relation": "NONE | MEMBER | ENDED | REQUESTED | INVITED | BANNED",
  "role": "OWNER | ADMIN | MODERATOR | MEMBER | null",
  "joinedAt": "…?", "invitedBy": "Person?",
  "membership": { "state": "ACTIVE | GRACE | ENDED", "period": "ONE_TIME | MONTHLY | YEARLY",
                  "paidUntil": "…?", "graceUntil": "…?", "renewFrom": "…?", "complimentary": false,
                  "nextPrice": "Money?" } ,
  "mutedUntil": "…?", "notifications": "ALL | MENTIONS | OFF",
  "request": { "state": "PENDING | DECLINED", "at": "…", "canAskAgainAt": "…?" },
  "invite": { "inviteId": "…", "invitedBy": "Person", "invitedAt": "…", "expiresAt": "…" },
  "unreadCount": 0, "lastReadPostId": "…?" }
```
`membership` only in a members-only space you've paid for (or were kept in for free, `complimentary`). `renewFrom` is
when Renew opens: 3 days before `paidUntil` (7 for yearly; SM3). `nextPrice` is the price your next period will cost
when the owner changed it (SM3b says "was 5.00 USDC"). `mutedUntil` far in the future (`9999-12-31T…`) means "until
unmuted". `relation: BANNED` → SS6e. `request.canAskAgainAt` is 30 days after a decline.

`MySpace`: `{ space: SpaceSummary, me: Me, lastPost: { postId, authorName, text?, kind, at, removed }?, unreadCount
(capped at 99), activityAt }`. A row shows Renew when `me.membership.renewFrom` has passed (SD1).

`Suggestion`: `SpaceSummary` + `{ reason: CONTACTS | POPULAR_IN_COUNTRY | POPULAR, known: { names, count } }`.

## Joining

| Id | Route | Body → answer | Notes |
| --- | --- | --- | --- |
| SP10 | `POST /spaces/{spaceId}/follow` | → `Me` | Public spaces: you're a `MEMBER` at once (SS1b). Not optimistic: show progress until it answers. Errors: `spaces.banned` (403), `spaces.payment_required` (403: members-only, use SP13), `spaces.invite_required` (403: invite-only, use SP14), `spaces.space_not_found`, `spaces.account_restricted`. Following again when already in answers your `Me`. |
| SP11 | `DELETE /spaces/{spaceId}/membership` | → `{ left: true }` | Unfollow or leave (SS1c, SM6). A paid membership ends now and isn't refunded. Errors: `spaces.owner_cannot_leave` (409), `spaces.not_member` (403). |
| SP12 | `PUT /spaces/{spaceId}/notifications` | `{ level: ALL \| MENTIONS \| OFF }` → `Me` | Errors: `spaces.not_member`. |
| SP13 | `POST /spaces/{spaceId}/membership/payment` | → `MembershipPayment` | Members-only spaces: what joining, renewing or rejoining costs, held 15 minutes (SM1, SM3b, SM5). Then **W17** and **W4** with `to: { spaceId }`, `spacePaymentId`, exactly `amount` (and W17's `quoteId` on W4), from your **personal** wallet, on one of `blockchains`. You're a member only when the transfer is **confirmed** (`space.membership_updated`), never from the tap (SM1c). Errors: `spaces.not_paid_space` (409), `spaces.already_member` (409: one-time membership already paid, or a period active and not yet in its renewal window, `details.fields.renewFrom`), `spaces.banned`, `spaces.wallet_not_ready` (409), `spaces.space_not_found`, `spaces.account_restricted`. |

`MembershipPayment`:
```json
{ "paymentId": "…", "spaceId": "…", "kind": "JOIN | RENEWAL | REJOIN",
  "amount": { "asset": "USDC", "amountMinor": "5000000", "amount": "5" }, "period": "MONTHLY",
  "periodStart": "…", "periodEnd": "…?", "previousAmount": "Money?",
  "walletId": "…", "blockchains": ["BASE-SEPOLIA"], "expiresAt": "…" }
```
`periodStart`/`periodEnd` are the period this payment would buy if it confirmed now (a renewal starts at
`paidUntil`; joining, rejoining or renewing in grace starts the day paid; one-time has no end). `previousAmount` when
the price changed since you last paid (SM3b). The space receives exactly `amount`; the fee is on top (W17 quotes it;
the space rate, seed 1 %, from phase 2F). Calling SP13 again makes a fresh payment; a payment confirmed after another
still extends from the latest end, so money paid is never lost.

### Invites and requests

| Id | Route | Body → answer | Notes |
| --- | --- | --- | --- |
| SP14 | `POST /spaces/{spaceId}/requests` | → `Me` | Request to Join an invite-only space (SS3). If you hold a live invite, this accepts it. Errors: `spaces.request_too_soon` (409, `details.fields.canAskAgainAt`: 30 days after a decline), `spaces.banned`, `spaces.already_member`, `spaces.not_invite_only` (409), `spaces.space_not_found`. Admins get `space.request_new` (and a push). |
| SP15 | `DELETE /spaces/{spaceId}/requests/mine` | → `Me` | Cancel Request (SS3b). |
| SP16 | `GET /spaces/{spaceId}/invites` | → `{ requests: [JoinRequest], invites: [SentInvite] }` | SI4, admins. `requests`: pending, oldest first. `invites`: the last 90 days, newest first, `state` `INVITED` (with `expiresAt`; a declined invite stays `INVITED` until it expires, decisions: "a decline is never announced"), `JOINED`, `EXPIRED`. Cancelled ones aren't listed. Errors: `spaces.not_admin`. |
| SP17 | `POST /spaces/{spaceId}/requests/{userId}/accept` · `…/decline` | → `{ state: ACCEPTED \| DECLINED }` | Admins. Accept makes them a `MEMBER` (`space.membership_updated` and a push, "You're in …"); decline is told to them by frame only (no push) and blocks a new request for 30 days. Errors: `spaces.not_admin`, `spaces.request_not_found` (404). |
| SP18 | `POST /spaces/{spaceId}/invites` | `{ userIds: [1..50] }` → `{ results: [{ userId, outcome }] }` | Admins, public and invite-only spaces (members-only: share the link; `spaces.invite_invalid`). `outcome`: `INVITED` (new or refreshed: 14 more days), `ALREADY_MEMBER`, `BANNED`, `UNREACHABLE` (blocked either way, or no such account). Find people from contacts or by @username with users' `GET /users/by-handle/{handle}` (SI1b's close matches come from contacts on the phone). Each invitee gets `space.invited` and a push. |
| SP19 | `DELETE /spaces/{spaceId}/invites/{inviteId}` | → `{ cancelled: true }` | Admins (SI4 Cancel). The card leaves the invitee's Chats (`space.invite_removed`). |
| SP20 | `GET /spaces/invites/mine` | → `{ invites: [ReceivedInvite] }` | The invite cards at the top of Chats (SI2), newest first, live only (not expired, cancelled, declined or accepted). |
| SP21 | `POST /spaces/invites/{inviteId}/accept` | → `Space` | SI3. You join as a `MEMBER`; the inviter gets `space.invite_accepted` (no push). Errors: `spaces.invite_not_found` (404: expired, cancelled, answered or not yours), `spaces.banned`. |
| SP22 | `POST /spaces/invites/{inviteId}/decline` | → `{ declined: true }` | SI3b. Nobody is told. The app shows Undo for a few seconds **before** sending this (there is no undo on the server). |

`JoinRequest`: `{ user: Person, requestedAt }`. `SentInvite`: `{ inviteId, user: Person, invitedBy: Person, state,
invitedAt, expiresAt, joinedAt? }`. `ReceivedInvite`: `{ inviteId, space: SpaceSummary, invitedBy: Person,
invitedAt, expiresAt, known: { names, count } }`.

## Members, roles and moderation

| Id | Route | Body → answer | Notes |
| --- | --- | --- | --- |
| SP23 | `GET /spaces/{spaceId}/members?filter=&q=&cursor=&limit=` | → `{ team: [Member], contacts: [Member], members: [Member], count, next }` | SS5 for everyone who can see inside the space: `team` (owner, admins, moderators), `contacts` (your contacts in it), `count`; `members` is empty for them. Admins and moderators (SR1) also get `members`, paged by name, with `filter` `ALL` (default), `MUTED`, `BANNED` (admins; banned people are not members, `role` null) and `q` (name or handle prefix). `team` and `contacts` only on the first page. Errors: `spaces.members_only` (403), `spaces.space_not_found`. |
| SP24 | `PUT /spaces/{spaceId}/members/{userId}/role` | `{ role: ADMIN \| MODERATOR \| MEMBER }` → `Member` | The owner sets any role; admins make or unmake moderators. The person gets `space.membership_updated` and a push for a new role. Errors: `spaces.not_admin`, `spaces.not_owner`, `spaces.cannot_moderate` (403: the owner, yourself, or a role at or above yours), `spaces.not_member` (404-like 403: they aren't in the space). |
| SP25 | `PUT /spaces/{spaceId}/members/{userId}/mute` | `{ duration: HOUR \| DAY \| WEEK \| UNTIL_UNMUTED }` → `Member` | Moderators and up (SR1b). They read and react, can't post; told only that they were muted and until when (push, SH1e). `DELETE` unmutes. Errors: `spaces.not_moderator`, `spaces.cannot_moderate`, `spaces.not_member`. |
| SP26 | `DELETE /spaces/{spaceId}/members/{userId}` | → `{ removed: true }` | Admins (SR1c). They leave and may follow again; their posts stay; a paid period isn't refunded (pay them back from the private chat). Told by push. Errors: `spaces.not_admin`, `spaces.cannot_moderate`, `spaces.not_member`. |
| SP27 | `PUT /spaces/{spaceId}/bans/{userId}` · `DELETE …` | → `{ banned: true \| false }` | Admins. A ban removes them and stops follow, join, requests, invites, paying the space and the private chat with the owner (both ways). Their posts stay. Told by push without who did it (SS6e). Unban lets them follow or ask again. Errors: `spaces.not_admin`, `spaces.cannot_moderate`. |

`Member`: `Person` + `{ role?, joinedAt?, mutedUntil?, bannedAt? }`.

## Space chat

| Id | Route | Body → answer | Notes |
| --- | --- | --- | --- |
| SP28 | `GET /spaces/{spaceId}/posts?before=&after=&limit=` | → `{ posts: [Post], next }` | Newest first with `before` (or neither), oldest first with `after` (catching up from the newest you hold). `next` continues in the same direction. Default 50. Members read everything; **visitors of a public space** read the latest 20 only (SS1, read-only, `next` null); members-only and invite-only visitors and `ENDED` members get `spaces.members_only` / `spaces.membership_ended` (403). A banned person `spaces.banned`. |
| SP29 | `POST /spaces/{spaceId}/posts` | `{ clientPostId, text?, replyToPostId?, mediaIds: [] }` → `Post` | A queueable write (SH1d): made offline with a `clientPostId` (UUID from the phone), sent in order on reconnect; a repeat returns the first answer. `text` up to 4,000 characters; at most 10 `mediaIds` (completed, yours); text or media required. Members of a `LIVE` space (`GRACE` included). Errors: `spaces.not_member`, `spaces.membership_ended`, `spaces.posting_restricted` (403, admins only, SH1b), `spaces.muted` (403, `details.fields.mutedUntil`, SH1e), `spaces.banned`, `spaces.post_invalid` (400), `spaces.post_not_found` (the reply target), `spaces.media_not_found`, `spaces.too_many_posts` (429, `Retry-After`: 20 a minute, 500 a day). Every member gets `space.post_new`. |
| SP30 | `DELETE /spaces/{spaceId}/posts/{postId}` | → `Post` | Your own post, or any post for moderators and up (SH1c Remove Post). Leaves a tombstone: `removed { by: AUTHOR \| MODERATOR, at }`, text and media gone; replies still quote it as removed. The moderator is never named. Errors: `spaces.post_not_found`, `spaces.not_moderator`. |
| SP31 | `PUT /spaces/{spaceId}/posts/{postId}/reaction` | `{ emoji: "🔥" \| null }` → `{ postId, reactions, myReaction }` | One emoji per person per post (any one emoji, 1–16 UTF-8 bytes, as Moments); `null` clears. Queueable like posts. Members only (`GRACE` included; muted members may react). Errors: `spaces.reaction_invalid` (400), `spaces.post_not_found`, `spaces.not_member`. Everyone in the space gets `space.post_updated` with the counts. |
| SP32 | `POST /spaces/{spaceId}/read` | `{ postId }` → `{ unreadCount }` | Moves your read marker forward (never back). |
| SP33 | `POST /spaces/media` | `{ purpose: SPACE_PHOTO \| SPACE_COVER \| POST, kind: IMAGE \| VIDEO \| VOICE, contentType, sizeBytes, width?, height?, durationMs? }` → `{ mediaId, uploadUrl, uploadHeaders, expiresAt }` | Not encrypted (space content isn't end to end). PUT the bytes to `uploadUrl` with exactly `uploadHeaders` within 1 hour. Images up to 10 MiB, video 64 MiB, voice 16 MiB; photo and cover images only. Errors: `spaces.media_invalid` (400). |
| SP34 | `POST /spaces/media/{mediaId}/complete` | → `{ mediaId, sizeBytes }` | Checks the stored size. Then use the id in SP2, SP5 or SP29 (each file once). Errors: `spaces.media_not_found`, `spaces.media_invalid`. |

`Post`:
```json
{ "postId": "…", "spaceId": "…", "clientPostId": "…? (yours only)",
  "author": { "userId": "…", "displayName": "Amina", "handle": "amina.w", "avatarUrl": "…?", "role": "ADMIN" },
  "text": "…?", "media": [ { "mediaId": "…", "kind": "IMAGE", "contentType": "image/jpeg", "sizeBytes": 1,
  "width": 1080, "height": 1350, "durationMs": null, "url": "…" } ],
  "replyTo": { "postId": "…", "authorName": "Juma", "text": "first 120 characters…", "removed": false },
  "reactions": [ { "emoji": "🔥", "count": 48 } ], "myReaction": "🔥?",
  "createdAt": "…", "removed": { "by": "MODERATOR", "at": "…" } }
```
`author.role` is the author's role now (the badge in SH1); a person who left shows no role. Reactions are ordered by
count. `url`s live 1 hour. No read receipts and no typing in a space (a big room). `#` in a space chat is plain text:
money to a space goes through the private chat (SH1).

## The private chat with the owner (messaging, end-to-end encrypted)

- **M4** `POST /chats/direct` gains **`viaSpaceId`**: `{ "userId": "<owner>", "viaSpaceId": "…" }` opens (or makes)
  the one private chat between you and that space's owner **for that space**, separate from any ordinary chat between
  the two of you (so business never mixes with family, SH2d). Allowed when the space is `LIVE` and you may message
  its owner (`privateMessages` `ANYONE`: anyone who can see the space; `MEMBERS`: members only; never when banned).
  The owner may open one with a member of their space the same way (`userId` = the member). Otherwise
  `messaging.not_reachable` (as for a person you can't reach). Blocks still apply.
- The chat is a normal `DIRECT` chat (Signal sessions, payloads, attachments, calls): M1/M2/M4 answers carry
  **`viaSpaceId`** on it. Label it "Private · via {space}" on both sides; the owner's Chats gains the Spaces filter chip
  over these (SH2d). Get the space's name and photo from SP3 (cache them).
- Sending in it (M12) is refused with `messaging.not_reachable` once either side is banned from the space or the
  space is deleted or removed.
- **Money** to the space happens here (SH2b): W4 `to: { spaceId }` with `chatId` = this chat. The app posts the
  payment card into this chat as it does in any chat; the owner sees it as "Received into {space}". The membership
  receipt (SM2) is posted the same way by the payer's app once `space.membership_updated` confirms it.

## Space wallet (wallet module, reusing the phase 2 owner model)

Every space has its own wallet (owner type `SPACE`): its own Circle user and PIN; **only the owner signs**; admins see
it; members see only their own payments to it (SW1–SW2). No approvals and no daily cap for spaces (plan §7a): the
owner sends directly.

| Id | Route | Body → answer | Notes |
| --- | --- | --- | --- |
| W18 | `POST /wallet/spaces/{spaceId}` | → `{ walletId, spaceId, status, challengeId?, sdk }` | The space owner sets up the space wallet (SN4): run `challengeId` in the Circle SDK with `sdk` (the PIN screen names the space), then W13 `?walletId=` to add networks (SN5). Asking again while `SETTING_UP` gives a fresh challenge (Finish Wallet Setup, SN5b). When it turns `ACTIVE` the space goes `LIVE` (`space.updated`, and `wallet.ready` with `walletId`). Errors: `wallet.space_not_found` (404), `wallet.not_space_owner` (403), `wallet.not_signer` (403), `wallet.already_exists` (409), `wallet.account_restricted`, `wallet.support_account_has_no_wallet`, `wallet.custody_unavailable`. |
| W19 | `GET /wallet/spaces/{spaceId}` | → `{ spaceId, status: NONE \| SETTING_UP \| ACTIVE, wallet? }` | Owner and admins (`wallet` as W16's items). Others: `wallet.space_not_found`. |

- **W1, W3, W5, W6, W12, W13** take `?walletId=` for a space wallet: the owner and admins read (W1, W5, W6, W13 GET);
  only the owner signs (W3, W4, W12/W13 writes: `wallet.not_signer` for admins).
- **W16** lists space wallets too: `owner { type: "SPACE", spaceId, signerUserId }`, the ones you sign for first,
  then those you administer (`canSign: false`, SW1b).
- **W4** gains `to: { "spaceId": "…" }`: pay a space wallet (members of the space only: `wallet.space_not_member`,
  403, SH2e "Follow to Pay"; visitors and banned people included). With **`spacePaymentId`** (from SP13) it pays a
  membership: personal wallet only (`wallet.space_payment_personal_only`, 400), exactly the payment's `amount` and
  space (`wallet.space_payment_invalid`, 409: unknown, expired, not yours or another amount); non-members may pay
  then. **From** a space wallet: `walletId` = the space wallet, the owner signs, no `payoutRequestId`.
  New errors: `wallet.space_not_found`, `wallet.space_not_member`, `wallet.space_payment_invalid`,
  `wallet.space_payment_personal_only`.
- **Fees (phase 2F, deployed):** call **W17** `POST /wallet/transfers/quote` with W4's exact body (`to: { spaceId }`,
  `spacePaymentId` for a membership) while the money card is open: it answers the fee at the **space rate** (seed
  1 %, `rateBps: 100`, or the gas `minimum`), the total, and for the signer a `quoteId` held 15 minutes. Send that
  `quoteId` on W4; the fee is charged on top in the same signature, the space receives exactly `amount`. A payment
  *from* a space wallet is priced the same way (sender SPACE). Spaces have no daily cap (§7a). W17 runs the same
  space checks as W4 (`wallet.space_not_member`, `wallet.space_payment_invalid`, …).
- **`payment.received`** for a space wallet carries `ownerType: "SPACE"` and `spaceName` (group wallets `ownerType: "GROUP"`); its push names the space.
- **W5** and the events gain `fromSpaceId`, `toSpaceId`, `spacePaymentId`. Frames for a space wallet carry `walletId`:
  `transfer.updated` and `payment.received` go to the owner; `wallet.ready` to the owner and admins.
- **I14** (delete account) is refused with `wallet.group_funds_remaining` while a space wallet you sign for holds money
  (as group wallets).

## Reports

| Id | Route | Body → answer | Notes |
| --- | --- | --- | --- |
| SP35 | `POST /spaces/{spaceId}/reports` | `{ postId?, reason, detail? }` → `{ reportId }` | SR3. A post (`postId`) or the whole space (no `postId`). `reason`: `SPAM_SCAM`, `COUNTERFEIT`, `HARASSMENT`, `HATE_VIOLENCE`, `SEXUAL`, `OTHER`; `detail` up to 500 characters. Online only. Post reports reach the space's moderators and admins (`space.report_new`) **and** Circaso's queue; space reports only Circaso's. The reporter's name never reaches the space's team. "Also block" is users' block route, called by the app. One open report per person and target (a repeat answers the same `reportId`). Errors: `spaces.report_invalid` (400), `spaces.post_not_found`, `spaces.too_many_reports` (429: 20 a day). |
| SP36 | `GET /spaces/{spaceId}/reports?state=OPEN\|DONE&cursor=` | → `{ reports: [ReportGroup], next }` | SR4, moderators and up. One entry per reported post: open ones most reported first; done ones newest first, with who acted. Errors: `spaces.not_moderator`. |
| SP37 | `POST /spaces/{spaceId}/reports/{postId}/resolve` | `{ action: KEEP \| REMOVE_POST \| MUTE \| REMOVE_MEMBER, muteDuration? }` → `{ resolved: true }` | Moderators and up (`REMOVE_MEMBER`: admins). Acts (as SP30/SP25/SP26) and closes every open report on that post; each reporter gets `space.report_resolved` and a push. Errors: `spaces.not_moderator`, `spaces.not_admin`, `spaces.report_not_found` (404), `spaces.cannot_moderate`. |
| SPO1 | `GET /ops/spaces/reports?cursor=` (scope `ops`) | → `{ reports: [OpsReport], next }` | Circaso's queue, newest first, with the reporter (Circaso sees who reported rule-breaking). |
| SPO2 | `POST /ops/spaces/{spaceId}/remove` (scope `ops`) | `{ reason }` → `{ removed: true }` | Circaso removes a space (`REMOVED`, SS6d). Members get `space.updated`. |

`ReportGroup`: `{ post: Post, reason (the most given), count, firstAt, lastAt, state: OPEN | DONE, resolution?: {
action, by: Person, at } }`.

## Realtime (module `spaces`, replayable 7 days like the other modules)

| Type | To | `data` |
| --- | --- | --- |
| `space.post_new` | every member (not visitors) | `{ spaceId, spaceName, post: Post, notify }` (`notify`: whether this post was pushed to you; ignore it) (the author's own phone gets it too: match `clientPostId`) |
| `space.post_updated` | every member | `{ spaceId, postId, reactions, removed? }` (reactions changed, or the post was removed) |
| `space.updated` | every member (and the team of a `SETTING_UP` space) | `{ spaceId, version, state }`: read SP3 again (details, posting mode, price, `LIVE`, `DELETED`, `REMOVED`) |
| `space.membership_updated` | the person | `{ spaceId, spaceName, reason, until? }`: read SP3 again for your `Me` (`until`: the mute's end, the paid period's end or the grace's end, when the reason has one). `reason`: `JOINED` (paid, accepted, or invite accepted), `RENEWED`, `ROLE_CHANGED`, `MUTED`, `UNMUTED`, `REMOVED`, `BANNED`, `UNBANNED`, `REQUEST_DECLINED`, `RENEWAL_DUE`, `GRACE_STARTED`, `ENDED`, `LEFT` |
| `space.price_changed` | every member | `{ spaceId, price: Price, previous: Price }` |
| `space.invited` | the invitee | `{ inviteId, spaceId, spaceName, inviterId, inviterName }`: read SP20 for the card |
| `space.invite_removed` | the invitee | `{ inviteId, spaceId }` (cancelled by an admin, or the space went) |
| `space.invite_accepted` | the inviter | `{ spaceId, spaceName, userId, name }` (SI3: "Pasaka joined") |
| `space.request_new` | the owner and admins | `{ spaceId, spaceName, userId, name }` |
| `space.report_new` | the team (moderators and up) | `{ spaceId, postId, count }` |
| `space.report_resolved` | each reporter | `{ spaceId, reportId, action }` |

Phones offline get these from replay on reconnect (`resync` beyond 7 days: read SP7, SP20, SP28).

## Pushes (`kind`, wording by the gateway; spaces aren't end to end encrypted, so names are in the text)

| `kind` | When | Text |
| --- | --- | --- |
| `space.post` | a new post, to members whose `notifications` is `ALL` (or `MENTIONS` when it replies to them or is by the team) | "{space}" / public spaces: "{author}: {first 80 characters, or Photo/Video/Voice note}"; members-only and invite-only: "New post in {space}" |
| `space.invited` | SP18 | "{inviter} invited you to {space}" |
| `space.request` | SP14, to the owner and admins | "{name} asked to join {space}" |
| `space.joined` | membership confirmed, request accepted | "You're in {space}" / "Welcome to {space}" |
| `space.renewal` | 3 days before the end (7 for yearly), once per period | "Your {space} membership ends on {date}. Renew" |
| `space.grace` | the period ended | "Your {space} membership ended. You can still take part until {date}" |
| `space.ended` | grace over | "Your {space} membership has ended" |
| `space.price` | a price change | "{space}'s price changes to {amount} from your next period" |
| `space.role` | made admin or moderator | "You're now {an admin / a moderator} of {space}" |
| `space.moderation` | muted, removed, banned | "An admin muted you in {space} until {time}" / "You were removed from {space}" / "You can't take part in {space} any more" |
| `space.report` | a report you made was handled | "Thanks for your report. {space}'s moderators looked at it" |

Every push carries `kind` and `spaceId` (new optional field in the push data) so a tap opens the space (or Chats for
`space.invited`).

## Membership timing (server jobs)

`spaces.memberships` runs every 10 minutes: sends `RENEWAL_DUE` once per period at `renewFrom`; moves `ACTIVE` →
`GRACE` at `paidUntil` (`graceUntil` = +3 days); `GRACE` → `ENDED` at `graceUntil`. Nothing is ever charged
automatically. Invites expire 14 days after they're sent (`EXPIRED` in SP16, gone from SP20).

## Offline (offline-first.md)

Queueable: SP29 posts and SP31 reactions (with `clientPostId`; reactions last-write-wins per post). Everything else
(create, follow, join, pay, request, accept, invite, roles, mute, remove, ban, report, settings) is online-only and
disabled offline with the reason. Cache SP7, SP3 and the latest SP28 pages per space for read-only "as of".

## Errors (`spaces.*`)

`space_not_found` (404), `space_unavailable` (404, `fields.reason`, `fields.name`), `handle_invalid` (400),
`handle_taken` (409, `fields.suggestions`), `details_invalid` (400, `fields`), `price_invalid` (400), `team_invalid`
(400), `account_too_new` (403, `fields.availableAt`), `too_many_spaces` (429), `account_restricted` (403),
`not_owner` (403), `not_admin` (403), `not_moderator` (403), `cannot_moderate` (403), `not_member` (403),
`members_only` (403), `membership_ended` (403), `banned` (403), `payment_required` (403), `invite_required` (403),
`not_invite_only` (409), `not_paid_space` (409), `already_member` (409, `fields.renewFrom`), `wallet_not_ready` (409),
`wallet_not_empty` (409), `owner_cannot_leave` (409), `request_too_soon` (409, `fields.canAskAgainAt`),
`request_not_found` (404), `invite_not_found` (404), `invite_invalid` (400), `posting_restricted` (403), `muted`
(403, `fields.mutedUntil`), `post_invalid` (400), `post_not_found` (404), `too_many_posts` (429), `reaction_invalid`
(400), `media_invalid` (400), `media_not_found` (404), `report_invalid` (400), `report_not_found` (404),
`too_many_reports` (429).
