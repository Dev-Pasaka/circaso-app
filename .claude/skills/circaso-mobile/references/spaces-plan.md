# Spaces, Moments, group wallets, shops and fees: plan

Lives in the `circaso-mobile` skill (`references/spaces-plan.md`).

Status: **phase 0 done and approved (2026-11-03); phase 1 (Moments) built in the backend branch and both apps, not yet run end to end.** Details in `project-status.md`. Earlier: the fee spike is done (§7b); the phase 0a frames for phases 1 and 2
are in `assets/Circaso-Spaces.html` (built by `scripts/build_spaces_canvas.py`), waiting for Pasaka's review. Nothing is built in code yet. Decisions marked **(proposed)** need a yes
or a change; open questions are in §8.

**Decided by Pasaka (2026-11-03):** statuses are called **Moments**; group and space wallets use **option A** (owner signs,
admins approve in the app; multi-signer later); live is **audio and video together** in the first live release; a
personal business wallet is **not in v1** (the group or space is the business).

## 1. What we are adding

1. **A new tab, Spaces**, between Chats and Wallet: `Chats | Spaces | Wallet | You`.
2. **Statuses** at the top of the Spaces tab: WhatsApp-style 24-hour posts (text, photo, video, voice) seen only by
   people in each other's contacts.
3. **Spaces** below the statuses: communities and small shops (public, members only or invite only) with a space chat,
   private chat with the space, a shop catalog like WhatsApp Business, a live room members can join, and a wallet
   members pay into.
4. **Group and space wallets**: every **space must have** its own wallet; a **group may** have one (chosen when the
   group is created, or added later). Each is separate from anyone's personal wallet, with its own PIN (and optional
   biometrics) and multiple networks and assets, and it works everywhere personal money works (pay, receive, QR,
   history, cards in chat).
5. **Business identity** (later, to brainstorm): the group or space is the business. A personal "business wallet" for
   one person is parked until §8 Q6 is answered.

Why: personal money and business or community money stay apart. Groups will mostly be chamas and small businesses
and communities; spaces are their public face.

## 2. Naming (proposed)

- Tab: **Spaces** (decided by Pasaka).
- Statuses: **Moments** (decided 2026-11-03). Candidates considered:
  - **Moments**: plain, clear, warm. *(recommended)*
  - **Rings**: the coloured ring around a contact's photo is the brand's circle; distinctive, but needs learning.
  - **Updates**: WhatsApp's own word; safe but generic.
- Space wallet in copy: "Space wallet"; group: "Group wallet" (already used by chamas).

## 3. The Spaces tab layout (proposed)

Follows WhatsApp's current **Updates** tab, which has exactly this shape (Status row on top, Channels below), in
Circaso's design system:

```
Spaces                                   [search] [ + ]
─────────────────────────────────────────────────────
Moments
 (+)You   (●)Amina   (●)Baraka   (○)Mum   →  scrolls
─────────────────────────────────────────────────────
Your spaces                               See all
 [img] Nairobi Sneaker Drop   ● LIVE   1.2k
 [img] Kilimani Chama Hub             340
Discover spaces                           See all
 [img] Coffee Farmers Kenya   Follow
 ...
```

- `+` in the bar: New moment (text or camera/gallery) or New space.
- Search covers people's moments (by name) and spaces (by name, handle, topic).
- A space that is live shows a pulsing LIVE pill; live rooms you can join can also float as a strip at the top.
- Adaptive: on tablets the Spaces list is the left pane; a space or a moment viewer opens in the detail pane.

## 4. Moments (statuses)

### What the person can do (matches WhatsApp's current status design)
- Post: **text** on a coloured background (font and colour picker), **photo**, **video** (trim to 60 s), **voice** (up
  to 60 s), with caption. Several posts form one moment ring for 24 hours.
- View: tap a ring to open full screen; segmented progress bar at the top; tap right/left for next/previous, hold to
  pause, swipe down to close, swipe left to the next person. Unseen rings are coloured; seen rings are grey.
- React and reply: quick reaction (heart) and a reply field; a reply opens **the private chat** with the poster, quoting
  the moment (as WhatsApp does). No public comments.
- Your own moment: viewers list with time and reaction, delete a post, forward to a chat.
- Mute someone's moments (they move to a "Muted" section). Search by name.
- Privacy: **My contacts** (default) / **My contacts except…** / **Only share with…**.

### Who sees what (decision: Pasaka, "only people whose numbers are saved")
- You see someone's moment only if **they have you in their contacts and you have them in yours** (WhatsApp's rule).
- **The audience is chosen on the poster's phone**, from their Circaso contacts and privacy setting. The server never
  decides who someone's contacts are.

### Encryption (proposed: end to end, like chats)
- Each post's media is encrypted on the phone with a fresh key and uploaded through the encrypted-attachments path the
  backend already has (AT1–AT3). The post (key, caption, type) goes as a Signal **sender-key message to the audience**,
  reusing the libsignal group machinery we use for chats.
- The server stores ciphertext only, deletes it after 24 h, and keeps only who may fetch it, view receipts and expiry.
- Copy may honestly say "Moments are end-to-end encrypted".

### Backend (new `status` module)
- Post a moment (ciphertext reference, audience user ids, expiry); list moments for me (who posted, not what); mark
  seen (view receipts to the poster); delete; mute list is local to the phone.
- Realtime frame `status.posted` / `status.deleted` / `status.viewed`; push only for replies (those are chat messages).
- Retention job: hard-delete after 24 h, including the attachment.

### App
- `feature/status` (data / domain / presentation), Room tables for moments seen and downloaded media cache (24 h),
  composer (text, camera, gallery, voice), viewer, privacy settings under You → Privacy.
- **In-app camera** (approved 2026-11-03): **CameraX** on Android (latest stable at build time), and on iOS the
  built-in **AVFoundation** capture (no extra library).

## 5. Spaces (communities and shops)

Decided by Pasaka (2026-11-03): a space can be **public, members-only or invite-only**; it has a **chat** members talk
in, a **shop** (catalog, like WhatsApp Business), and **anyone in the space can send money to it**. A person can talk
to the space **publicly** (in the space chat) or **privately** (one-to-one in their inbox).

### What it is
- A community or a small business: name, @handle, photo, cover, description, topic, owner, admins, members.
- **Who can join** (owner's choice when creating, changeable later; decided 2026-11-03):
  - **Public**: for anyone who wants a public space, businesses and people selling products. Shows in search and
    Discover; anyone **follows to join** and unfollows to leave.
  - **Members only**: membership communities. Shows in search with its name, photo, description and **the price to
    join in USDC**; a person **pays the entry fee** (into the space wallet, the space fee rate on top) and becomes a
    member at once. Leaving doesn't refund. The owner picks **one-time, monthly or yearly** (decided 2026-11-03).
    Renewals are **never deducted automatically** (every payment is signed by the person): a few days before the end of
    a period the member gets a reminder with "Renew" (Pay filled in); after the period ends there is a short grace
    period (proposed 3 days) with a banner, then the person drops to "membership ended" and can't read the chat, shop
    or join lives until they pay again. Price changes apply from the next period.
  - **Invite only**: exclusive and private. Hidden from search; an admin **invites a person by picking a contact or
    typing their @username**; the invite arrives in the person's inbox as a card with Accept / Decline.
- Discover lists public and members-only spaces: search, topics, "popular near you" (by country), spaces your contacts
  follow (proposed).

### Inside a space
- **Chat**: the space's conversation, like a group chat; every member can read it. Admin setting **who can send**:
  *Everyone* (default for communities) or *Admins only* (announcement style). Messages support text, photos, video,
  voice, replies, reactions, and product cards from the shop.
- **Talk privately** (decided 2026-11-03): "Message privately" opens a **one-to-one, end-to-end encrypted chat with
  the space's owner**. It sits in the person's inbox and in the owner's inbox, marked **"Private · from Amina, via
  Sneaker Drop"**. Only the owner can read it; other members and admins never see it. (A shared business inbox for
  admins can come later.)
- **Shop** (see §5a), **Live** (see §6), **Wallet** (see §7), **Members**, **About**, share link / QR
  (`circaso://space/@handle`).
- **Send money to the space**: any member can pay the space wallet (contributions, tips, payments without buying a
  product). Non-members see the shop window but must join (or be let in) to buy or pay.

## 5a. The shop (catalog, like WhatsApp Business)

### For the space owner and admins
- Add **products or services**: name, description, up to 10 photos (or a short video), **price in USDC** (shown to
  buyers also in their own currency, "≈ KES 1,290"), optional link, category or collection.
- **Quantity (stock), optional**: leave it empty for unlimited (most services), or set a number; a switch decides if
  buyers **see the quantity** ("3 left") or not. At zero the item shows "Sold out".
- Hide or show an item, reorder, group into collections; share an item into any chat, the space chat or a Moment.
- **Orders**: a list of what was bought, by whom, for how much, with the payment; mark an order **Done** (handed over or
  delivered) or **Refunded** (the refund is a normal payment back from the space wallet).

### For buyers
- Browse the shop, open an item (photos, price, stock if shown), choose quantity, **Buy**: Pay opens filled in (price ×
  quantity, the fee on top as decided), signed with their PIN, paid **into the space wallet**.
- After paying: an **order card** in their private chat with the space ("Order #A12 · 2 × Air Max · paid"), and the
  seller gets the order with the buyer's name to arrange delivery.
- **Cart** (decided 2026-11-03): add several items (with quantities) to a cart per space and pay once; the order
  holds every line.

### How it works underneath
- `catalog_item` (space, kind product/service, name, description, media, `price_usdc_minor`, `stock` nullable,
  `show_stock`, hidden, collection, sort) and `order` (space, buyer, lines with price at purchase, total, fee,
  `transferId`, status `AWAITING_PAYMENT → PAID → DONE / REFUNDED / EXPIRED`).
- **No overselling**: Buy **reserves** the quantity for the payment's 15-minute signing window; it is taken when the
  payment confirms and released if the payment fails or expires.
- **The price comes from the server**, not the phone: W4 for an order takes `orderId`, and the server fills the amount
  from the order, so a tampered app can't pay less.
- Photos and videos through the existing media upload path; shop and orders are **not end to end encrypted** (the
  shop is public by nature; order details must reach the seller and support), stated in the shop's info.
- Fees: a purchase is a payment to a space, so the space rate applies (seed 1 %), quoted before signing.
- Later: delivery options, discount codes, digital goods, payment links outside the app, shop analytics.

## 5b. Questions about spaces and the shop

- ~~Q15~~ **Decided:** Public / Members only (paid entry in USDC) / Invite only (invited by contact or @username).
- ~~Q16~~ **Decided:** private messages go to the space owner only, end-to-end encrypted, marked "Private".
- ~~Q17~~ **Decided:** cart in v1.
- ~~Q18~~ **Decided:** no end-to-end encryption in any space chat for now (invite-only included); the app says so.
- ~~Q19~~ **Decided:** the owner sets one-time, monthly or yearly; renewals are paid manually by the member after a
  reminder, never deducted.

### Space chat is not end-to-end encrypted (decided 2026-11-03)
Anyone can join a public space, so encrypting it end to end would protect nothing and would break search, moderation
and large membership. Space posts are **encrypted in transit and at rest on the server, not end to end**, and the
space screen says so plainly ("Chats in spaces are not end-to-end encrypted"). This applies to every join type for now. Chats and moments stay end to end. This is the honest trust line.

### Safety (required for public content; App Store guideline 1.2 and Play's user-generated-content policy)
- Report a post, comment, live or space; block a person; admins remove posts and members, ban, and appoint
  moderators; a Circaso review queue for reports (lands in the admin site later; until then by email or ops API).
- Spam limits: creating spaces, posting and following are rate-limited; new accounts can't create spaces for N days (proposed).
- Age and content rules in the terms; adult content not allowed (proposed).

### Backend (new `spaces` module)
- Spaces CRUD, join types (public follow, paid entry, invites by user), handle uniqueness, search index (Postgres full-text first),
  topics, follow/unfollow, join requests, roles, space chat messages, reactions, catalog and orders, reports, bans, counts. Realtime frames for live state and new posts; push for lives starting
  (to followers who opt in) and replies to you.

### App
- `feature/spaces`: Spaces tab, discover/search, space screen (chat, shop, live, wallet, members, about), create/edit
  space, shop editor and orders, buy flow, admin tools, report/block sheets. Room cache for joined spaces, their latest
  chat and their shop (offline: read-only, "as of").

## 6. Live in a space (decided: audio and video together)

- **Host** starts a live in their space; **followers get a push** ("Nairobi Sneaker Drop is live").
- Roles: **host**, up to ~10 **speakers** (host invites, or listeners raise a hand and the host accepts), and
  **listeners** (any member; non-members can follow to join).
- In the room: speakers' faces in a grid, listener count, raise hand, reactions, a live comment stream, **Pay the space**
  button (contributions or buying during the live), host controls (mute, move to listener, remove, end).
- Media: Cloudflare Realtime (already used for calls): speakers publish, listeners only subscribe. Labelled "Live",
  never "end-to-end encrypted". Recording and replay: later (needs storage decision).
- **Video is in the first live release** (decided 2026-11-03): host and speakers can turn on their camera; same SFU,
  speakers publish audio and video, listeners subscribe. Very large audiences may need Cloudflare Stream (WHIP/WHEP);
  set listener caps and measure cost first.
- Backend: extend `calls` with a `LIVE` kind and roles, or a `live` submodule in `spaces` (decide when building).

## 7. Group and space wallets (the big model change)

### Today
A personal wallet per person (Circle user-controlled wallet; the person signs with a PIN). Chamas use the **treasurer
model**: group money sits in the treasurer's personal wallet.

### Proposed
- Wallets get an **owner**: `PERSON`, `GROUP` or `SPACE`. It has its own addresses on every supported network and its
  own assets.
- **Spaces: a wallet is required** (decided 2026-11-03; spaces are for business). Creating a space ends with the owner
  setting the space wallet's PIN; the space goes live only once the wallet is set up.
- **Groups: a wallet is optional** (decided 2026-11-03). Creating a group has a switch "Group wallet" (off by default,
  proposed); on, the owner sets its PIN at the end of creation. A group without a wallet is an ordinary encrypted group
  chat: no money actions, no target. The owner can add a wallet later from group info. A **target needs a wallet**, so
  adding a target to a group without one walks the owner through setting the wallet up first.
- **Biometrics per wallet** (decided 2026-11-03): after setting a group or space wallet's PIN, the owner is offered
  fingerprint / Face ID approval for that wallet, exactly as for the personal wallet (Circle's biometric PIN is per
  Circle user, and each group or space wallet is its own Circle user, so `WalletSigner.enableBiometrics(session)` runs
  with that wallet's session). Each wallet's biometrics can be turned on or off on its own (Security, and the wallet's
  settings); if Circle reports biometrics lost for one wallet, only that one falls back to its PIN.
- **Signing always names the wallet**: a person may hold several wallets (personal plus every group or space they
  own). Every approval screen (PIN, biometrics prompt, review) shows the wallet's name and picture ("Approve from
  Kilimani Chama"), and the server issues the challenge for that wallet's Circle user only.
- **Paying works the same**: pay a group or space from its chat or page, scan its QR, or send to its address; payments
  show as cards in the group chat and in the space; history and statements per wallet.
- **Wallet switcher**: wherever you pay *from* (Pay screen, Wallet tab), a person who administers group or space
  wallets can switch "Paying from: Personal ▾ / Kilimani Chama / Sneaker Drop". Personal stays the default; business
  money never mixes with it.
- **Chamas and groups become one thing: a Group** (decided 2026-11-03), with all the features of today's groups and
  chamas. A group with a wallet can have a **target** (goal), optional: add one and the group works like a chama
  (goal strip, contributions, payout requests and approvals); without one it is a group with a wallet. Creating a group
  is the same flow either way; the target can be added or removed later.
  - Contributions go to the group wallet; payouts are requested and approved by admins (the CH4–CH6 flow), then the
    owner signs from the group wallet. The goal strip, requests and group wallet screen stay; "held by Otieno"
    becomes "in the group wallet". The treasurer model is retired (dev is testnet, so no money to migrate).
  - The owner sets the group wallet's PIN when the group is created (if the wallet switch is on), or later from group
    info. Members of a group without a wallet see no money actions in it.

### Who holds the keys: the decision that shapes everything (§8 Q1)
Circle user-controlled wallets belong to one Circle user with one PIN. For a shared wallet there are three ways:

| Option | How | For | Against |
| --- | --- | --- | --- |
| **A. One signer** *(recommended for v1)* | The backend creates a Circle user for the group or space; the **owner** sets its PIN on their phone (a second PIN, separate from their personal one). Admins approve payouts in the app (as chamas do now); only the owner signs. | Works with the Circle SDK we already ship; same PIN screens; fast to build; server still never holds keys. | One person can move the money (approvals are app rules, not on-chain); changing owner means the old owner hands over the PIN or the wallet is emptied into a new one. |
| B. Multi-owner smart account | Circle Modular Wallets (smart contract accounts with several owners and a threshold, passkeys). | True on-chain "2 of 3 admins"; owners can be rotated; strongest trust story for chamas. | New Circle SDK and flows, passkeys, per-chain contract support; a few weeks more; needs Pasaka's approval as a library. |
| C. Server-held (developer-controlled) | Circaso's server signs. | Simplest UX. | **Breaks "the server never holds keys"**; custodial licensing; rejected. |

**Decided 2026-11-03: A now, designed so B can replace it later** (`WalletSigner` already hides the vendor). The app is
honest about it: "Only Amina (owner) can send from this wallet; admins approve first."

### Backend changes (`wallet` module)
- `wallet.owner_type` + `owner_id`; Circle user per owner; routes take an optional `walletId` / `owner` (W1–W5, W9–W12
  keep working for personal with no change).
- Pay **to** a group or space: `to: { groupId }` / `to: { spaceId }`. Who may pay a space: anyone who can see it; a
  group: its members.
- Permissions: view balance (members), propose payout (members), approve (admins), sign (owner).
- Frames and push carry `walletId`; statements per wallet.
- Limits per wallet type; later **KYB** (business verification) before large volumes or cash out, once partners exist.

### App changes
- `feature/wallet`: `WalletOwner` everywhere a wallet is read; the switcher; group/space wallet screens reuse E1/D4
  layouts; set-up flow reuses Circle's PIN screens with the group's name on them ("Set a PIN for Kilimani Chama").
- `feature/chama` folds into groups: the goal becomes an optional target on any group; point everything at the group
  wallet; drop treasurer selection. Copy says "group" and "target" ("chama" can stay as a friendly word in Asha and help).
- `feature/spaces`: Space wallet tab, Pay the space, product posts with a price that open Pay prefilled.

## 7a. Group withdrawal limits (added 2026-11-03, Pasaka)

- **Groups** have a maximum amount that can leave the group wallet **to anywhere outside the group** per day.
  Payments between the group wallet and its own members' wallets follow the same rule unless the rules say otherwise
  (decided 2026-11-03: **payouts to members count too**, because "pay a member, member sends it on" would otherwise
  bypass the cap).
- **Default: 10,000 USDC a day per group** (decided 2026-11-03), adjustable per group in the database by an admin
  (later from the admin site).
- **Spaces have no daily withdrawal limit** (they are businesses' own wallets). The rule engine still supports one, so a
  limit can be added later without code.
- Personal wallets keep the platform limits already on the server (placeholders today: 500 USDC a payment, 1,000 a day).
- **Rules live in the database**, seeded with defaults, not in code: per owner type (PERSON, GROUP, SPACE), optional
  per wallet override, per asset (valued in USD), with `per_payment_max`, `daily_max` and a **rolling 24-hour window**
  (decided 2026-11-03). Edited by SQL or an ops API now, from the admin
  site later.
- **Enforced by the server at W4 (prepare payment)**, before any challenge is made. This holds even though the person
  signs: the Circle SDK can only sign challenges our backend creates with our Circle API key, so a payment the server
  refuses can't happen. The check counts payments that are confirmed, submitted or still awaiting signature, so two
  quick payments can't slip past the cap together.
- Error `wallet.limit_exceeded` with `details` (limit, used, left, resets at); the app shows "This group can send
  KES X more today; it resets at 14:30" and offers a smaller amount.
- Together with option A (owner signs, admins approve), the daily cap limits what a single owner could ever take out
  in one day.
- Raising a group's limit: a request to Circaso (ops) for now; later tied to KYB.

## 7b. Transaction fees (added 2026-11-03, Pasaka)

### The rule
- A percentage per payment, **0 % to 2 %** (any rule can be set to zero, e.g. for a promotion), applied to **at most
  the first $10,000** of a payment:
  `fee = rate × min(amount in USD, 10,000)`. So at 2 % the most anyone pays is **$200**; anything above $10,000 is free.
- **Cheapest: payments between Circaso wallets** (any owner: person, group, space), starting at 0.5 %.
  **Most expensive: payments to addresses outside Circaso**, up to 2 %.
- Money arriving from outside Circaso is not charged (we can't charge an incoming chain payment anyway).
- **Fully dynamic, in the database**: a `fee_rule` table, seeded with defaults:
  - match on: payment kind (`INTERNAL`, `EXTERNAL`), sender owner type, receiver owner type, chain, asset;
  - values: `rate_bps` (0–200: a database check enforces 0–2 %, and the bounds themselves are configurable),
    `cap_base_usd` (10,000), optional `min_fee_usd` (on top of the gas minimum below), `effective_from` / `effective_to`, `version`;
  - most specific rule wins; every change makes a new version (history kept, nothing overwritten).
  - Edited with SQL or an ops API now, from the admin site later.
- **The fee is quoted before signing and locked**: W4 returns `fee { amount, rate, ruleId, ruleVersion }` and the
  totals; the quote holds for the challenge's 15 minutes, so a rule change never alters a payment someone has already seen.

### Who pays (decided 2026-11-03: the sender)
- **The sender pays on top**: "Send $200 · fee $1.00 · total $201.00"; the receiver gets exactly what was
  sent, which matters for chamas and for buying things at a stated price.
- "Send all" quotes the largest amount whose total fits the balance.

### How the fee travels in a payment the person signs
The person signs one challenge; it must pay the receiver **and** the fee address together, or neither (never charge a
fee for a payment that failed, never pay without the fee).

- **EVM chains (Base, Polygon, Ethereum, …), proposed:** a small **Circaso payment contract** deployed per chain.
  The server prepares one **contract-execution** challenge instead of a plain transfer:
  `pay(token, to, amount, feeTo, fee, transferId)`; the contract moves `amount` to the receiver and `fee` to Circaso's
  fee address in one transaction and emits an event with `transferId` for reconciliation. Allowing the contract to move
  USDC is either a one-time approval at wallet set-up or a per-payment signed permit (decide in the spike).
  `feeTo` and the allowed tokens are fixed in the contract's configuration, not taken from the request, so a tampered
  payload can't redirect fees or payments.
- **If our wallets are smart-contract accounts** (the backend's recent "SCA chain" work suggests they are), a
  **batched call** (two transfers in one user operation) may do the same without our own contract; Circle Gas
  Station keeps paying gas.
- **Solana:** one transaction can carry two transfer instructions; check what Circle's user-controlled API allows.
- **Spike done (2026-11-03), full report in `references/fee-spike.md`.** Per chain:

  | Network | One user-signed challenge can hold | Choice |
  | --- | --- | --- |
  | Base, Polygon, Ethereum | `executeBatch((address,uint256,bytes)[])` on the **sender's own SCA** through `POST /v1/w3s/user/transactions/contractExecution`: two USDC `transfer` calls (receiver, fee wallet), all or nothing. Accepted by Circle on all three testnets (not yet signed on a phone). No USDC approval, no contract of ours. | **Batch** |
  | Solana | Contract execution is unsupported and the transfer API takes one destination. Only a raw two-instruction transaction through `sign/transaction` works, and then we broadcast it ourselves, get no Gas Station, and the blockhash expires in about 60–90 s (a durable nonce is needed). | **Wait**: Solana payments carry a 0 % rule until a second spike (only if Solana fees are needed at launch) |

  So **no payment contract for now** (nothing to audit). The server builds the batch, and the fee address comes from
  server configuration, never from the request. The `pay()` contract above stays an option if we ever need an on-chain
  `transferId` event. Ethereum works but its SCA gas makes the minimum fee about $15–40 under the 25 % rule (Circle itself
  advises EOAs on Ethereum mainnet): decide before mainnet whether to keep ETH, cap it or give it a high `min_fee_usd`.

### Gas sponsorship and the 25 % rule (decided 2026-11-03)
- **Circaso sponsors all network gas** (Circle's **Gas Station** pays it for our smart-contract wallets and bills
  Circaso monthly). People never see gas; they see one Circaso transaction fee.
- **Rule: the gas of a payment must not exceed 25 % of the fee we collect on it.** When the percentage fee is too
  small to cover that (small payments, or expensive networks such as Ethereum), the gas cost is **added to the
  person's transaction fee** as a minimum:
  `fee = max(rate × min(amount, 10,000), 4 × estimated gas)`, so gas is always ≤ 25 %. Example: $1 on Base pays about
  $0.04 instead of $0.005; $1,000 at 0.5 % pays $5 (gas ~$0.01, no change).
- **Gas is estimated at quote time (W4)** for the exact call that will run (the fee-splitting call costs more than a plain
  transfer), with a safety margin for price moves; the quote is locked for the 15-minute signing window as before.
- **0 % rules (promotions) are allowed**: no minimum is added and the gas is booked as promotion cost in the reports.
- The fee ledger records quoted fee, estimated gas and **actual gas** per payment; reports show the gas-to-fee ratio per
  chain and rule, and alert when actual gas went above 25 % (e.g. a price spike after the quote).
- The rule's numbers (25 %, safety margin) live in the database with the fee rules, editable like them.

### Fee address and accounting
- One **Circaso fee wallet per chain**, a **Circle developer-controlled wallet** (decided 2026-11-03) in Circaso's own
  Circle wallet set, used for nothing else. It only receives fees; moving money out of it (to the company's bank or
  exchange) is a company operation. Its entity secret is kept in the server's secret store, never in the app or the
  repo. This is Circaso's own money, so it doesn't break "the server never holds users' keys". A multi-signature
  treasury (e.g. a Safe) can replace it once fee volume justifies it.
- A **fee ledger** row per payment (quoted, collected, on-chain reference), reconciled against the contract events;
  reports per day, chain, kind and rule for the admin site.
- Fees pay for the gas Circaso sponsors (today `fee` is always zero and Circaso pays gas); the W4 contract changes from
  "`fee` is always zero" to the quoted fee, so the app and backend guide change together.
- Legal: charging per transfer can bring money-transmission rules; confirm with counsel before mainnet.

## 8. Decisions needed from Pasaka

1. ~~Wallet keys~~ **Decided:** option A (owner signs, admins approve in-app) for v1, B later.
2. ~~Name for statuses~~ **Decided:** Moments.
3. ~~Live~~ **Decided:** audio and video together.
4. ~~Space posting~~ **Decided:** a space chat every member can read; admins choose who can send (everyone or admins only); private one-to-one chat also possible.
5. ~~Space encryption~~ **Decided:** space chats are not end-to-end encrypted for now, said plainly; private chats with the owner are.
6. ~~Personal business wallet~~ **Decided:** not in v1; only through groups and spaces.
7. ~~Who can pay a space~~ **Decided:** anyone who is in the space (members).
8. ~~Selling~~ **Decided:** a shop with a catalog of products and services, USDC prices, optional quantity (shown or hidden), orders (§5a).
15–18. Space join-type names, where private messages go, cart, invite-only encryption: see §5b.
9. ~~Chamas~~ **Decided:** chamas and groups merge into Groups; every group has a wallet; a target is optional.
10. ~~CameraX~~ **Decided:** CameraX on Android, AVFoundation on iOS.
11. ~~Who pays fees~~ **Decided:** the sender pays on top; the receiver gets the full amount.
12. ~~Starting fee rates~~ **Decided:** seed internal 0.5 %, to or from groups and spaces 1 %, external 2 %; all
    editable, including to 0 %. The point is the revenue mechanism; the numbers will change.
13. ~~Group daily limit~~ **Decided:** 10,000 USDC a day by default, adjustable per group in the database; rolling 24 h; payouts to members count.
19. ~~Members-only entry fee~~ **Decided:** one-time, monthly or yearly, set by the owner; renewals paid manually (§5).
14. ~~Fee wallet~~ **Decided:** a Circle developer-controlled company wallet per chain, for now (created once in
    Circle's developer console: a wallet set and its entity secret, kept in the server's secret store).
20. ~~Gas~~ **Decided:** Circaso sponsors gas (Circle Gas Station); gas ≤ 25 % of our fee, enforced by adding the gas to
    the person's fee as a minimum; 0 % promotions allowed with gas as promotion cost.

## 9. Phases (each ends with a device check, design review and a commit)

| Phase | Scope | Depends on |
| --- | --- | --- |
| 0. Design | Frames for the Spaces tab, Moments composer and viewer, space page, create space, live room, group wallet set-up and switcher, in Circaso's system (the canvas has none of these). Reviewed with Pasaka before code. | Answers to §8 |
| 1. Tab + Moments | Spaces tab shell (Moments row + empty spaces section), Moments end to end (backend `status` + app). | Phase 0 |
| 2. Groups with wallets | Wallet owner model, optional group wallet at creation or later (owner PIN, biometrics per wallet), wallet names on every approval, wallet switcher, pay to a group, chamas merged into groups with an optional target. | — |
| 2F. Fees and limits | Spike on what one signed challenge can hold per chain; `fee_rule` and limit-rule tables with seeds; quote and lock at W4; payment contract (EVM) or batch; fee wallet and ledger; group daily limits; app shows fee and limits before signing. | Phase 2, Q11–Q14 |
| 3. Spaces core | Create (public / members only / invite only), discover, search, follow and join requests, paid entry for members-only spaces (one-time, monthly or yearly, renewal reminders and grace period), invites by contact or @username, space chat, private chat with the owner, roles, moderation and reports, space wallet (reuses phase 2), send money to the space. | Phase 2 |
| 3S. Shop | Catalog (products and services, photos, USDC price, optional stock shown or hidden), cart, buy with reservation, orders and refunds, order cards, share items. | Phase 3, 2F |
| 4. Live | Audio and video live rooms with speakers and listeners, pushes, pay during live. | Phase 3 |
| 5. Business identity | Shared business inbox for spaces, KYB with partners, delivery and discounts, personal business wallet if ever wanted. | Partners |

Rough size: phase 1 ≈ 1 week, 2 ≈ 1–1.5 weeks (A) or +2–3 weeks (B), 3 ≈ 1.5–2 weeks, 3S ≈ 1–1.5 weeks, 4 ≈ 2 weeks (audio
and video), across backend and both apps.

## 10. Risks

- **Fee collection on user-signed payments**: settled by the spike for EVM (batch); Solana waits with a 0 % rule rather than
  charging in two separate steps. Still unproven: signing a batch on a phone, and what Circle's SDK shows the person for a
  contract-execution challenge (if it shows anything, it must not look like a strange contract call).
- **Contract risk:** a payment contract holds approvals over people's USDC; it must be minimal, audited and not upgradeable by one key.

- **One-signer group wallets** concentrate trust in the owner (mitigated by in-app approvals, clear copy, and option B later).
- **Moderation load** of public spaces; App Store review will check report/block exist before release.
- **Regulation:** businesses taking payments may need KYB; keep limits low on testnet and plan with partners.
- **Live scale:** Cloudflare Realtime costs per participant-minute; set listener caps until measured.
- **Several PINs** (personal plus each group or space wallet) can confuse; every PIN and biometric screen names whose
  wallet it is, and biometrics per wallet keep PIN entry rare.
