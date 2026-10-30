# Circaso screen index

Generated from `assets/Circaso-Screens.html` (107 frames; 104 have a card below, the rest are website/logo boards). Each line: screen id · title — source file (`#card id`). The offline states (G3, G3b, G4) live in `assets/Circaso-Offline-States.html`; the script searches both files. Pull one screen's markup with `python3 scripts/extract_screen.py <card id>`.

**Naming:** older frames and some text say "CoinX"; the product is **Circaso**. Use Circaso in the app.

**Superseded:** A3 Sign in, A4 Phone, A5 Verify, A7 PIN, A8 Face ID are replaced by the ID pages. Build A1, A2, A9, A10 from page A and everything sign-in from page ID.

## L · Logo (2 screens) `#p-l`

The brand mark and its history. Option 1, "Circle and centre", is the chosen Circaso mark and is already applied across the app screens, the website and the emails. L1 is kept as a record of the earlier CoinX exploration.


## A · First launch (10 screens) `#p-a`

The original first-launch sequence. A3 to A8 are superseded by the Identity flow (page ID): sign-in, phone, code, PIN and Face ID now live there with the agreed rules. A1 (launch), A2 (welcome), A9 (contacts) and A10 (assistant intro) still stand.

- A1 · Launch screen  — `Main.dc.html` (`#c-Main`)
- A2 · Welcome  — `A2-Welcome.dc.html` (`#c-A2-Welcome`)
- A3 · Sign inSuperseded  — `A3-Sign-in.dc.html` (`#c-A3-Sign-in`)
- A4 · Your phone numberSuperseded  — `A4-Phone.dc.html` (`#c-A4-Phone`)
- A5 · Verify codeSuperseded  — `A5-Verify.dc.html` (`#c-A5-Verify`)
- A6 · Your profile  — `A6-Profile.dc.html` (`#c-A6-Profile`)
- A7 · Create your PINSuperseded  — `A7-PIN.dc.html` (`#c-A7-PIN`)
- A8 · Face IDSuperseded  — `A8-Face-ID.dc.html` (`#c-A8-Face-ID`)
- A9 · Find your people  — `A9-Contacts.dc.html` (`#c-A9-Contacts`)
- A10 · Meet CoinX  — `A10-Meet-CoinX.dc.html` (`#c-A10-Meet-CoinX`)

## ID · Identity and sign-in (54 screens) `#p-i`

Every screen and state for sign-up, sign-in on a new phone, recovery, account security and account status. Built from the Identity & Sign-in Flow doc (18 Oct 2026). Screen IDs (ID-xx) match that doc. Rules these screens follow Sign-in is Google or Apple only. No passwords anywhere. One phone number per account; a number already in use is blocked, never merged. One active phone per account; signing in on a new phone signs the old one out. An account is usable only once its phone is verified. The payment PIN is mandatory. Face ID / fingerprint is optional and the PIN takes over when it fails. Recovery needs the 12-word key plus one other factor (phone or the original Google/Apple account). After recovery, sending is paused for 48 hours. The recovery key can be written down, saved as a file, and imported from a file.

- ID-01 · Launch  — `ID01-Launch.dc.html` (`#c-ID01-Launch`)
- ID-01 · Launch, offline  — `ID01b-Launch-offline.dc.html` (`#c-ID01b-Launch-offline`)
- ID-02 · Welcome, iPhone  — `ID02-Welcome.dc.html` (`#c-ID02-Welcome`)
- ID-02 · Welcome, Android, loading  — `ID02b-Welcome-Android.dc.html` (`#c-ID02b-Welcome-Android`)
- ID-02 · Welcome, error  — `ID02c-Welcome-error.dc.html` (`#c-ID02c-Welcome-error`)
- ID-02 · Welcome, signed out  — `ID02d-Welcome-signed-out.dc.html` (`#c-ID02d-Welcome-signed-out`)
- ID-03 · Signing you in  — `ID03-Signing-in.dc.html` (`#c-ID03-Signing-in`)
- ID-04 · Your number, Android one tap  — `ID04a-Phone-one-tap.dc.html` (`#c-ID04a-Phone-one-tap`)
- ID-04 · Your number, Android number picker  — `ID04b-Phone-number-hint.dc.html` (`#c-ID04b-Phone-number-hint`)
- ID-04 · Your number, iPhone  — `ID04c-Phone-iPhone.dc.html` (`#c-ID04c-Phone-iPhone`)
- ID-04 · Finish setting up  — `ID04d-Phone-finish.dc.html` (`#c-ID04d-Phone-finish`)
- ID-05 · Carrier check didn't work  — `ID05-Carrier-fallback.dc.html` (`#c-ID05-Carrier-fallback`)
- ID-06 · Enter the code, verifying  — `ID06-Enter-code.dc.html` (`#c-ID06-Enter-code`)
- ID-06 · Wrong code  — `ID06b-Wrong-code.dc.html` (`#c-ID06b-Wrong-code`)
- ID-06 · Too many tries  — `ID06c-Code-locked.dc.html` (`#c-ID06c-Code-locked`)
- ID-07 · Number already registered  — `ID07-Number-taken.dc.html` (`#c-ID07-Number-taken`)
- ID-08 · Your recovery key  — `ID08-Recovery-intro.dc.html` (`#c-ID08-Recovery-intro`)
- ID-09 · Recovery key  — `ID09-Key-reveal.dc.html` (`#c-ID09-Key-reveal`)
- ID-09 · Save the key as a file  — `ID09b-Key-save-file.dc.html` (`#c-ID09b-Key-save-file`)
- ID-10 · Confirm the key  — `ID10-Confirm-key.dc.html` (`#c-ID10-Confirm-key`)
- ID-10 · Confirm, wrong word  — `ID10b-Confirm-wrong.dc.html` (`#c-ID10b-Confirm-wrong`)
- ID-11 · Create your payment PIN  — `ID11-Protect.dc.html` (`#c-ID11-Protect`)
- ID-11 · Confirm your payment PIN  — `ID11d-Confirm-PIN.dc.html` (`#c-ID11d-Confirm-PIN`)
- ID-11 · PINs didn't match  — `ID11b-Protect-declined.dc.html` (`#c-ID11b-Protect-declined`)
- ID-11 · Face ID, optional  — `ID11c-Payment-PIN.dc.html` (`#c-ID11c-Payment-PIN`)
- ID-11 · Fingerprint, optional, Android  — `ID11e-Fingerprint.dc.html` (`#c-ID11e-Fingerprint`)
- ID-12 · You're in  — `ID12-Handoff.dc.html` (`#c-ID12-Handoff`)
- ID-13 · New phone  — `ID13-New-phone.dc.html` (`#c-ID13-New-phone`)
- B4 · Wrong number on a new phone  — `ID13b-Wrong-number.dc.html` (`#c-ID13b-Wrong-number`)
- ID-14 · Restoring your chats  — `ID14-Restoring.dc.html` (`#c-ID14-Restoring`)
- ID-15 · Signed out on this phone  — `ID15-Signed-out-here.dc.html` (`#c-ID15-Signed-out-here`)
- ID-16 · What do you still have?  — `ID16-What-you-have.dc.html` (`#c-ID16-What-you-have`)
- ID-17 · Enter your recovery key  — `ID17-Enter-key.dc.html` (`#c-ID17-Enter-key`)
- ID-17 · Recovery key imported from a file  — `ID17c-Key-imported.dc.html` (`#c-ID17c-Key-imported`)
- ID-17 · Import failed  — `ID17d-Import-failed.dc.html` (`#c-ID17d-Import-failed`)
- ID-17 · Wrong recovery key  — `ID17b-Key-wrong.dc.html` (`#c-ID17b-Key-wrong`)
- ID-18 · Choose your new sign-in  — `ID18-New-sign-in.dc.html` (`#c-ID18-New-sign-in`)
- ID-19 · Confirm recovery  — `ID19-Confirm-recovery.dc.html` (`#c-ID19-Confirm-recovery`)
- ID-20 · Your new recovery key  — `ID20-New-key.dc.html` (`#c-ID20-New-key`)
- ID-22 · Lost both  — `ID22-Lost-both.dc.html` (`#c-ID22-Lost-both`)
- ID-23 · Hold banner on Home  — `ID23-Home-hold.dc.html` (`#c-ID23-Home-hold`)
- ID-23 · Send during the hold  — `ID23b-Send-paused.dc.html` (`#c-ID23b-Send-paused`)
- ID-24 · Account and security, Face ID off  — `ID24-Account-security.dc.html` (`#c-ID24-Account-security`)
- ID-24 · Change your payment PIN  — `ID24b-Set-PIN.dc.html` (`#c-ID24b-Set-PIN`)
- ID-24 · Account and security, Face ID on  — `ID24c-PIN-on.dc.html` (`#c-ID24c-PIN-on`)
- ID-25 · Security activity  — `ID25-Security-activity.dc.html` (`#c-ID25-Security-activity`)
- ID-26 · Sign out  — `ID26-Sign-out.dc.html` (`#c-ID26-Sign-out`)
- ID-27 · Remove this phone  — `ID27-Remove-phone.dc.html` (`#c-ID27-Remove-phone`)
- ID-28 · Delete account  — `ID28-Delete-account.dc.html` (`#c-ID28-Delete-account`)
- ID-28 · Delete blocked, money in wallet  — `ID28b-Delete-has-money.dc.html` (`#c-ID28b-Delete-has-money`)
- ID-21 · Suspended  — `ID21-Suspended.dc.html` (`#c-ID21-Suspended`)
- ID-29 · Blocked  — `ID29-Blocked.dc.html` (`#c-ID29-Blocked`)
- ID-30 · Force update  — `ID30-Force-update.dc.html` (`#c-ID30-Force-update`)
- Error · CoinX is having trouble  — `ID31-Backend-down.dc.html` (`#c-ID31-Backend-down`)

## C · Chats and money (8 screens) `#p-c`

The heart of the product: chats, and money sent as a message (#500 in the composer becomes a money chip), the authorise step, the money moment and transaction detail.

- C1 · Chats  — `C1-Chats.dc.html` (`#c-C1-Chats`)
- C2 · Chat thread  — `C2-Chat-thread.dc.html` (`#c-C2-Chat-thread`)
- C3 · #amount shortcut  — `C3-Money-shortcut.dc.html` (`#c-C3-Money-shortcut`)
- C4 · Send sheet  — `C4-Send-sheet.dc.html` (`#c-C4-Send-sheet`)
- C5 · Authorise  — `C5-Authorise.dc.html` (`#c-C5-Authorise`)
- C6 · Money moment  — `C6-Money-moment.dc.html` (`#c-C6-Money-moment`)
- C7 · Transaction detail  — `C7-Transaction.dc.html` (`#c-C7-Transaction`)
- C8 · New chat  — `C8-New-chat.dc.html` (`#c-C8-New-chat`)

## D · Groups and chamas (7 screens) `#p-d`

Groups and chamas: creating a group, the group thread, contributing to a goal, the shared group wallet, paying out with approval, joining by link, and business groups.

- D1 · New group  — `D1-New-group.dc.html` (`#c-D1-New-group`)
- D2 · Group thread  — `D2-Group-thread.dc.html` (`#c-D2-Group-thread`)
- D3 · Contribute  — `D3-Contribute.dc.html` (`#c-D3-Contribute`)
- D4 · Group wallet  — `D4-Group-wallet.dc.html` (`#c-D4-Group-wallet`)
- D5 · Pay from the group  — `D5-Pay-from-group.dc.html` (`#c-D5-Pay-from-group`)
- D6 · Join a group  — `D6-Join-group.dc.html` (`#c-D6-Join-group`)
- D7 · Business group  — `D7-Business-group.dc.html` (`#c-D7-Business-group`)

## E · Wallet (7 screens) `#p-e`

The wallet: balance, adding money, identity verification (only needed for fiat on/off-ramp), receiving, sending to an external wallet, cash-out (coming soon, Binance/Coinbase meanwhile) and activity.

- E1 · Wallet  — `E1-Wallet.dc.html` (`#c-E1-Wallet`)
- E2 · Add money  — `E2-Add-money.dc.html` (`#c-E2-Add-money`)
- E3 · Verify identity  — `E3-Verify-identity.dc.html` (`#c-E3-Verify-identity`)
- E4 · Receive  — `E4-Receive.dc.html` (`#c-E4-Receive`)
- E5 · Send to a wallet  — `E5-External-send.dc.html` (`#c-E5-External-send`)
- E6 · Cash out (coming)  — `E6-Cash-out.dc.html` (`#c-E6-Cash-out`)
- E7 · Activity  — `E7-Activity.dc.html` (`#c-E7-Activity`)

## F · Assistant and settings (4 screens) `#p-f`

The CoinX/Circaso assistant, the You tab, security and privacy settings, and notifications.

- F1 · Ask CoinX  — `F1-Ask-CoinX.dc.html` (`#c-F1-Ask-CoinX`)
- F2 · You  — `F2-You.dc.html` (`#c-F2-You`)
- F3 · Security and privacy  — `F3-Security.dc.html` (`#c-F3-Security`)
- F4 · Notifications  — `F4-Notifications.dc.html` (`#c-F4-Notifications`)

## S · Support (6 screens) `#p-s`

Customer support as a normal chat, with free in-app audio calls to a real person, plus a way in for people who can't sign in.

- SP-01 · Help  — `SP01-Help.dc.html` (`#c-SP01-Help`)
- SP-02 · Support chat  — `SP02-Support-chat.dc.html` (`#c-SP02-Support-chat`)
- SP-03 · Calling support, in the queue  — `SP03-Calling-support.dc.html` (`#c-SP03-Calling-support`)
- SP-04 · On a call with support  — `SP04-On-call.dc.html` (`#c-SP04-On-call`)
- SP-05 · Conversation ended  — `SP05-Chat-ended.dc.html` (`#c-SP05-Chat-ended`)
- SP-06 · Help without signing in  — `SP06-Help-signed-out.dc.html` (`#c-SP06-Help-signed-out`)

## G · System states (2 screens in the main canvas, +3 in the offline file) `#p-g`

System states: offline and a failed send.

- G1 · Offline  — `G1-Offline.dc.html` (`#c-G1-Offline`)
- G2 · Send failed  — `G2-Send-failed.dc.html` (`#c-G2-Send-failed`)
- G3 · Chats, sends waiting  — `G3b-Chats-waiting.dc.html` (card id `G3b-Chats-waiting`) — **from `assets/Circaso-Offline-States.html`**
- G3 · Waiting to send  — `G3-Waiting-to-send.dc.html` (card id `G3-Waiting-to-send`) — offline file
- G4 · Back online, caught up  — `G4-Back-online.dc.html` (card id `G4-Back-online`) — offline file

## H · Calls and live (6 screens) `#p-h`

Calls: incoming, 1:1 voice with money mid-call, video, chama group calls with live contributions, contributing during a call, and business live selling (after the pilot).

- H1 · Incoming call  — `H1-Incoming-call.dc.html` (`#c-H1-Incoming-call`)
- H2 · Voice call, money mid-call  — `H2-Voice-call.dc.html` (`#c-H2-Voice-call`)
- H3 · Video call  — `H3-Video-call.dc.html` (`#c-H3-Video-call`)
- H4 · Chama group call, live goal  — `H4-Group-call.dc.html` (`#c-H4-Group-call`)
- H5 · Contribute during the call  — `H5-Contribute-on-call.dc.html` (`#c-H5-Contribute-on-call`)
- H6 · Business live selling (after pilot)  — `H6-Business-live.dc.html` (`#c-H6-Business-live`)

## W · Website (1 screen) `#p-w`

The pre-launch waitlist website. W1 is the desktop page (also live in the coinx-web project); W2 renders the same page at phone width.
