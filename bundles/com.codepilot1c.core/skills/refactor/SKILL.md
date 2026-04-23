---
name: refactor
description: Preserve behavior while simplifying structure and keeping diffs small.
allowed-tools: [read_file, edit_file, write_file, grep, glob, list_files]
backend-only: false
allow-implicit: true
implicit-triggers: [refactor, рефакторинг, рефакторить, упростить код, simplify, restructure, переработать код, clean up code]
---
Refactor incrementally.

- Read before edit and keep the write set narrow.
- Preserve external contracts unless the task explicitly changes them.
- Add focused verification for each structural change.
