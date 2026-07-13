# Forge 1.20.1 migration status

## Decisions

- Target: Minecraft 1.20.1, Forge 47.x, Java 17.
- The original Forge `campchat-1.0.0.jar` is a runtime mod. The GitHub `campchat-fabric` module is not ported.
- TaCZ is integrated through a reflection bridge so exact patch releases can be tested without recompiling ZoneWars.
- Third-party JARs are not committed into the source tree.

## Completed in this migration package

- ForgeGradle 6 project and 1.20.1 metadata.
- Java 17 toolchain.
- Runtime dependency plan.
- Source-only repository layout; Fabric runtime server and bundled JARs removed.
- Existing resource pack preserved.
- Pure client state model preserved.
- Original Fabric sources preserved under `docs/migration/reference` for parity review.

## Next compile gates

1. Finish Forge event/network adapter in `ZoneWarsForge`.
2. Complete Mojmap method conversion in the three client UI classes.
3. Bind TaCZ Forge 1.1.8 shoot/hurt/kill events after the exact TaCZ JAR is placed in `run/mods`.
4. Verify the exact Xaero World Map version expected by CampChat's direct `xaero.map.gui.GuiMap` reference.
5. Run two-player dedicated-server tests before importing a production world.

## Ported source now present

- `ZoneWarsForge.java`: Forge lifecycle, command registration, server ticks, logout/respawn, damage/death and block-interaction adapters around the existing match/economy/squad/clan/respawn logic.
- `ZoneWarsNetwork.java`: Forge `SimpleChannel` action/state transport with explicit packet size limits.
- `ZoneWarsClient.java`: Forge key registration, client tick handling, custom death/map/inventory entry points.
- Client state parser and UI sources mechanically converted from Yarn names to 1.20.1 Mojmap names.
- Full supplied Fabric implementation retained under `docs/migration/reference` for parity checks only.

## Build verification limitation

The sandbox could not resolve `services.gradle.org`, so Gradle/Forge dependencies could not be downloaded and compilation was not executed here. The first build on a networked machine is expected to expose remaining Mojmap/API signature corrections, especially in GUI rendering and the reflective TaCZ item builder. This package is the active first Forge port, not a tested release JAR.

## Build-log pass 1

Corrected the first 84 compiler diagnostics: HUD package names, Forge GUI method names, DamageSource accessors, command player lookup, PlayerList iteration, item/container mappings, world/block methods, registry access and Map.Entry values.
