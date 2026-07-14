# StalZone / ZoneWars вЂ” Forge 1.20.1

STALKER-inspired Minecraft PvP project built around **TaCZ 1.1.8**. The active target is **Minecraft 1.20.1, Forge 47.4.0 and Java 17**.

## Active project

ZoneWars provides:

- RED vs BLUE matches with waiting, preparation, active, overtime and end phases;
- capture points and team score;
- TaCZ weapon kits and ammunition;
- server-authoritative economy and shop;
- squads, clans and chat;
- player statistics, hit markers and kill feed;
- tactical HUD (score bar, compass, point states, squad panel);
- respawn system: base / field tent / squad outpost (rally), fully driven from the campchat PDA map;
- JSON persistence for arena, clans and player stats.

## Map / UI stack (important)

The in-game map UI is built on two external mods:

- **campchat** (PDA screen by `kltyton`, used with permission, non-commercial) вЂ” the `M` key and the death flow open its PDA on the Map tab. `CampChatMapOverlay` draws the ZoneWars layer (match header, capture points, players, respawn icons, deployment controls) on top of the PDA map via reflection; no compile-time dependency.
- **Xaero's Minimap + World Map** вЂ” the HUD minimap is Xaero's own minimap (the custom round radar in `ZoneWarsHud` renders only when Xaero is absent). `XaeroWaypointBridge` mirrors capture points, base/tent/rally respawns and pings into Xaero waypoints by reflection.

The legacy fullscreen deployment screen (`ZoneMapScreen`) was deleted. Respawn selection lives on the PDA map: icons for base (flag), tent and rally (mast); click = select (pulse animation), click again / `ENTER` / `DEPLOY` button = respawn; hotkeys `1-3`.

## Runtime stack

Required:

- Minecraft `1.20.1`
- Forge `47.4.0`
- Java `17`
- TaCZ Forge `1.1.8-hotfix`
- campchat `1.0.0` Forge вЂ” original JAR from `kltyton`; keep the credit, do not port `campchat-fabric`.
- Xaero's World Map `1.43.0` Forge вЂ” campchat embeds its map classes; required.
- Xaero's Minimap `26.3.0` Forge вЂ” renders the HUD minimap; strongly recommended.

Optional: Curios `5.14.1+1.20.1`, GeckoLib `4.8.3`. STALKER Cubed Reborn and Stalker Decorations ship broken models (not a ZoneWars issue) and stay out of scope.

## Build & deploy scripts

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot"
.\gradlew.bat clean build   # output: build/libs/zonewars-0.2.0-alpha.jar
```

Repository workflow used during development: `apply-*.ps1` scripts (kept out of git) rewrite the touched Java sources without BOM, run the Gradle build gate, then commit and push; `deploy-zonewars-to-instance.ps1` copies the built jar into the Prism/ElyPrism instance and keeps the Xaero mods up to date from Modrinth.

## Controls

- `M` вЂ” open the campchat PDA map with the ZoneWars layer
- `I` вЂ” tactical inventory
- `O` вЂ” squad UI
- `G` вЂ” danger ping (moved off `TAB` to avoid the vanilla player list conflict)
- `P` вЂ” campchat PDA (its own binding)
- Death вЂ” the PDA map opens automatically for respawn selection

## Main commands

```text
/zw join auto|red|blue В· /zw leave В· /zw start В· /zw stop В· /zw state
/zw balance В· /zw stats В· /zw shop В· /zw buy <item> В· /zw kit <kit> В· /zw validatekits
/zw placetent В· /zw placeoutpost В· /zw respawn base|tent|outpost|confirm
/squad create|invite|join|leave|info В· /sc <msg>
/clan create|join|leave|stats|info В· /cc <msg>
/zwa set redspawn|bluespawn В· /zwa point add <id> <name> <radius> В· /zwa shop add
```

## Known issues

- GitHub Actions build check fails on the PR while the local Gradle build succeeds вЂ” the workflow environment (JDK 17 setup) needs fixing; do not treat the red check as a code failure.
- Capture-point fix (player Y vs point Y on superflat) needs in-game verification via `/zw capturedebug` (`inside=true`).
- The tactical inventory displays inventory and submits purchases but is not yet a complete server-backed drag/drop container.
- Exact TaCZ hit/kill integration needs a native Forge EventBus adapter; generic damage/death events provide basic tracking.
- Git history contains stray `*.bak` / `*.ps1` files from early scripts; new commits exclude them (`git add -A -- . ":(exclude)*.bak" ":(exclude)*.ps1"`).
- Red dotted trail on maps is Xaero's "footprints" feature, not ZoneWars; disable it in Xaero settings or via the config patch in the apply script.

See `PROJECT_CONTEXT.md` for the detailed AI/developer handoff.