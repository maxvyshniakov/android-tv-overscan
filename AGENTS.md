# Android TV Overscan — Agent Guide

Entry point for coding agents working in this repository. Project architecture lives in [docs/architecture.md](docs/architecture.md); operational facts follow the Mastack project contract under `docs/agents/`:

- [docs/agents/publishing.md](docs/agents/publishing.md) — branches, pre-push gate, push, CI, release.
- [docs/agents/checks.md](docs/agents/checks.md) — focused checks, full gate (`bun run verify`), heavy checks, review rules.
- [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md) — GitHub Issues workflow and reconciliation.
- [docs/agents/orchestration.md](docs/agents/orchestration.md) — Lanefather + Slotfather + Repo-Kit lane workflow.

## Key constraints

- Application ID stays `com.skipstery.projectorcalibrator` so signed upgrades preserve saved overscan profiles.
- Android APK builds without Gradle via `python3 build.py` (JDK 17+/21, Android SDK `android-34`, `build-tools;36.0.0`).
- Never reboot, disconnect, or modify a connected TV device without explicit owner approval.
