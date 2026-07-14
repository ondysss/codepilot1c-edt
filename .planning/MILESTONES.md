# Milestones

## v0.1.10 Managed Form Event Handlers (Shipped: 2026-07-14)

**Phases completed:** 4 phases, 10 plans, 21 tasks

**Key accomplishments:**

- Injectable event-catalog seam (EventHandlerCatalog/EventHandlerTargetResolver), extended mutate_form_model SCHEMA op enum, and InspectFormLayoutResult DTO extension, all unit-tested against fakes with zero live-EDT dependency.
- Wired add_event_handler/set_event_handler/remove_event_handler into EdtMetadataService's applyFormModelOperations switch, via a single upsert-by-(target,event) implementation, form-root-aware target resolution, button/group rejection, and a deterministic handler-name fallback — all unit-tested against a fake event catalog with zero live-EDT dependency.
- inspect_form_layout now surfaces existing event handlers as {event, handlerName} on both the form root (via formProperties) and every EventHandlerContainer FormItemNode, unconditionally, closing the read side of the event-handler API spine.
- Pure `BslHandlerStubGenerator` deriving directive from `Event.isServerCallWithContextNotAllowed()`/`Environments` and reproducing the widest `ParamSet` signature verbatim in RU/EN, backed by a small greppable `BslKeywords` literal map — zero I/O, fully unit-tested against `McoreFactory` fakes.
- Injectable `ModuleFileWriter` seam (IFile-backed prod impl + in-memory test fakes) plus a pure `BslHandlerStubWriter` orchestrator that appends/verifies a generated BSL stub into `Module.bsl` and reports `WRITTEN`/`SKIPPED_EXISTING_WARN`/`WRITE_FAILURE` -- proving idempotency, never-overwrite, ScriptVariant-correct detection, and the write-failure both-or-neither signal entirely headlessly.
- Threaded the 07-01 pure generator and 07-02 injectable writer into `EdtMetadataService.updateFormModel`'s post-export tail: `add_event_handler`/`set_event_handler` now atomically produce a correctly-directived BSL stub, with a fresh-re-resolving compensating rollback on write failure (STUB-01 both-or-neither) and a warn-not-fail path for pre-existing procedures (Pitfall 3) — Phase 6 regression stays green.
- Pure, EMF-transaction-free call_type resolver for EXT-01 — resolves omitted input to the bytecode-verified BEFORE default, matches valid literals case-insensitively via `Enum.name()` + `getLiteral()`, and lists all four allowed values on invalid input.
- Adds a single adopted-vs-native decision point to `EdtMetadataService.wireEventHandler`: ADOPTED forms get `EventHandlerExtension` with an explicit-or-resolved `call_type` (via Plan 01's `ExtendedMethodCallTypeResolver`) and a name-matched `base_handler_exists` observability flag, echoed in the operation summary; NATIVE forms keep Phase 6's plain `EventHandler` path unchanged; Phase 7's BSL stub machinery is reused byte-for-byte.
- Appended provider-neutral event-handler op priming (event/target/handler_name/call_type) to `BackendToolSurfaceRewriteContributor`'s `mutate_form_model` case, and confirmed the shipped 9-class event-handler regression matrix runs green (56 tests, 0 failures) with no new test authored.
- Green full reactor build (qualifier `0.1.7.20260714-0435`) compiling the QA-01 priming edit, p2 update site assembled at `repositories/com.codepilot1c.update/target/repository`, and a concrete ordered live-EDT smoke checklist (`09-SMOKE-CHECKLIST.md`) authored for both base-config and extension-adopted forms — milestone closure now awaits the user's live-EDT smoke confirmation at the Task 3 checkpoint.

---

## v0.1.9 EDT Extension Native Migration Tooling (Shipped: 2026-07-13)

**Phases completed:** 5 phases, 5 plans, 0 tasks

**Key accomplishments:**

- v0.1.9 closed on the user's live-EDT re-verification, with the branch's outstanding EDT-tooling and chat-UI remediation committed first.

---
