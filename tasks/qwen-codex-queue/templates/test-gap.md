# {{TITLE}}

Goal:

- Add or adjust the smallest useful test coverage for the behavior described below.

Context:

- Target behavior: {{TARGET_BEHAVIOR}}
- Current gap: {{CURRENT_GAP}}

Constraints:

- Prefer updating an existing test file over creating a new test suite.
- Do not change production code unless the test exposes a real defect that must be fixed for the test to pass.

Done when:

- The intended behavior is covered by a focused automated test.
- The test is stable and aligned with local conventions.

Verification:

- Run: `{{VERIFICATION_COMMAND}}`
