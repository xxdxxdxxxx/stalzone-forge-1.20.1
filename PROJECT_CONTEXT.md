# PROJECT_CONTEXT вЂ” AI / developer handoff

Read this before touching the code. It reflects the state of the branch
`feature/tacz-native-events-and-server-inventory` (July 2026).

## What this is

ZoneWars: a STALKER-flavored RED vs BLUE PvP mod (Forge 1.20.1, Java 17, mod id `zonewars`,
jar `zonewars-0.2.0-alpha.jar`). Development checkout: `C:\stalzone-forge`. Test instance:
`C:\Users\xxdxxdxx\AppData\Roaming\ElyPrismLauncher\instances\1.20.1(2)\minecraft`.

## Client architecture (src/main/java/ru/zonewars/)

| File | Role |
| --- | --- |
| `client/ZoneWarsClient.java` | Key bindings (M/O/G/I), client tick: on death opens the PDA deployment map; M opens the PDA map. |
| `client/ClientStateReceiver.java` | Receives serialized match state; on `respawnPrompt` calls `CampChatMapOverlay.openDeployment`. NEVER open any other screen from here. |
| `client/state/ZoneWarsState.java` | Parses/holds the `Snapshot` record (see protocol below). |
| `client/net/ZoneWarsNetworking.java` | Client senders: `requestState()`, `chooseRespawn(kind)`, `confirmRespawn()`, `sendPing(type,x,z)`, `setWaypoint(x,z)`, `openSquadMenu()`, `openTacticalInventory()`. |
| `client/map/CampChatMapOverlay.java` | THE map UI. Renders the ZoneWars layer over the campchat PDA map screen (reflection, `SCREEN_CLASS` = campchat screen FQN): header, capture points, teammates, respawn icons (flag/tent/mast glyphs) with hover + pulse animations, DEPLOY bar, mouse/keyboard handling (1-3, ENTER). `openPda`/`openDeployment` are the only entry points; `openDeployment` self-guards (cooldown, retry cap, no-op if PDA already open). Failures log as `[ZoneWars] PDA open failed:`. |
| `client/map/XaeroWaypointBridge.java` | Reflection bridge to Xaero: mirrors capture points, own-team base/tent/rally respawns and pings as temporary waypoints every 40 ticks; auto-opens the deployment map on `respawnPrompt`; `markDeploying()` pauses reopening for 4s after DEPLOY. `active()` = Xaero minimap loaded and bridge healthy. |
| `client/ui/ZoneWarsHud.java` | HUD: score bar, compass, point chips, squad panel (top-left, y=170 to clear Xaero's minimap). Custom round radar (baked AA disc/ring/arrow textures) renders ONLY when `XaeroWaypointBridge.active()` is false. |
| `client/ui/ZoneInventoryContainerScreen.java` | Tactical inventory screen (WIP server container). |
| `forge/*` | Server side: match rules, networking, kits, economy, respawn teleport via `PlayerRespawnEvent`. |

Deleted: `client/ui/ZoneMapScreen.java` (legacy fullscreen deployment screen). Do not reintroduce it.

## State protocol

`Snapshot(phase, team, redScore, blueScore, seconds, selfYaw, selectedRespawn, respawnPrompt, mapTexture, bounds, bases, points, players, respawns, markers, killFeed, hit, selfStats)`.
`RespawnState(kind, team, name, x, z, available, seconds, health, maxHealth)`; kinds `BASE|TENT|OUTPOST`, teams `RED|BLUE|NONE`.
Server action strings: `request_state`, `respawn:<kind>`, `respawn:confirm`, `ping:<type>:<x>:<z>`, `waypoint:<x>:<z>`, `squad:menu`, `inventory:open`.

## Respawn flow (current, working)

1. Player dies -> server sets `respawnPrompt=true` -> `ClientStateReceiver`/client tick/bridge all funnel into `openDeployment` -> campchat PDA opens on the Map tab.
2. Overlay draws base/tent/rally icons at world positions (clamped to the panel); click = `chooseRespawn`, click the selected icon again / ENTER / DEPLOY bar = `confirmRespawn` -> server teleports on respawn.
3. Vanilla `DeathScreen` remains the last-resort fallback (retry cap 40) вЂ” its Respawn button still works.

## External mods (do not break)

- **campchat 1.0.0** by `kltyton` вЂ” permission granted, non-commercial, keep credit. No sources; all integration is reflection (`com.kltyton.campchat.client.gui.CampChatScreen`, fields `activeTab`/`mapPanel`/`embeddedMap`, `$MainTab.MAP`, GuiMap `cameraX/cameraZ/scale`; divide scale by gui scale).
- **Xaero Minimap 26.3.0 / World Map 1.43.0** вЂ” campchat embeds the world map; waypoint reflection supports both old (`xaero.common.minimap.waypoints.Waypoint`) and new (`xaero.hud.minimap.*`) class layouts. The red dotted "where I ran" trail on maps is Xaero footprints (user-side setting / config `footprints` line).

## Dev workflow (Windows PowerShell 5.1)

- Patches are delivered as `apply-*.ps1` scripts: full-file here-strings + `Write-NoBom` (UTF-8 no BOM, `.bak` backup) + Gradle build gate + `git add -A -- . ":(exclude)*.bak" ":(exclude)*.ps1"` + commit + push. Git writes to stderr: always run git/gradle through `cmd /c "... 2>&1"` (`Invoke-Native`).
- JDK 17 pinned via `gradle.properties` (`org.gradle.java.home`). Gradle 8.10.2 + ForgeGradle 6.
- `deploy-zonewars-to-instance.ps1` copies the jar to the instance and ensures Xaero mods from Modrinth.
- Known CI issue: the GitHub Actions build check fails in ~25s on PRs while local builds pass вЂ” the workflow lacks proper JDK 17 setup. Fix `.github/workflows` or merge on local green builds.

## Java pitfalls learned here

- `NativeImage` is ABGR вЂ” convert ARGB via helper; bake textures once (rebuild ~1500ms), never per-frame pixel work (FPS!).
- Player arrow rotation: `yaw + 180` points up; use pose translate+mulPose.
- `GuiGraphics.blit(ResourceLocation, x, y, w, h, u, v, uW, vH, texW, texH)`; `AbstractTexture.setFilter(blur, mipmap)`.
- HUD renders only after the `HOTBAR` overlay, not on every `RenderGuiOverlayEvent.Post`.
- Keep key binds off `TAB` (vanilla player list) вЂ” ping is on `G`.

## Test checklist

1. `/zw join auto` + `/zw start`; capture a point (`/zw capturedebug` -> `inside=true` вЂ” still unverified after the superflat Y fix).
2. Die -> PDA opens with icons; click-click / ENTER / DEPLOY respawns; no legacy screen anywhere.
3. M opens the PDA map alive; icons selectable; markers anchored while panning.
4. Xaero minimap (top-right circle if so configured) shows point letters + B/T/R waypoints.
5. TaCZ kits: `/zw validatekits`; killfeed on TaCZ kills (known gap).

## Roadmap / open items

- Real server-backed drag/drop tactical inventory container.
- Native TaCZ Forge EventBus adapter for exact hit/kill stats.
- Fix GitHub Actions workflow (JDK 17) and clean `*.bak`/`*.ps1` from git history.
- Optional: prettier respawn icon art via baked textures instead of fill-glyphs.