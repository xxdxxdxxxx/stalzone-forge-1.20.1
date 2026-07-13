# StalZone / ZoneWars — Project Context

> Updated: 2026-07-13. This is the source of truth for the next developer or AI.

## Fixed decisions

- Active platform: **Minecraft 1.20.1 / Forge 47.4.0 / Java 17**.
- Do not move the active project back to Fabric 1.21.1 unless explicitly requested.
- TaCZ runtime: native Forge `tacz-1.20.1-1.1.8-hotfix.jar`.
- CampChat runtime: original Forge `campchat-1.0.0.jar` by `kltyton`.
- Do not port the abandoned `campchat-fabric` module into the active tree.
- Preserve CampChat author credit and permission restrictions.
- Keep both ZoneWars Tactical Map and Xaero World Map; resolve their default `M` key conflict in controls.
- Third-party JARs should not be committed to a public source repository without redistribution permission.

## Current source layout

```text
src/main/java/ru/zonewars/forge/ZoneWarsForge.java
    Forge mod entrypoint and current gameplay implementation.

src/main/java/ru/zonewars/forge/ZoneWarsNetwork.java
    Forge SimpleChannel messages for client actions and server state.

src/main/java/ru/zonewars/client/
    Client initialization, key mappings and state receiver.

src/main/java/ru/zonewars/client/state/ZoneWarsState.java
    Immutable client snapshot and JSON parsing.

src/main/java/ru/zonewars/client/ui/
    Tactical HUD, map and inventory.

docs/migration/reference/
    Old Fabric sources for parity reference only. Never build these as active modules.

zonewars-pack/
    Legacy resource-pack/model foundation.
```

## Verified

- ForgeGradle project resolves with Forge 47.4.0 and Java 17.
- `clean build` completes successfully.
- Produced mod: `build/libs/zonewars-0.2.0-alpha.jar`.
- A normal Forge 1.20.1 client loads ZoneWars, TaCZ, CampChat, Curios, GeckoLib and Xaero.
- `/zw` commands are registered when the ZoneWars JAR is actually present in the instance.
- ZoneWars map on `M` and tactical inventory on `I` open.
- CampChat opens on `P`.
- HUD/top bar/squad/minimap render after `/zw join auto`.
- Severe join-time FPS drop was traced to rendering the complete HUD after every vanilla overlay event. Fixed by rendering only after `VanillaGuiOverlay.HOTBAR`.

## TaCZ bridge status

### Item creation

Exact Forge TaCZ 1.1.8 signatures were inspected:

```text
GunItemBuilder.create()
    .setId(ResourceLocation)
    .setAmmoCount(int)
    .setAmmoInBarrel(boolean)
    .setFireMode(FireMode)
    .forceBuild()                 // NO ARGUMENTS

AmmoItemBuilder.create()
    .setId(ResourceLocation)
    .setCount(int)
    .build()
```

The first Forge port incorrectly called `forceBuild(registryLookup)`, causing reflection to fail and `/zw validatekits` to report `gun=false, ammo=true`. The source is corrected to call `forceBuild()` with no arguments. Shop icons use the same builder, so this correction also restores TaCZ gun models in shop menus.

### Combat events

Forge TaCZ 1.1.8 event classes are standard Forge EventBus events:

```text
GunShootEvent
GunFireEvent
EntityHurtByGunEvent.Pre
EntityHurtByGunEvent.Post
EntityKillByGunEvent
```

The old Fabric bridge searches for static callback fields and silently does nothing on Forge. Replace it with native `MinecraftForge.EVENT_BUS` listeners. Until then, generic Forge `LivingAttackEvent`, `LivingHurtEvent` and `LivingDeathEvent` provide partial friendly-fire, damage and kill tracking.

## Gun pack

File:

```text
Stalker-Pack1.0.1-Rework.zip
```

Install as:

```text
<instance>/minecraft/tacz/Stalker-Pack1.0.1-Rework.zip
```

The ZIP is already correctly structured:

```text
gunpack.meta.json       namespace: stalker
assets/stalker/
data/stalker/
```

Main gun models, textures, displays, data and sounds exist. Known pack issues:

- AK-101, AK-103 and AKS-74 display JSON reference missing LOD model/texture `stalker:gun/lod/ak74m`.
- AK-102 references missing LOD `stalker:gun/lod/ak105`.
- Main full-resolution models remain present, so these are secondary distance-rendering defects.

## External content issues

`stalker_decor_mod_1.20.1.jar` and `stalkercubedreborn_1.0.9_fix_forge.jar` contain broken resources independent of ZoneWars:

- unsupported element rotations such as `32.5`, `30`, `-180`;
- null UV values;
- missing model files such as `models/custom/bolts.json`, `sign_others.json`, `vss_deco.json`;
- missing textures;
- at least one loot table referencing an unknown item.

These errors generate huge logs and black/missing models. Repair the two JAR resource trees separately; do not modify ZoneWars rendering to compensate.

## Performance rules

- Render ZoneWars HUD once per frame, not once per Forge overlay.
- Full state broadcast is currently every 10 server ticks (2 Hz). Keep it at 2 Hz or reduce it; do not return to 4+ full JSON snapshots per second.
- Long term: split player positions, hit markers and kill feed into compact/delta packets.
- Avoid synchronous disk writes on every gameplay action.

## Next priorities

1. Rebuild after the TaCZ `forceBuild()` fix and verify `/zw validatekits` returns all guns and ammo as true.
2. Verify shop icons show actual TaCZ weapons.
3. Implement native Forge TaCZ EventBus listeners for shoot/hurt/kill and remove the inactive Fabric callback reflection.
4. Repair the two friend-mod resource JARs or obtain corrected builds from their authors.
5. Fix missing STALKER gun-pack LOD resources.
6. Implement a real server-backed inventory/container for safe drag/drop.
7. Split the 3,000+ line `ZoneWarsForge.java` into commands, match, economy, squad, clan, respawn, persistence and TaCZ integration services.
8. Add unit tests for match phases, capture points, economy, respawns, social operations and JSON recovery.

## Build and deployment

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build
```

Copy `build/libs/zonewars-0.2.0-alpha.jar` into the normal Forge instance `minecraft/mods` folder. Do not put the ZoneWars JAR into ForgeGradle `run/mods` while also loading the source mod, or duplicate mod IDs may occur.

## Runtime mod versions tested

```text
Minecraft                 1.20.1
Forge                     47.4.0
Java                      17.0.16
TaCZ                       1.1.8-hotfix
CampChat                   1.0.0
Curios                     5.14.1+1.20.1
GeckoLib                   4.8.3
Xaero's Minimap            26.2.0
Xaero's World Map          1.42.0
STALKER Cubed Reborn       1.0.9
Stalker Decorations        1.0
```
