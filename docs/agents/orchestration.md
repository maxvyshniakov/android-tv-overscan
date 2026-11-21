# Orchestration

Use Mastack's global `orchestrate` skill with `lanefather`, `slotfather`, and the shared `@maxvyshniakov/repo-kit` lane adapter (`.githooks/lane-check`).

- Project facts live in `.lanefather.toml`.
- Register a lane with `lanefather start <id> --ticket <N> --claims '<globs>' --create`.
- Implementers commit their work, run focused checks, then run `lanefather check <id>` on the committed tree.
- Coordinator lands green lanes into an integration worktree with `lanefather land <id> --into <worktree>`, runs independent review (`lanefather review <id>`), handles at most one fix round via `--fix-lane`, and cleans up with `lanefather done <id>`.
- Do not touch or reboot any connected Android TV device during automated lane execution.
