# Contributing to ZoneWars

## Development target

Use the active target unless the project owner explicitly changes it:

```text
Minecraft 1.20.1
Forge 47.4.0
Java 17
TaCZ 1.1.8-hotfix
```

Do not move the project back to Fabric or Minecraft 1.21.x without an explicit decision.

## Before changing code

1. Create a branch from `main`.
2. Keep changes focused: one topic per PR.
3. Prefer small safe steps over large rewrites.
4. Do not commit external mod JARs unless redistribution permission is clear.

## Build and test

Run at least:

```powershell
.\gradlew.bat clean test
```

For release candidates, run:

```powershell
.\gradlew.bat clean build
```

If local Windows memory fails during `reobfJar`, use:

```powershell
.\gradlew.bat clean build --no-daemon --max-workers=1
```

GitHub Actions must pass before merging.

## Runtime testing

Java tests do not replace Minecraft runtime testing. Before release, follow:

```text
docs/RUNTIME_TEST_CHECKLIST_RU.md
```

Especially verify:

- TaCZ event registration in `logs/latest.log`;
- `/zw validatekits`;
- capture progress with `/zwa capturedebug`;
- respawn behavior;
- shop/economy behavior;
- client/server JAR version match.

## Code style

- Keep server-authoritative logic on the server.
- Never trust client packets for permissions, phase checks, money, team, or position-sensitive decisions.
- Prefer pure helper methods in `ZoneWarsRules` when logic can be tested without Minecraft.
- Avoid adding more unrelated logic to `ZoneWarsForge.java` when a focused helper class is practical.
- Log integration failures clearly; do not silently swallow important runtime errors.

## Persistence

ZoneWars stores data in:

```text
config/zonewars/
```

When changing persistence:

- preserve backward compatibility where possible;
- avoid overwriting unreadable files with defaults;
- keep `.bak` recovery behavior;
- validate numeric values loaded from JSON.

## Pull request checklist

- [ ] Branch is up to date with `main`.
- [ ] `clean test` passes.
- [ ] `clean build` passes locally or in GitHub Actions.
- [ ] Runtime checklist items relevant to the change were tested.
- [ ] No external JARs or local run folders were committed.
- [ ] README/docs were updated if commands or setup changed.
