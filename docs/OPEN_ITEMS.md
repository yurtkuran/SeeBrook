---
title: Seebrook open items
tags: [seebrook, status]
updated: 2026-11-25
---

# Open items

A short checklist of what is still open. Details live in `ENGINEERING_DECISIONS.md` (Pending decisions) and `OPEN_QUESTIONS.md`. Updated with every change that opens or closes an item.

## Owner actions

- [ ] **Legal entity**: decide who owns the accounts, domain, Firebase project and Stripe relationship. *Before any paid or production account.*
- [ ] **Firebase file with Google**: download `GoogleService-Info.plist` again (Google sign-in was enabled after the first download) into `mobile-ios/Config/Firebase/Debug/`.
- [ ] **Xcode 26.2**: then upgrade Firebase to 13 (ED-50).
- [ ] **Stripe live setup**: platform account, Channel Harbor's connected account (capabilities `card_payments`, `us_bank_account_ach_payments` and `transfers` only), webhook with the 8 events, restricted key. *Before the first real payment.*
- [ ] **Card surcharge**: confirm it is allowed and set the amount. *Before real card payments; until then the card fee is $0 or cards are off.*
- [ ] **Hosting provider**: also settles object storage, backups, and an outside uptime check on `/health/ready` (alerts are emailed already, ED-61). *Before TestFlight and the pilot.*
- [ ] **Apple Developer Program** ($99/yr). *At TestFlight; until then testing runs on the owner's iPhone with a free Personal Team.*
- [ ] **Google Play account** ($25). *When the Android app starts.*
- [ ] **Pilot roster and PDFs**, provided securely. *Synthetic data until then.*
- [ ] **Channel Harbor community photo**: a clean photo the HOA may use (the one in the Stitch design is a social media screenshot). *Before the pilot.*
- [ ] **First real `convert-register` dry run** on the Channel Harbor workbook. *Before importing real data.*

## Decisions pending

- [ ] **Account deletion**: proposed to remove identity and contact details and keep the ledger and audit history. Needs owner approval and a legal check. *Before App Store submission.*
- [ ] **Privacy policy and terms**: who writes and publishes them (Seebrook or the HOA); include any payment authorization wording for the app (ED-54) and the recurring payment terms (ED-59; the app shows a plain authorization sentence for now, ED-60). *Before App Store submission.*
- [ ] **Android owners in the pilot**: start with iOS owners only, or wait for Android. *Before M7.*
- [ ] **Root MFA**: enforce TOTP for the root administrator. *Before a second HOA joins.*

## Engineering

- [ ] **Revisit the app's screen design** (Documents, Account) with the owner; the first versions follow Stitch loosely. Payments and Home now follow the owner's "Payments - Mobile" and "Home - Mobile" designs (ED-54, ED-56).
- [ ] **Backend for the app**: push device registration, owner profile edits, account deletion (after the decision).
- [ ] **M6**: ~~recurring plans, saved methods, charge run, cancellation at transfer (server)~~ → ~~app screens for saved methods and autopay~~ → ~~operator alerts by email~~. Complete.
- [ ] **M7 pilot**: production setup (including a production Brevo key and `SEEBROOK_EMAIL_*` settings, the Channel Harbor logo and photo, `set-hoa-logo`, `set-hoa-photo`, and its recurring schedule, `recurring-schedule`), backup restore drill, security review, owner onboarding.
- [ ] **Later**: Sign in with Apple (after the paid Apple Developer Program), financial admin web portal (before a second HOA), Android app.

## In review

- Brevo verified and the no-reply decision (docs only, ED-63).

## Recently closed

- 2026-11-26: Brevo verified: `email.seebrook.cc` authenticated and a test email reached the inbox; owner emails stay no-reply (ED-63).
- 2026-11-26: Test email command merged (ED-62, PR #40).
- 2026-11-26: Brevo account created by the owner.
- 2026-11-26: DbGate updated to 7.3.6 (PR #39).
- 2026-11-26: Operator alerts merged (ED-61, PR #38); M6 complete.
- 2026-11-25: Autopay in the iOS app merged (ED-60, PR #37).
- 2026-11-25: Recurring payments on the server merged (ED-59, PR #36).
- 2026-11-25: Checkout part 2, Stripe payment sheet and Stripe test mode merged (ED-58, PR #35); the M5 iOS app is complete.
- 2026-11-25: Channel Harbor's dues schedule decided: monthly on the 1st; owners may choose monthly, quarterly or annual plans.
- 2026-11-25: Stripe sandbox set up by the owner (test keys, test connected account limited to card, bank and transfers, Stripe CLI).
- 2026-11-25: Home restyled with each HOA's community photo merged (ED-56, ED-57, PR #34).
- 2026-11-25: Malformed `Authorization` header answers 401 instead of 500 (PR #33).
- 2026-11-25: iOS checkout part 1, the new Payments design and HOA logos merged (ED-54, ED-55, PR #32).
- 2026-11-24: Balance and history in the iOS app merged (ED-53, PR #31).
- 2026-11-24: Documents in the iOS app merged (ED-52, PR #30).
- 2026-11-24: Properties in the iOS app merged (ED-51, PR #29).
- 2026-11-24: Firebase sign-in and the new sign-in screen merged (ED-50, PR #28).
- 2026-11-24: Development Firebase project created (`seebrook-67b03`).
- 2026-11-23: Accepting invitations in the iOS app merged (ED-49, PR #27).
- 2026-11-23: iOS project setup merged (ED-48, PR #26).
- 2026-11-23: iOS details decided: iOS 17, `cc.seebrook.app`, development-token sign-in in debug builds (ED-48).
- 2026-11-23: Open items checklist added (PR #25).
- 2026-11-23: Payout report merged (ED-47, PR #24).
- 2026-11-23: Apple Developer Program deferred to TestFlight; on-device testing uses a free Personal Team.
- 2026-11-19: Payment reconciliation and outage handling (ED-46, PR #23).
- 2026-11-19: Payment processor chosen: Stripe with Connect (ED-43).
