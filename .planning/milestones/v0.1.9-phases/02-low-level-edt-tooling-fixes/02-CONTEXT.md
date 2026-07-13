# Phase 2 Context — Implement Low-Level EDT Mutation and Diagnostics Fixes

## Goal

Make existing CodePilot1C tools correct for the low-level operations required by native extension migration.

## Must-haves

- Shared TypeDescription setter for non-`BasicFeature` containment fields.
- Effective extension-prefixed names in validation and mutation result.
- Command module artifact support for CommonCommand.
- Standard command group aliases/candidates.
- Bot adopt lookup/unsupported diagnostics.
- Extension role/config-right diagnostics with available values.

## Boundaries

- Keep built-in registrations in `ToolRegistry.registerDefaultTools()` if new tools are added.
- Preserve validation-token flow for mutating tools.
- New/changed tool schemas must stay strict and Qwen-compatible.
