# Updating one configuration extension through EDT

`edt_update_extension` reloads exactly the selected extension into its parent project's default infobase. It uses the native EDT synchronization service and `reloadInfobase`, without mouse automation or invoking a base-configuration update.

Resolve the scope first:

```json
{"extension_project":"MyExtension","base_project":"MyConfiguration","dry_run":true}
```

Start the update:

```json
{"extension_project":"MyExtension","base_project":"MyConfiguration"}
```

The default is `async=true`. The initial response has `status=scheduled`, `updated=false` and a `job_id`. Poll `update_infobase_status` with that identifier. Success means `state=DONE` and the decoded `result` contains `status=updated`, `updated=true`. A native failure or cancellation means `state=FAILED`; scheduling alone does not establish success. Job results use the existing registry's retention policy.

Parameters:

| Parameter | Default | Meaning |
| --- | --- | --- |
| `extension_project` | required | Exact, open EDT extension project |
| `base_project` | actual parent | Optional check against the parent from EDT's project model |
| `dry_run` | `false` | Inspect the target without starting a reload |
| `async` | `true` | Return immediately with a background job identifier |
| `keep_connected` | `false` | Keep the EDT synchronization connected after reload |
| `allow_conflict_override` | `false` | Explicitly permit the EDT resolver to override conflicting extension changes in the infobase |

The tool rejects a base configuration selected as an extension, a different expected parent, a closed or missing project, a missing default infobase, and an infobase association changed after scheduling. Concurrent extension reloads into the same infobase are rejected until the active operation finishes. Connection paths and credentials are not included in the result; `target_binding` is an opaque hash used to pin the operation's target.

Conflicting infobase changes fail by default. If `allow_conflict_override=true`, the native conflict resolver must actually resolve them; unavailable or failed resolution never fabricates successful completion. Review those changes before opting in.

The runtime adapter accepts compatible five-argument `reloadInfobase` APIs returning a boolean or `IStatus`. An unsupported API or unrecognized return value fails. It selects the synchronization provider from the native EDT platform bundle rather than a higher-ranked third-party wrapper that can expand the update scope.

## Verification

`EdtExtensionUpdateServiceTest` covers project selection, target binding, provider selection, native return values and conflict resolution. `EdtUpdateExtensionToolTest` covers dry runs, synchronous and asynchronous completion, failed work, duplicate operations, recovery after errors and credential-free error output. `EdtUpdateExtensionSurfaceTest` checks registration and the tool schema; against the unmodified core it fails on the missing tool.

With the EDT and JDK versions required by the reactor, run the targeted tests alongside the existing update and background-job regressions:

```sh
mvn "-Dedt.home=$EDT_HOME" -pl bundles/com.codepilot1c.core.tests -am \
  -Dtest=EdtExtensionUpdateServiceTest,EdtUpdateExtensionToolTest,EdtUpdateExtensionSurfaceTest,BackgroundJobRegistryTest,UpdateInfobaseStatusToolTest,EdtUpdateInfobaseToolTest \
  -Dsurefire.failIfNoSpecifiedTests=false package
```

After deployment, verify the tool through MCP and perform an extension reload in a development infobase, followed by a platform test run. Unit tests cannot verify the installed OSGi service or the actual infobase contents.
