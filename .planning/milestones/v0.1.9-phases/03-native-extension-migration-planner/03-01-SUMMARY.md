# 03-01 Summary — Native Extension Migration Planner

Status: completed

## Completed

- Added `migrate_to_extension_native` as a dry-run-first high-level extension migration planner.
- The planner accepts `source_project`, `extension_project`, and `source_fqns`.
- Dry run emits ordered operations with source FQNs, target effective FQNs, coverage sections, skipped/unsupported sections, and warnings.
- Representative fixtures cover InformationRegister, Catalog, HTTPService, CommonCommand, ScheduledJob, Bot, and Role.
- Apply mode is explicitly gated and refuses to mutate until dry-run review and validation-token flow are implemented by composing lower-level primitives.
- Source deletion remains explicitly skipped/out of scope.

## Verification

- `mvn -pl bundles/com.codepilot1c.core.tests -am -Dtest='*Extension*Test,*Migration*Test,*Metadata*Test,*ModuleArtifact*Test,*RoleRights*Test,*ValidateRequest*Test' test`
- Result: BUILD SUCCESS; Tests run: 30, Failures: 0, Errors: 0, Skipped: 0.

## Release Gate Reminder

Before publishing/installing this milestone as an update site, run full reactor packaging: `mvn -DskipTests package`, then install/smoke in EDT.
