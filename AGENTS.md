# Codex Instructions

## Development Principles

- Optimize for the 32-unit pilot. Favor simple, reliable, testable, and reversible solutions.
- Treat `docs/REQUIREMENTS.md` and `docs/DECISIONS.md` as approved product constraints. Treat architecture and schema proposals as suggestions, not binding decisions.
- Record every engineering decision (new or changed) in `docs/ENGINEERING_DECISIONS.md` in the same change that introduces it. Never rewrite past entries; supersede them.
- Keep business logic in the backend. Mobile clients and the KMP shared module present data and collect input only. Any client-side logic beyond presentation must be justified and added to the exceptions register in `ENGINEERING_DECISIONS.md` (ED-17).
- Implement one roadmap milestone at a time, delivering a runnable demo, applicable migrations, tests, and concise PR notes.
- Prefer one service, one relational database, private object storage, and simple background jobs. Avoid message brokers, unnecessary abstractions, and speculative scaling.
- Use secure backend tooling for initial administration. A financial administration portal is planned before onboarding additional HOAs, but is not required for the initial beta.
- Do not initiate questionnaires. Make reasonable implementation decisions independently. Escalate only material user-facing, financial, legal, security, or costly irreversible decisions.

## Security and Data Integrity

- Enforce server-side HOA and property authorization for every API, import, background job, and document URL.
- Preserve financial auditability and idempotency. Never silently modify or delete financial history; use auditable corrections.
- Ensure CSV imports are atomic. Failed imports must not leave partially applied changes, and retries must not create duplicates.
- Ensure payment webhook processing is idempotent. Replayed or duplicate events must never create duplicate credits.
- Never store full payment card numbers, CVVs, or raw bank credentials.
- Never log secrets, credentials, or personal financial data.

## Testing and Verification

- Include automated tests covering:
  - Cross-tenant access denial.
  - Property ownership revocation.
  - CSV import retry and rollback.
  - Payment webhook replay and duplicate prevention.
  - Financial balance invariants.
- Run relevant tests before considering work complete.
- Before completing a roadmap milestone, verify the applicable security, financial integrity, and regression tests.
- Report tests passed, failed, and not run. Never claim tests passed without executing them.

## Git Workflow and Commit Conventions

- Work only on non-protected branches. Never commit directly to `master`, or `develop`.
- Branch flow: `feature/*` from `develop` → PR → `develop` → production release PR → `master` → CI/CD → production.
- GitHub branch protection is unavailable on the current plan; local hooks in `.githooks/` block direct commits and pushes to `develop` and `master`. Never bypass them with `--no-verify`.
- Verify the current branch before committing. If on a protected branch, ask the user how to proceed.
- Never commit changes without explicit user authorization.
- When changes are ready, provide a proposed commit title and description for review.
- Commit titles must begin with a type enclosed in square brackets, followed by a concise, imperative description.
- Keep commit titles concise and descriptive, preferably under 72 characters. Prioritize clarity over strict length limits.
- Supported commit types:
  - `[feature]` — New functionality.
  - `[update]` — Enhancements to existing functionality.
  - `[bugfix]` — Bug fixes.
  - `[quality]` — Refactoring, cleanup, or test improvements.
  - `[security]` — Security-related changes.
  - `[docs]` — Documentation changes.
  - `[chore]` — Maintenance, dependencies, or tooling.
- Commit descriptions must be concise bullet points (not prose paragraphs) that summarize the changes and explain their purpose, emphasizing why rather than merely what. Pull request descriptions use the same bullet style.
- Do not include unrelated changes in a commit.
- Never push changes to a remote repository without explicit authorization.
- Pull requests:
  - After the user approves a commit, push the feature branch and create the pull request into `develop` with `gh pr create`; that approval covers pushing the feature branch.
  - The user reviews and merges all pull requests. Never merge, approve, or close a pull request.
  - Create production release pull requests (`develop` → `master`) only when the user asks.
