# Phase 3 Context — Dry-Run Native Extension Migration Planner

## Goal

Add a high-level migration planner/tool that composes corrected low-level primitives into a safe `migrate_to_extension_native(...)` workflow.

## Desired tool shape

`migrate_to_extension_native(source_project, extension_project, source_fqns, create_native=true, apply_extension_prefix=true, copy_modules=true, copy_children=true, copy_forms=true, copy_rights=true, rewrite_references=true, dry_run=true)`

## Safety rules

- Dry-run-first by default.
- Apply mode must require validation token/confirmation.
- Do not delete source objects in this milestone.
- Unsupported pieces are explicit skipped operations with reasons, not silent omissions.
