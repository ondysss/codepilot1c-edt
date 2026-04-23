# Qwen Codex Queue Tasks

Versioned catalog of task templates for the `qwen -> codex review` queue flow.

Use these templates to create small, bounded tasks that fit:

- one clear goal
- narrow scope
- focused verification
- reviewable diff

Templates live in [`templates/`](/Users/alexorlik/repo/codepilot1c-oss/tasks/qwen-codex-queue/templates).

Recommended workflow:

1. Pick the closest template.
2. Create a queue task with:

   ```bash
   bash /Users/alexorlik/repo/codepilot1c-oss/tools/new-qwen-codex-task.sh <template> "<task slug>"
   ```

3. Edit the generated file under `.runs/qwen-codex-queue/queue/todo/`.
4. Run the queue:

   ```bash
   bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-qwen-codex-queue.sh
   ```

If a task lands in `needs_human/`, the queue runner can automatically generate one or more `review-followup` tasks back into `todo/` from the latest Codex findings.

Automation prompt templates live in:

- [`automation/codex-app-queue-run.prompt.md`](/Users/alexorlik/repo/codepilot1c-oss/tasks/qwen-codex-queue/automation/codex-app-queue-run.prompt.md) for direct queue sweeps
- [`automation/codex-app-plan-run.prompt.md`](/Users/alexorlik/repo/codepilot1c-oss/tasks/qwen-codex-queue/automation/codex-app-plan-run.prompt.md) for plan-driven background runs from a planning bundle
