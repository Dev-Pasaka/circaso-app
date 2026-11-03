# Moments API (backend `status` module): contract for Spaces phase 1

Written 2026-11-03, before either side was built, so the backend module and both apps build against the same thing.
When the backend's `docs/mobile-integration.md` gains its Moments section, that copy wins (rule 1 of the skill) and
this file is updated to match.

## Model

- A **moment** is one post (text, photo, video or voice) that lives **24 hours** from `postedAt`. Several moments
  from one person form their ring.
- **End to end encrypted**, reusing libsignal's group machinery (WhatsApp's status design):
  - Each poster has one **moments sender key** (a libsignal `SenderKeyDistributionMessage` under a fixed
    `distributionId` per poster, stored on the phone). The post body is encrypted **once** with `GroupCipher`.
  - Audience members who don't have the current distribution yet get a **key copy**: the distribution message
    encrypted pairwise (their Signal session, the same sessions chats use) and sent alongside the post.
  - When someone **leaves** the audience (privacy change, unblocked contact removed), the poster rotates: a new
    distribution, and copies go to everyone in the new audience with the next post.
  - Media is encrypted on the phone with a fresh AES-256-GCM key (same as chat attachments, AT1–AT3); the media key,
    digest, real type, caption, text background and a tiny blurred thumbnail travel **inside** the encrypted body.
- **Who sees what:** the **poster's phone** chooses the audience (its Circaso contacts and the privacy setting; the
  server never learns anyone's address book). The **viewer's phone** shows only moments from people in its own
  contacts, which makes the rule "both have each other saved". The server only checks that the two can reach each
  other (`users.api.ReachPolicy`: nobody blocked anybody).
- The server stores ciphertext, the audience, view receipts and expiry; nothing readable.

## Encrypted body (inside `ciphertext`; JSON, the app's own format)

```json
{ "v": 1, "kind": "TEXT|IMAGE|VIDEO|VOICE",
  "text": "…", "background": "INK|METAL|PAPER|GRAPHITE", "style": "BOLD|REGULAR",
  "caption": "…", "media": { "mediaId": "…", "key": "b64", "digest": "b64", "mime": "image/jpeg",
  "sizeBytes": 123, "durationMs": 14000, "width": 1080, "height": 1920 }, "thumb": "b64 jpeg ≤ 2 KB" }
```

## Endpoints (`/v1`, scope `app`, `Idempotency-Key` on every non-GET)

| Id | Route | Body → answer | Notes |
| --- | --- | --- | --- |
| ST1 | `POST /moments/media` | `{sizeBytes, kind: image\|video\|voice}` → `{mediaId, uploadUrl, uploadHeaders: {Content-Type}, expiresAt}` | Ciphertext size, 1 B to 32 MiB. PUT the ciphertext to `uploadUrl` with exactly those headers. |
| ST2 | `POST /moments/media/{mediaId}/complete` | → `{mediaId, sizeBytes}` | Checks the object's size. |
| ST3 | `POST /moments` | `{clientMomentId, ciphertext, audience: [userId], keyCopies: [{userId, ciphertext}], mediaIds: [mediaId]}` → `{momentId, postedAt, expiresAt}` | `clientMomentId` (UUID) makes retries safe: a repeat returns the first answer. Audience 1–1,024 people, never the poster; people who can't reach the poster are dropped silently (a block is never revealed). At most 1 media; ciphertext ≤ 64 KiB. `keyCopies` only for audience members. |
| ST4 | `GET /moments?after=&limit=` | → `{moments: [Moment], next}` | Every live moment the caller may see, plus the caller's own; oldest first within a poster. |
| ST5 | `POST /moments/{momentId}/view` | `{reacted?: Boolean}` → `{viewed: true}` | Audience only. Repeatable: the first call records the view, later ones change `reacted`. Sends `status.viewed` to the poster. |
| ST6 | `GET /moments/{momentId}/views` | → `{views: [{userId, viewedAt, reacted}]}` | Poster only. |
| ST7 | `DELETE /moments/{momentId}` | → `{deleted: true}` | Poster only. Removes the media too; sends `status.deleted` to the audience. |
| ST8 | `GET /moments/media/{mediaId}` | → `{downloadUrl, expiresAt, sizeBytes}` | 15-minute link for the poster or anyone in the audience of a live moment carrying it. |

`Moment`: `{momentId, posterId, ciphertext, keyCopy: String?, mediaIds, postedAt, expiresAt, mine, viewed, reacted,
viewCount}`. `keyCopy` is the caller's own copy, if one was sent with this moment. `viewCount` only on `mine`.

Errors (`status.*`): `audience_invalid` (400), `moment_too_large` (400), `media_invalid` (400: size or kind),
`media_not_found` (404: also when not allowed, so nothing leaks), `moment_not_found` (404: missing, expired, deleted,
or not visible), `not_poster` (403), `too_many_moments` (429: more than 50 in 24 h, `Retry-After`).

## Realtime (module `status`, replayable like messaging)

- `status.posted` `{momentId, posterId}` to the audience. The phone then fetches ST4.
- `status.deleted` `{momentId, posterId}` to the audience.
- `status.viewed` `{momentId, viewerId, viewedAt, reacted}` to the poster.
- No pushes. A reply to a moment is an ordinary M12 chat message quoting it.

## Retention

A sweep every 10 minutes hard-deletes moments past `expiresAt`, their views and their media; media prepared but
not attached within 1 hour is deleted too. Deleting an account removes its moments at once.
