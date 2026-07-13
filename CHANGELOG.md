# Changelog

All notable changes to ZoneWars are documented here.

## Unreleased

### Added

- GitHub Actions build workflow.
- Runtime/server checklist documentation.
- Server administrator guide.
- JUnit 5 test support.
- Tested `ZoneWarsRules` capture-zone calculations.
- `/zwa capturedebug` for capture-point diagnostics.

### Changed

- Capture points use vertical cylinder detection instead of a 3D sphere, improving behavior on uneven terrain.
- TaCZ integration now attempts native Forge EventBus registration via reflection.
- Client state no longer exposes exact enemy positions.
- Network packet limits are stricter.
- Arena JSON values are bounded to safe ranges.
- Economy values are capped and validated.
- ResourceLocation construction uses validated parsing where practical.

### Fixed

- Living players can no longer abuse `respawn:confirm` as a teleport.
- Malformed team names no longer throw command exceptions.
- Medkits no longer charge money when the player is already at full health.
- JSON writes are safer and create `.bak` backups.
- Damaged JSON can be recovered from backup when possible.
- Capture point height mismatch no longer prevents capture.

### Known issues

- Full TaCZ behavior still depends on the exact runtime TaCZ event class names.
- Tactical inventory is not yet a full server-backed drag/drop container.
- `ZoneWarsForge.java` is still large and should be split gradually.
- Some STALKER decoration/model issues belong to external mods or packs.
