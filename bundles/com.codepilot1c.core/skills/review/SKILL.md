---
name: review
description: Review BSL/metadata changes for bugs, regressions, spec compliance, and code quality.
allowed-tools: [read_file, grep, glob, list_files, get_diagnostics, edt_metadata_details, scan_metadata_index, inspect_form_layout, bsl_symbol_at_position, bsl_type_at_position, bsl_analyze_method, bsl_module_context, bsl_module_exports, bsl_list_methods, edt_find_references, inspect_platform_reference, git_inspect]
backend-only: false
allow-implicit: true
implicit-triggers: [review, ревью, code review, проверь код, проверить код, посмотри код, аудит кода, check code]
---
Review changes with defect-first ordering.

- Start with the highest-risk files and flows.
- Prioritize behavioral regressions, invariants, and missing validation.
- Keep summaries short; findings come first.

## BSL quality checklist
- Naming: Russian identifiers, PascalCase for procedures/params/variables.
- Annotations: &AtServerNoContext for server calls from forms. Minimal client logic.
- Queries: parameterized via &Param (no concatenation). No SELECT *. No queries in loops (N+1). LIKE only with index.
- Transactions: BeginTransaction in try/catch. RollbackTransaction first line in catch. No interactive calls inside.
- BSP usage: CommonModule.* instead of direct attribute access. LongRunningOperations for background. PrintManagement for print.
- Strings: NStr() for localizable strings. No hardcoded user-visible text.
- Exports: comment block required for all export procedures/functions.

## EDT-specific checks
- Metadata: UUIDs correct, no duplicate FQNs.
- Forms: DataPath bindings valid, all elements linked to attributes.
- Modules: compilation directives match call context (&AtClient/&AtServer).
- Diagnostics: get_diagnostics(scope=project) shows 0 new errors.

## Verdict format
**ACCEPT** - all correct
**ACCEPT-WITH-NOTES** - minor notes, non-blocking
**REQUIRES-FIXES** - critical issues with file:line references and fix recommendations
