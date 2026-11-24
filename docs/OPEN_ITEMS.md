---
title: Seebrook open items
tags: [seebrook, status]
updated: 2026-11-24
---

# Open items

A short checklist of what is still open. Details live in `ENGINEERING_DECISIONS.md` (Pending decisions) and `OPEN_QUESTIONS.md`. Updated with every change that opens or closes an item.

## Owner actions

- [ ] **Legal entity**: decide who owns the accounts, domain, Firebase project and Stripe relationship. *Before any paid or production account.*
- [ ] **Firebase file with Google**: download `GoogleService-Info.plist` again (Google sign-in was enabled after the first download) into `mobile-ios/Config/Firebase/Debug/`.
- [ ] **Xcode 26.2**: then upgrade Firebase to 13 (ED-50).
- [ ] **Stripe test-mode account** with Connect and a test connected account (free): needed for checkout testing on the phone.
- [ ] **Stripe live setup**: platform account, Channel Harbor's connected account, webhook with the 8 events, restricted key. *Before the first real payment.*
- [ ] **Card surcharge**: confirm it is allowed and set the amount. *Before real card payments; until then the card fee is $0 or cards are off.*
- [ ] **Hosting provider**: also settles object storage, alert delivery and backups. *Before TestFlight and the pilot.*
- [ ] **Brevo account** and `seebrook.cc` domain authentication. *Before the first real invitation.*
- [ ] **Apple Developer Program** ($99/yr). *At TestFlight; until then testing runs on the owner's iPhone with a free Personal Team.*
- [ ] **Google Play account** ($25). *When the Android app starts.*
- [ ] **Pilot roster and PDFs**, provided securely. *Synthetic data until then.*
- [ ] **First real `convert-register` dry run** on the Channel Harbor workbook. *Before importing real data.*

## Decisions pending

- [ ] **Account deletion**: proposed to remove identity and contact details and keep the ledger and audit history. Needs owner approval and a legal check. *Before App Store submission.*
- [ ] **Privacy policy and terms**: who writes and publishes them (Seebrook or the HOA). *Before App Store submission.*
- [ ] **Android owners in the pilot**: start with iOS owners only, or wait for Android. *Before M7.*
- [ ] **Root MFA**: enforce TOTP for the root administrator. *Before a second HOA joins.*

## Engineering

- [ ] **M5 iOS app**: ~~shared module and Xcode setup~~ → ~~invitations~~ → ~~Firebase sign-in~~ (Sign in with Apple after the paid program) → properties (in review) → documents → balance and history → checkout.
- [ ] **Malformed `Authorization` header** returns 500 instead of 401 (seen in local testing; small server fix).
- [ ] **Backend for the app**: push device registration, owner profile edits, account deletion (after the decision).
- [ ] **M6**: recurring payment plans, cancelling a departing owner's recurring charges at transfer, alert delivery.
- [ ] **M7 pilot**: production setup, backup restore drill, security review, owner onboarding.
- [ ] **Later**: financial admin web portal (before a second HOA), Android app.

## In review

- Properties in the iOS app: Home and Account tabs, property switching (ED-51).

## Recently closed

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
