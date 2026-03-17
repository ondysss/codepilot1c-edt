# QA Config Contract

`tests/qa/qa-config.json` is the project-level configuration for CodePilot1C QA / Vanessa Automation.

## Important fields

- `vanessa.epf_path`
  Path to `VanessaAutomation.epf`.
- `vanessa.params_template`
  Path to a base Vanessa Automation JSON config. If omitted, runtime `va-params.json` is generated from `qa-config.json`.
- `vanessa.steps_catalog`
  Optional JSON catalog used only for advisory precheck of `unknown_steps`.
- `paths.features_dir`
  Directory with `.feature` files.
- `paths.steps_dir`
  Directory with step libraries passed to Vanessa runtime.
- `paths.results_dir`
  Directory for QA run results.
- `edt.project_name`
  EDT project name when `edt.use_runtime=true`.
- `test_runner.unknown_steps_mode`
  One of `off`, `warn`, `strict`.

## Minimal example

```json
{
  "vanessa": {
    "epf_path": "C:/tools/VanessaAutomation/VanessaAutomation.epf",
    "params_template": "tests/qa/vanessa-base.json"
  },
  "paths": {
    "features_dir": "tests/features",
    "steps_dir": "tests/steps",
    "results_dir": "tests/qa/results"
  },
  "edt": {
    "use_runtime": true,
    "project_name": "cf"
  },
  "test_runner": {
    "use_test_manager": true,
    "timeout_seconds": 300,
    "unknown_steps_mode": "warn"
  }
}
```

## `steps_dir` vs `steps_catalog`

- `paths.steps_dir` affects the real Vanessa Automation run.
- `vanessa.steps_catalog` affects only CodePilot1C precheck for `unknown_steps`.

If `steps_catalog` is outdated, CodePilot1C may warn about unknown steps even when Vanessa runtime supports them.
