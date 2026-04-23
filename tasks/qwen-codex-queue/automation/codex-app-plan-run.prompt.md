Process the local planning bundle for this repository through the Claude -> Codex background flow.

Planning source of truth:

- `/Users/alexorlik/repo/codepilot1c-oss/.planning/local/qwen-runtime-surface/BACKLOG.md`
- `/Users/alexorlik/repo/codepilot1c-oss/.planning/local/qwen-runtime-surface/EXECUTION-SLICE.md`

Workflow:

1. Check `/Users/alexorlik/repo/codepilot1c-oss/.planning/local/qwen-runtime-surface/queue/todo/`.
2. If queue tasks already exist there, run exactly this command:

   ```bash
   RUN_ID="auto-$(date +%Y%m%d-%H%M%S)" \
   QUEUE_DIR=/Users/alexorlik/repo/codepilot1c-oss/.planning/local/qwen-runtime-surface/queue \
   QUEUE_RUNS_DIR=/Users/alexorlik/repo/codepilot1c-oss/.runs/claude-codex-plan/qwen-runtime-surface/queue-runs \
   FLOW_RUNS_ROOT=/Users/alexorlik/repo/codepilot1c-oss/.runs/claude-codex-plan/qwen-runtime-surface/flow-runs \
   WORKTREE_PARENT=/Users/alexorlik/repo/codepilot1c-oss/.runs/claude-codex-plan/qwen-runtime-surface/_worktrees \
   CLAUDE_LAUNCH_MODE=direct \
   AUTO_GENERATE_REVIEW_FOLLOWUPS=true \
   FOLLOWUP_MAX_FINDINGS=2 \
   MAX_TASKS=1 \
   bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-claude-codex-queue.sh
   ```

3. After the queue command finishes, apply queue results back into the planning bundle by running exactly this command with the same `RUN_ID`:

   ```bash
   python3 /Users/alexorlik/repo/codepilot1c-oss/tools/qwen-codex-plan-sync.py apply-results \
     --plan-root /Users/alexorlik/repo/codepilot1c-oss/.planning/local/qwen-runtime-surface \
     --queue-run-dir /Users/alexorlik/repo/codepilot1c-oss/.runs/claude-codex-plan/qwen-runtime-surface/queue-runs/$RUN_ID \
     --approved-status done \
     --no-changes-status blocked \
     --needs-human-status in_progress \
     --failed-status blocked \
     --output-json /Users/alexorlik/repo/codepilot1c-oss/.runs/claude-codex-plan/qwen-runtime-surface/runs/$RUN_ID/apply-results.json
   ```

4. If `/queue/todo/` is empty, run exactly this command instead to enqueue one new task from the plan bundle and process it:

   ```bash
   QUEUE_DIR=/Users/alexorlik/repo/codepilot1c-oss/.planning/local/qwen-runtime-surface/queue \
   NEEDS_HUMAN_PLAN_STATUS=in_progress \
   CLAUDE_LAUNCH_MODE=direct \
   AUTO_GENERATE_REVIEW_FOLLOWUPS=true \
   FOLLOWUP_MAX_FINDINGS=2 \
   MAX_TASKS=1 \
   bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-claude-codex-plan.sh \
     /Users/alexorlik/repo/codepilot1c-oss/.planning/local/qwen-runtime-surface
   ```

5. Summarize:
   - which backlog item was processed
   - which items were marked `done`
   - which items remain `in_progress`
   - which `review-followup` tasks were generated
   - queue run directory
   - flow runs directory
6. If the queue command exits with `2`, do not treat that as an automation failure by default. Report failed tasks first, then `needs_human` tasks, and say whether new `review-followup` tasks were generated back into `todo/`.
7. Escalate a `needs_human` item as requiring manual attention only when no new follow-up tasks were generated for it, or when the same backlog item keeps recurring without reaching `approved`.

Rules:

- Treat the planning bundle as the source of truth.
- Do not manually edit backlog statuses outside the plan sync scripts.
- Work only inside this repository.
- Do not commit, push, or publish anything.
