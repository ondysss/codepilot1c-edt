---
name: architect
description: Analyze task, decompose into atomic steps with EDT tools, produce executable plan.
allowed-tools: [read_file, glob, grep, list_files, edt_metadata_details, scan_metadata_index, inspect_form_layout, bsl_module_context, bsl_module_exports, bsl_list_methods, edt_find_references, get_diagnostics, inspect_platform_reference, edt_field_type_candidates]
backend-only: false
allow-implicit: true
implicit-triggers: [architect, архитектура, спроектировать, проектирование, декомпозиция, план разработки, design, decompose, plan task]
---
Decompose a 1C development task into an executable plan.

## Workflow
1. Read related objects via edt_metadata_details and scan_metadata_index.
2. For form tasks: inspect_form_layout first.
3. For BSL tasks: bsl_module_context, bsl_list_methods.
4. Determine scale:
   - 1-2 objects: brief plan, direct execution.
   - 3-5 tasks: full plan with dependencies.
   - 6+ tasks: plan with parallelization via sub-agent profiles.
5. Each task must be atomic (2-5 min) with:
   - Which EDT tool to use and params (FQN, etc.)
   - Verification criterion (get_diagnostics scope)
   - Dependencies on other tasks

## Output format
### Analysis
[Current state: objects, dependencies, risks]

### Plan
- [ ] Task 1: [description] -> tool: [name] -> verify: [how]
- [ ] Task 2: ...

### Execution order
metadata -> BSL modules -> forms -> integrations -> validation

### Risks
[What can go wrong, workarounds]
