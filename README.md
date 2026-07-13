# StalZone / ZoneWars — Forge 1.20.1

STALKER-inspired Minecraft PvP project built around **TaCZ 1.1.8**. The active target is **Minecraft 1.20.1, Forge 47.4.0 and Java 17**.

## Active project

ZoneWars provides:

- RED vs BLUE matches;
- waiting, preparation, active, overtime and end phases;
- capture points and team score;
- TaCZ weapon kits and ammunition;
- server-authoritative economy and shop;
- squads, clans and chat;
- player statistics, hit markers and kill feed;
- tactical HUD, map and inventory UI;
- tents, outposts and selectable respawns;
- JSON persistence for arena, clans and player stats.

## Runtime stack

Required:

- Minecraft `1.20.1`
- Forge `47.4.0`
- Java `17`
- TaCZ Forge `1.1.8-hotfix`

Modpack components:

- CampChat `1.0.0` Forge — use the original JAR from `kltyton`; do not port or ship `campchat-fabric`.
- Xaero's World Map `1.42.0` Forge — CampChat directly embeds its map classes.
- Xaero's Minimap `26.2.0` Forge — optional, with a key conflict to resolve.
- Curios `5.14.1+1.20.1`.
- GeckoLib `4.8.3`.
- STALKER Cubed Reborn `1.0.9`.
- Stalker Decorations `1.0`.

## Build

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build
```

Output:

```text
build/libs/zonewars-0.2.0-alpha.jar
```

The project compiled successfully with Forge 47.4.0 on 2026-07-13.

## Local modpack test

Use a normal Prism/ElyPrism instance for external release mods. ForgeGradle `runClient` can fail on production Mixin JARs.

Instance:

```text
Minecraft 1.20.1
Forge 47.4.0
Java 17
```

Copy the built ZoneWars JAR and external mods to the instance `minecraft/mods` folder.

## TaCZ STALKER gun pack

Install `Stalker-Pack1.0.1-Rework.zip` without unpacking:

```text
minecraft/tacz/Stalker-Pack1.0.1-Rework.zip
```

Expected namespace: `stalker`.

The pack contains `stalker:ak101`, `ak102`, `ak103`, `ak200`, `ak203`, `aks74`, `aks74u`, `rpk16` and `pkp`. Some optional LOD references (`ak74m`, `ak105`) are missing and need resource cleanup, but main models are present.

## Controls

- `M` — ZoneWars tactical map (conflicts with Xaero by default)
- `I` — ZoneWars tactical inventory
- `O` — squad UI
- `TAB` — danger ping
- `P` — CampChat PDA

Recommended: move Xaero World Map to `Y` and keep ZoneWars map on `M`.

## Main commands

```text
/zw join auto|red|blue
/zw leave
/zw start
/zw stop
/zw state
/zw balance
/zw stats
/zw shop
/zw buy <item>
/zw kit <kit>
/zw validatekits
/zw placetent
/zw placeoutpost
/zw respawn base|tent|outpost|confirm

/squad create|invite|join|leave|info
/sc <message>
/clan create|join|leave|stats|info
/cc <message>

/zwa set redspawn|bluespawn
/zwa point add <id> <name> <radius>
/zwa shop add
```

## Known issues

- The Forge TaCZ `GunItemBuilder.forceBuild()` method has no registry argument. The active source contains the corrected bridge; older builds report `gun=false, ammo=true` for all kits.
- The original Forge TaCZ events use Forge EventBus classes, while the old Fabric callback bridge is currently inactive. Generic Forge damage/death events still provide basic tracking; exact TaCZ hit/kill integration needs a native EventBus adapter.
- STALKER Cubed and Stalker Decorations contain invalid/missing model JSON and textures. Their black/missing models are not caused by ZoneWars.
- ZoneWars HUD must render only after one overlay (`HOTBAR`). Rendering on every `RenderGuiOverlayEvent.Post` caused severe FPS loss after joining a team; the active source contains the fix.
- The tactical inventory currently displays inventory and submits purchases but is not yet a complete server-backed drag/drop container.
- CampChat is an external Forge mod. Preserve the `kltyton` credit and permission terms.

See `PROJECT_CONTEXT.md` for the detailed AI/developer handoff.
