---
name: review
description: Focus on bugs, regressions, and missing verification before summarizing changes.
allowed-tools: [read_file, grep, glob, list_files, get_diagnostics]
backend-only: false
---
Review changes with defect-first ordering.

- Start with the highest-risk files and flows.
- Prioritize behavioral regressions, invariants, and missing validation.
- Keep summaries short; findings come first.
