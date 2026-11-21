# Checks

## Focused
- Python launcher: `bun run test:launcher` (`python3 -m unittest discover -s tests -v`) and `bun run check:cli`.
- Java protocol & geometry tests: `bun run test:java` (`python3 scripts/test_geometry.py`).
- Android APK build: `bun run build:apk` (`python3 build.py`).

## Full gate
- `bun run verify` (`repo-kit verify`, 1 Slotfather slot) runs launcher tests, CLI check, Java tests, APK build, and `repo-kit check`.

## Heavy checks
- Managed by `repo-kit` and `.githooks/lane-check` with Slotfather leases (`REPO_KIT_SLOT`).

## Review
- Independent review runs after `lanefather land` using `lanefather review <id>`.
- When running under Antigravity-only policy, pass `--reviewer neon` (or another Antigravity model family member distinct from the implementer).
