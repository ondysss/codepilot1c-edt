# Roadmap — CodePilot1C OSS

## Milestones

- ✅ **v0.1.9 — EDT Extension Native Migration Tooling** — Phases 1–5 (shipped 2026-07-13) — full detail in [milestones/v0.1.9-ROADMAP.md](milestones/v0.1.9-ROADMAP.md)
- ✅ **v0.1.10 — Managed Form Event Handlers** — Phases 6–9 (shipped 2026-07-14) — full detail in [milestones/v0.1.10-ROADMAP.md](milestones/v0.1.10-ROADMAP.md)
- 📋 **Next milestone** — not yet defined (run `/gsd-new-milestone`)

## Phases

<details>
<summary>✅ v0.1.9 EDT Extension Native Migration Tooling (Phases 1–5) — SHIPPED 2026-07-13</summary>

- [x] Phase 1: Research EDT API Contracts and Lock Failure Reproductions (1/1 plan)
- [x] Phase 2: Low-Level EDT Tooling Fixes (1/1 plan)
- [x] Phase 3: Native Extension Migration Planner (1/1 plan)
- [x] Phase 4: Live EDT Audit Remediation (1/1 plan)
- [x] Phase 5: Release Install and Live EDT Closure Smoke (1/1 plan) — completed 2026-07-13 (live-verified in EDT by user)

Full phase details, outcomes, and verification: [milestones/v0.1.9-ROADMAP.md](milestones/v0.1.9-ROADMAP.md).
Requirements traceability: [milestones/v0.1.9-REQUIREMENTS.md](milestones/v0.1.9-REQUIREMENTS.md).

</details>

<details>
<summary>✅ v0.1.10 Managed Form Event Handlers (Phases 6–9) — SHIPPED 2026-07-14</summary>

- [x] Phase 6: Event-Handler API Spine, Operation Plumbing & Inspect Surfacing (3/3 plans) — completed 2026-07-13
- [x] Phase 7: BSL Handler Stub Generation (Base Configuration) (3/3 plans) — completed 2026-07-13
- [x] Phase 8: Extension (Расширения) Form Support (2/2 plans) — completed 2026-07-14
- [x] Phase 9: Qwen Priming, Regression Tests & Live-EDT Smoke Closure (2/2 plans) — completed 2026-07-14 (live-EDT smoke verified in EDT by user; base + extension)

Delivered: the agent safely wires managed-form event handlers — setting the event on the EMF form model AND
generating the matching BSL handler stub, atomically — on both base-configuration and 1C-extension (расширения)
forms, via the existing `mutate_form_model` tool under the `MUTATE_FORM_MODEL` validation-token flow.

Full phase details, outcomes, and verification: [milestones/v0.1.10-ROADMAP.md](milestones/v0.1.10-ROADMAP.md).
Requirements traceability: [milestones/v0.1.10-REQUIREMENTS.md](milestones/v0.1.10-REQUIREMENTS.md).
Audit: [milestones/v0.1.10-MILESTONE-AUDIT.md](milestones/v0.1.10-MILESTONE-AUDIT.md).

</details>

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1–5 | v0.1.9 | 5/5 | Complete | 2026-07-13 |
| 6. Event-Handler API Spine, Operation Plumbing & Inspect Surfacing | v0.1.10 | 3/3 | Complete | 2026-07-13 |
| 7. BSL Handler Stub Generation (Base Config) | v0.1.10 | 3/3 | Complete | 2026-07-13 |
| 8. Extension (Расширения) Form Support | v0.1.10 | 2/2 | Complete | 2026-07-14 |
| 9. Qwen Priming, Regression Tests & Live-EDT Smoke Closure | v0.1.10 | 2/2 | Complete | 2026-07-14 |

_Per-phase timelines and outcomes are preserved in the milestone archives under `milestones/`. No active milestone — run `/gsd-new-milestone` to start the next._
