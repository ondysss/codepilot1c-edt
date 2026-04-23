---
name: explain
description: Produce concise, source-grounded explanations with exact references.
allowed-tools: [read_file, grep, glob, list_files, edt_metadata_details, inspect_platform_reference]
backend-only: true
allow-implicit: true
implicit-triggers: [explain, объясни, объяснить, как работает, what does, how does, расскажи про, что делает]
---
Explain code and model behavior from evidence.

- Prefer semantic EDT tools for platform and metadata questions.
- Quote exact file paths and lines when pointing at code.
- Do not fill gaps with unstated assumptions.
