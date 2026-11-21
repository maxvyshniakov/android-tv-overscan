# Publishing

## Branches
- Main branch: `main` (single-branch repository unless `dev` is created).
- Worktrees: managed by `lanefather` (or `.claude/worktrees/<name>` for integration worktrees).

## Gate
- Managed `.githooks/pre-push` (via `@maxvyshniakov/repo-kit`) runs `bun run verify` on outgoing commits.

## Push
- Push explicitly requested commits via `lanefather promote <sha>` or `git push origin <sha>:refs/heads/main`.

## CI
- `.github/workflows/verify.yml` runs Python launcher tests (Ubuntu/macOS/Windows, Python 3.9 & 3.13) and Android Java tests + APK build on Ubuntu.
- Find runs with `gh run list --commit <sha>`.

## Release
- Package release artifacts via `scripts/package_release.py` and tag only on explicit owner instruction.
