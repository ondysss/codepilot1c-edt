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

## Milestone: v0.1.10 — Managed Form Event Handlers

**Shipped:** 2026-07-14
**Phases:** 4 (6–9) | **Plans:** 10 | **Tasks:** 21 | **Sessions:** 1 autonomous run

### What Was Built
- Event-handler API spine (Phase 6, pre-run): add/set/remove_event_handler ops through `mutate_form_model`, runtime event resolution, inspect surfacing.
- BSL stub generation (Phase 7): pure `BslHandlerStubGenerator` (directive from `Event.isServerCallWithContextNotAllowed()`/`Environments`, verbatim widest-`ParamSet` RU/EN signature), injectable `ModuleFileWriter` seam + `BslHandlerStubWriter`, integrated into `updateFormModel` with compensating-`executeWrite` rollback for both-or-neither atomicity.
- Extension support (Phase 8): `ExtendedMethodCallTypeResolver` (default `BEFORE`) + a single adoption branch in `wireEventHandler` creating `EventHandlerExtension`; name-matched `base_handler_exists`; unprefixed names; Phase 7 machinery reused byte-for-byte.
- Closure (Phase 9): provider-neutral Qwen priming, 9-class regression matrix green (56 tests), full reactor build + p2 site, human-approved live-EDT smoke (base + extension).

### What Worked
- **Research-first with real-jar verification.** With the `edt-javadoc` MCP unreachable, researchers used `javap` against the installed 1C:EDT 2025.1.5 platform jars — resolving the milestone's "highest residual risk" (the `ExtendedMethodCallType` default = `BEFORE`) as a bytecode fact, not a Phase-9 deferral.
- **Injectable seams for headless testability.** The `ModuleFileWriter` / event-catalog seams let correctness-critical logic (directive, signature, idempotency, rollback) be unit-tested with zero live-EDT dependency — 69 green tests across the surface.
- **Plan-checker caught locked-decision drift.** Two research-driven corrections (EXT-03 mechanism, QA-01 provider-neutral scope) that superseded locked CONTEXT.md decisions were surfaced to the user for explicit approval instead of being silently applied.

### What Was Inefficient
- **CLAUDE.md drift cost a mid-flight correction.** The documented Qwen-native architecture (`isQwenNative`/`QwenFunctionCallingTransport`) no longer exists in the code; Phase 9 research had to discover this and rescope QA-01. A stale project-instruction file forced a locked-decision override that better upfront doc hygiene would have avoided.
- **CONTEXT.md decisions outran the code twice.** Both overrides trace to grey-area proposals made before research verified the actual codebase — a sign discuss proposals for deep-EDT phases should lean more on "research will confirm the mechanism" than on asserting a specific one.

### Patterns Established
- Deferring a live-EDT human smoke across phases to a single consolidated closure gate (Phases 7+8 → Phase 9 QA-03) — one human validation instead of three.
- Recording user-approved decision overrides prominently (top-of-plan note + CONTEXT.md amendment), not buried in a code comment.

### Key Lessons
- For EDT-platform work, treat CLAUDE.md's API-specific rules as potentially stale; verify against the installed jars. Rewrite the Qwen rules to match the generalized provider layer.
- A "closure phase" (prime + prove + build + human gate) with almost no new production code is a clean milestone-ending shape — most of QA-02 was already satisfied by prior-phase tests.

### Cost Observations
- Model mix: planning=opus, research/execute/verify/checker=sonnet.
- Sessions: 1 autonomous `/gsd-autonomous` run (Phases 7–9 discuss→plan→execute→verify; Phase 6 pre-existing).
- Notable: 2 user pauses for locked-decision overrides + 1 terminal live-EDT smoke gate — everything else ran unattended.

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
