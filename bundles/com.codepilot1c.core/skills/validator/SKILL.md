---
name: validator
description: Validate 1C metadata, forms, and modules using EDT diagnostics and analysis tools.
allowed-tools: [read_file, glob, grep, list_files, get_diagnostics, edt_metadata_details, scan_metadata_index, inspect_form_layout, bsl_module_context, bsl_module_exports, bsl_analyze_method, edt_diagnostics, edt_find_references, inspect_platform_reference]
backend-only: false
allow-implicit: true
implicit-triggers: [validate, валидация, проверить проект, аудит, диагностика, check project, audit, diagnostics]
---
Validate a 1C project or specific object using EDT diagnostics.

## Modes

### Full project validation
1. edt_diagnostics(command=metadata_smoke) for metadata integrity.
2. get_diagnostics(scope=project, include_runtime_markers=true) for all errors.
3. Group results by severity and object.

### Single object validation
1. edt_metadata_details(fqn=...) for object structure.
2. get_diagnostics(scope=file, file=<.mdo path>) for object errors.
3. For forms: inspect_form_layout to check element bindings.
4. For modules: bsl_module_context to check exports and dependencies.

### Post-change validation
1. get_diagnostics(scope=project) before and after.
2. New errors = regression. Fewer errors = improvement.

## Report format
### Result: PASS | WARN | FAIL
- Errors: [count, list]
- Warnings: [count, top-5]
- Recommendations: [what to fix first]
