# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v0.1.9 — EDT Extension Native Migration Tooling

**Shipped:** 2026-07-13
**Phases:** 5 | **Plans:** 5 | **Sessions:** not tracked precisely

### What Was Built
- Low-level EDT metadata mutation primitives: TypeDescription fields (`type`, `commandParameterType`, `EventSubscription.source`), CommonCommand command modules, StandardCommandGroup aliases, Bot adoption diagnostics, extension role-right diagnostics.
- Validation surface exposing effective extension-prefixed names/FQNs and explicit unsupported/available-kind responses before mutation.
- `migrate_to_extension_native` dry-run-first planner/tool with gated apply and no unsafe source deletion.
- Regression tests across the reported defect classes; a live-EDT smoke as the milestone closure gate.

### What Worked
- **Audit-driven remediation loop:** live EDT audit (FAIL) → Phase 4 turned each blocker into a fix + regression test → re-verification. The defect classes (EDTEXT-01…08) mapped cleanly to phases.
- **One TypeDescription abstraction, reused everywhere:** generalizing the TypeDescription property set (`isTypeDescriptionPropertyName`) let `EventSubscription.source` support drop in with a minimal, low-risk diff.
- **Explicit human-verify closure gate:** making "install + live smoke in real EDT" its own phase prevented shipping on green-local-build alone.

### What Was Inefficient
- **Two remediation commits reached milestone close unbuilt in-session** (`f5d4632`, `d2e23c1`): they added unit tests but were committed without a compile/test run in the closing session. A build/test gate immediately before milestone close would catch this.
- **Planning artifacts drifted out of git:** `.planning/` is blanket-gitignored and was only force-added through Phase 03; Phases 04–05 (plans/summaries/context) were untracked until milestone close and had to be reconstructed into git during closeout.
- **Milestone git history isn't cleanly scoped:** milestone work interleaved with unrelated branch work (`feature/tool-surface-refactoring`) and late planning commits, making automated LOC/commit-range stats unreliable.

### Patterns Established
- **Live-EDT closure gate as an explicit phase** — no milestone close without runtime proof from the installed plugin.
- **Dry-run-first migration tooling** with gated apply behind the validation-token flow; destructive deletes out of scope until a verified clone exists.

### Key Lessons
1. **Live EDT/BM behavior can't be verified headless** — phases that need a running EDT must be planned as human-verify from the start; the user is verifier of record for closure smokes.
2. **Force-add planning artifacts on every commit** (or add a hook) so `.planning/` never drifts out of git between milestone closeouts.
3. **Gate milestone close on a fresh build/test**, not just on prior local BUILD SUCCESS — remediation can land uncommitted/unbuilt on a long-lived feature branch.
4. **Abstract shared primitives once** (TypeDescription) — the payoff compounds as new fields (`source`) reuse the same resolver/validator path.

### Cost Observations
- Model mix: not tracked precisely this milestone (closeout session on Opus; executor model configured as Sonnet).
- Notable: closeout was mostly deterministic CLI + doc edits; the expensive/irreducible work (live EDT smoke) is inherently human-in-the-loop.

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v0.1.9 | n/a | 5 | Introduced explicit live-EDT closure gate; audit→remediation→re-verify loop |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v0.1.9 | regression tests across EDTEXT-01…08 | not measured | migration planner + TypeDescription generalization (no new runtime deps) |

### Top Lessons (Verified Across Milestones)

1. Live EDT verification is a human gate — design for it, don't assume automation. *(first recorded v0.1.9)*
2. Keep `.planning/` consistently tracked in git across milestones. *(first recorded v0.1.9)*
