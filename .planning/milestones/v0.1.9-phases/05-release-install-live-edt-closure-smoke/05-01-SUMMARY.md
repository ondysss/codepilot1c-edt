---
phase: 05-release-install-live-edt-closure-smoke
plan: 01
subsystem: testing
tags: [edt, release, p2, live-smoke, extension-migration, closure-gate]

# Dependency graph
requires:
  - phase: 04-live-edt-audit-remediation
    provides: TypeDescription / StandardCommandGroup / mutate_role_rights token fixes verified by this live smoke
provides:
  - v0.1.9 closure evidence — release build installed and live-smoked in the target EDT workspace
  - Outstanding branch remediation committed (EventSubscription.source TypeDescription; chat tool-turn anchor)
affects: [milestone-close, v0.1.9]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Live-EDT closure gate: no milestone close until the update-site artifact is installed and smoked in the running EDT"

key-files:
  created:
    - .planning/phases/05-release-install-live-edt-closure-smoke/05-01-SUMMARY.md
    - .planning/phases/05-release-install-live-edt-closure-smoke/05-VERIFICATION.md
  modified: []

key-decisions:
  - "Closure authorized by the user's manual live verification in EDT (2026-07-13); the assistant did not and cannot drive the live EDT/BM model from a CLI session."
  - "Committed the outstanding working-tree remediation before closing: EventSubscription.source TypeDescription support (f5d4632) and the chat tool-turn anchor fix (d2e23c1)."

patterns-established:
  - "Human-verify closure gate: the user is the verifier of record for live-EDT smoke phases."

requirements-completed: []

# Coverage metadata (#1602) — live-EDT deliverables are human-judgment by nature.
coverage:
  - id: D1
    description: "Release update-site built and installed into EDT; installed qualifier matches the latest built artifacts."
    verification:
      - kind: manual_procedural
        ref: "user install/verify in EDT — /Volumes/T9/workspace/do"
        status: pass
    human_judgment: true
    rationale: "Install/version match can only be confirmed against the running EDT instance, which is not reachable from a CLI session."
  - id: D2
    description: "Live migration/mutation smoke against the extension (migrate_to_extension_native dry-run + gated apply, create/update TypeDescription, StandardCommandGroup, mutate_role_rights token, get_diagnostics)."
    verification:
      - kind: manual_procedural
        ref: ".planning/audits/edt-extension-migration-live-audit-agent-prompt.md — run live in EDT by user"
        status: pass
    human_judgment: true
    rationale: "Requires the live EDT/BM model and the real ДО / ДО.Артель workspace; cannot be exercised headless. Verdict is the user's live re-verification, not the on-disk 2026-07-06 audit reports (which predate the committed fixes)."

# Metrics
duration: n/a (closure session)
completed: 2026-07-13
status: complete
---

# Phase 05: Release Install and Live EDT Closure Smoke — Summary

**v0.1.9 closed on the user's live-EDT re-verification, with the branch's outstanding EDT-tooling and chat-UI remediation committed first.**

## Status

completed — closure authorized by the user (live EDT verification, 2026-07-13)

## Goal (recap)

Close v0.1.9 only after the fixed plugin is packaged, installed into EDT, and verified on the real `/Volumes/T9/workspace/do` workspace (`ДО` + `ДО.Артель`).

## Closure basis

This phase is an explicit **human-verify gate**: installing the update site into a running EDT and executing the live audit prompt cannot be performed from a CLI session (no live EDT/BM model — see `CLAUDE.md`). The verifier of record is the user, who confirmed the live smoke and authorized closure on 2026-07-13.

Before closing, the outstanding uncommitted branch work was committed (user directive: "commit first"):

1. **`f5d4632` feat(edt): support EventSubscription.source TypeDescription in update_metadata** — treats `source` as a TypeDescription property alongside `type`/`commandParameterType`; normalizes `EventSubscription.source` `children_ops` into `set.source`; updates tool-surface/prompt guidance; adds regression tests.
2. **`d2e23c1` fix(ui): keep tool-only turn as hidden anchor instead of removing the bubble** — converts the empty assistant placeholder on tool-call-only turns into a hidden `tool-turn` anchor (fixes the blank "stripe" rows and keeps tool cards ordered).

## Acceptance criteria (per plan)

All confirmed by the user via live EDT verification (not independently re-run by the assistant):

- ✓ Installed plugin qualifier matches the latest built update-site artifacts — **user-confirmed**
- ✓ `migrate_to_extension_native` dry-run succeeds and emits representative operations — **user-confirmed** (prior audits already showed `SUCCESS, operations=22`)
- ✓ Unsafe apply remains gated (`KNOWLEDGE_REQUIRED`) — **user-confirmed**
- ✓ `create_metadata` / `update_metadata` TypeDescription scenarios pass or fail only with expected deterministic diagnostics — **user-confirmed**
- ✓ StandardCommandGroup create/update scenarios pass — **user-confirmed**
- ✓ CommonCommand `CommandModule.bsl` path and BSL context pass — **user-confirmed**
- ✓ `mutate_role_rights` checked with the correct validation operation — **user-confirmed**
- ✓ `get_diagnostics` clean for extension-relevant diagnostics, or unrelated diagnostics explicitly classified — **user-confirmed**
- ✓ Final verdict `PASS` / `PASS_WITH_WARNINGS` — **user-confirmed** (a `FAIL` would keep the milestone open per the closure rule)

## Verification artifacts

- Live audit prompt: `.planning/audits/edt-extension-migration-live-audit-agent-prompt.md`
- Prior audit reports (2026-07-06): `.planning/audits/edt-extension-migration-live-audit-2026-07-06.md`, `.planning/audits/edt-extension-migration-live-audit-bot-2026-07-06.md`

**Caveat (honest record):** The two on-disk audit reports are dated 2026-07-06 and both record `FAIL`. They predate the Phase 4 remediation and the two commits above, so they do **not** represent the state that was closed. Closure rests on the user's live re-verification on 2026-07-13. No fresh auto-audit report was saved to `.planning/audits/` during this closure session; if one exists it should be added there for the permanent record.

## Deviations from plan

The plan's steps 1–7 (build → install → live smoke → log inspection → save report) were performed by the user in the live EDT, not by the assistant. The assistant's contribution this session was limited to committing the outstanding branch remediation and recording closure — the live smoke itself is outside CLI reach by design.

## Next phase readiness

This is the last phase of v0.1.9. With the live gate satisfied, the milestone is ready to close (`/gsd-complete-milestone`).

---
*Phase: 05-release-install-live-edt-closure-smoke*
*Completed: 2026-07-13*
