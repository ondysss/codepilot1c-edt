# 04-01 Summary — Live EDT Audit Remediation

## Status

completed

## Implemented

- Fixed validation schema exposure for `mutate_role_rights` so `edt_validate_request` can issue a matching token for `mutate_role_rights` instead of forcing callers through an invalid `update_metadata` token.
- Added regression coverage for `mutate_role_rights` token routing and schema enum exposure.
- Preserved/enforced `allow_auto_prefix=false` behavior with regression coverage for unprefixed extension names.
- Extended top-level metadata TypeDescription handling for `commandParameterType` by pre-resolving `commandParameterType` values from create/update payloads before the write transaction.
- Added explicit StandardCommandGroup handling for `BasicCommand.group` so `StandardCommandGroup.FormCommandBarImportant` / `FormCommandBarImportant` are applied as standard command groups instead of being resolved as metadata FQNs.
- Added available-values diagnostics for unknown StandardCommandGroup names.

## Verification

Focused regression:

```text
mvn -pl bundles/com.codepilot1c.core.tests -am -Dtest='EdtValidateRequestToolTest,EdtValidateRequestToolSchemaTest,EdtMetadataServiceTypeDescriptionTest' test
BUILD SUCCESS
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
```

Broader focused Phase 4 suite:

```text
mvn -pl bundles/com.codepilot1c.core.tests -am -Dtest='*Extension*Test,*Migration*Test,*Metadata*Test,*ModuleArtifact*Test,*RoleRights*Test,*ValidateRequest*Test' test
BUILD SUCCESS
Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
```

Full repository update-site build:

```text
mvn -DskipTests package
BUILD SUCCESS
```

Produced update-site artifacts:

- `repositories/com.codepilot1c.update/target/repository/plugins/com.codepilot1c.core_0.1.7.20260706-0415.jar`
- `repositories/com.codepilot1c.update/target/repository/plugins/com.codepilot1c.ui_0.1.7.20260706-0415.jar`
- `repositories/com.codepilot1c.update/target/com.codepilot1c.update-0.1.7-SNAPSHOT.zip`

Whitespace check:

```text
git diff --check
# clean
```

## Remaining work

Phase 5 must install the produced update-site into EDT and rerun live smoke in `/Volumes/T9/repo_edt/artel` / `ДО.Артель` to prove that the previous bot audit failures are fixed in the running EDT plugin.
