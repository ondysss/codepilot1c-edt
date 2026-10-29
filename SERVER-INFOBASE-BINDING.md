# Bind an existing client/server infobase to EDT

`connect_infobase` supports `kind: "server"` in addition to `file` and
`standalone`. It registers an existing 1C server infobase reference, saves access
settings using the EDT access manager and associates it with the project through
the EDT association manager. It does not create a server, a SQL database or an
infobase, and does not load or update any configuration.

```json
{
  "project_name": "Demo",
  "kind": "server",
  "server_address": "localhost:1541",
  "database_path": "existing_demo",
  "set_primary": true
}
```

For this mode, `database_path` is the infobase name registered on the 1C server,
not a file path or a connection string. `server_address` is required. Optional
`login` and `password` are infobase credentials; the password is not returned.
The existing primary infobase replacement guard remains in effect: without
`force: true` an existing primary is preserved and `PRIMARY_EXISTS` is returned.

After binding, first use `edt_update_extension` with `dry_run: true` and verify
the resolved extension, parent and infobase. Then update the intended extension,
wait for `DONE` with `updated: true`, and run `run_yaxunit_tests` on the base
project. Configuration delivery is separate from creating the association.

The regression suite includes public tool/schema checks, validation before
project access and the native server-reference/write sequence. Existing file
and standalone tests are run alongside the new cases.
