# Phase 5 Context — Release Install and Live EDT Closure Smoke

## Why this phase exists

The repository build can pass while the installed EDT runtime still fails. This phase is the explicit GSD closure gate for v0.1.9: no milestone closure until the update-site artifact is installed and live-smoked in the target EDT workspace.

## Workspace

`/Volumes/T9/workspace/do`

Projects:

- Main configuration: `ДО`
- Extension: `ДО.Артель`

## Required prompt

`.planning/audits/edt-extension-migration-live-audit-agent-prompt.md`

## Known local EDT cleanup

The EDT logs contain stale p2 repository failures for missing old zip files. Remove those old Available Software Sites or ensure they are ignored before interpreting fresh update results.

## Closure rule

If fresh live smoke returns `FAIL`, do not close the milestone. Create another remediation phase or reopen Phase 4 with the new audit findings.
