Process the local qwen-codex task queue for this repository.

Workflow:

1. Check `.runs/qwen-codex-queue/queue/todo/`.
2. If there are no task files, create a short inbox note that the queue is empty and stop.
3. If tasks exist, run `CLAUDE_LAUNCH_MODE=direct bash tools/run-qwen-codex-queue.sh`.
4. Summarize:
   - how many tasks were processed
   - how many ended in approved
   - how many need human follow-up
   - how many review-followup tasks were generated back into todo
   - note that generated follow-up tasks stay queued for the next run
   - where the queue summary and flow artifacts were written
5. If any tasks landed in `needs_human/` or `failed/`, call that out first.

Rules:

- Work only inside this repository.
- Do not commit, push, or publish anything.
- Use the repository queue flow as-is; do not invent a parallel process.
