<#
  ZoneWars x Xaero's Minimap integration
  ======================================
  1. New client class ru.zonewars.client.map.XaeroWaypointBridge:
     - reflection-based soft dependency (compiles without Xaero jar);
     - syncs [ZW] waypoints into Xaero every 2s: capture points (colored by
       owner), own-team tents/rally spawns, ping markers;
     - waypoints are temporary (not saved into the player's waypoint file);
     - works with both old (xaero.common.XaeroMinimapSession) and new
       (xaero.hud.minimap.BuiltInHudModules) Xaero APIs.
  2. ZoneWarsClient: registers the bridge on client setup.
  3. ZoneWarsHud: hides the built-in MINIMAP panel when the Xaero bridge is
     active (compass/score/killfeed stay). Without Xaero installed nothing
     changes.

  Usage (from the repo root):
    powershell -ExecutionPolicy Bypass -File .\apply-zonewars-xaero-map.ps1 -Build -Push
#>
param(
    [string]$RepoPath = ".",
    [string]$Branch = "",
    [switch]$Build,
    [switch]$Push,
    [string]$Remote = "origin",
    [string]$CommitMessage = "Add Xaero's Minimap integration: arena waypoints + HUD minimap dedup"
)

$ErrorActionPreference = "Stop"

function Get-Text([string]$Path) {
    return [System.IO.File]::ReadAllText($Path)
}

function Set-Text([string]$Path, [string]$Text) {
    if (Test-Path $Path) {
        Copy-Item $Path "$Path.bak" -Force
    }
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    [System.IO.File]::WriteAllText($Path, $Text)
}

function Invoke-Native([string]$CommandLine) {
    $eap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    cmd /c "$CommandLine 2>&1" | ForEach-Object { Write-Host $_ }
    $ErrorActionPreference = $eap
    return $LASTEXITCODE
}

# ---------------------------------------------------------------------------
# New file: XaeroWaypointBridge.java
# ---------------------------------------------------------------------------

$BridgeJava = @'
package ru.zonewars.client.map;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import ru.zonewars.client.state.ZoneWarsState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Soft integration with Xaero's Minimap (modid "xaerominimap").
 *
 * <p>Everything is resolved through reflection at runtime, so the mod compiles
 * and runs without Xaero installed. When Xaero is present, ZoneWars pushes
 * temporary waypoints (prefixed with "[ZW]") into the current Xaero waypoint
 * set: capture points colored by owner, the viewer team's tent/rally spawns
 * and ping markers. Waypoints are temporary, so they never pollute the
 * player's saved waypoint files, and they show up both on the minimap and on
 * Xaero's fullscreen world map.</p>
 */
public final class XaeroWaypointBridge {
    private static final String PREFIX = "[ZW] ";
    private static final int SYNC_INTERVAL_TICKS = 40;

    // Xaero color palette indices (same order as Minecraft chat colors).
    private static final int COLOR_GOLD = 6;
    private static final int COLOR_GRAY = 7;
    private static final int COLOR_BLUE = 9;
    private static final int COLOR_GREEN = 10;
    private static final int COLOR_RED = 12;
    private static final int COLOR_YELLOW = 14;
    private static final int COLOR_WHITE = 15;

    private static boolean xaeroLoaded;
    private static boolean broken;
    private static int ticker;
    private static String lastSignature = "";
    private static Class<?> waypointClass;
    private static Constructor<?> waypointCtor;
    private static Method getNameMethod;

    private XaeroWaypointBridge() {
    }

    public static void register() {
        xaeroLoaded = ModList.get().isLoaded("xaerominimap");
        if (!xaeroLoaded) {
            return;
        }
        MinecraftForge.EVENT_BUS.addListener(XaeroWaypointBridge::onClientTick);
        System.out.println("[ZoneWars] Xaero's Minimap detected: arena waypoints enabled.");
    }

    /** True when Xaero renders the map data, so the built-in HUD minimap hides itself. */
    public static boolean active() {
        return xaeroLoaded && !broken;
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || broken) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (++ticker < SYNC_INTERVAL_TICKS) {
            return;
        }
        ticker = 0;
        try {
            sync(minecraft);
        } catch (Throwable error) {
            broken = true;
            System.out.println("[ZoneWars] Xaero waypoint bridge disabled: " + error);
        }
    }

    private static void sync(Minecraft minecraft) throws Exception {
        ZoneWarsState.Snapshot snapshot = ZoneWarsState.snapshot();
        String signature = signature(minecraft, snapshot);
        if (signature.equals(lastSignature)) {
            return;
        }
        Object set = currentWaypointSet();
        if (set == null) {
            return;
        }
        ensureWaypointReflection();

        for (Object waypoint : waypointsOf(set)) {
            String name = (String) getNameMethod.invoke(waypoint);
            if (name != null && name.startsWith(PREFIX)) {
                removeFrom(set, waypoint);
            }
        }

        if (!"NONE".equals(snapshot.team())) {
            int y = minecraft.player.getBlockY();
            for (ZoneWarsState.PointState point : snapshot.points()) {
                addTo(set, createWaypoint(point.x(), y, point.z(),
                        PREFIX + displayName(point), initial(displayName(point), "P"), pointColor(point)));
            }
            for (ZoneWarsState.RespawnState respawn : snapshot.respawns()) {
                if (!snapshot.team().equals(respawn.team())) {
                    continue;
                }
                int color = respawn.available() ? respawnColor(respawn.kind()) : COLOR_GRAY;
                addTo(set, createWaypoint(respawn.x(), y, respawn.z(),
                        PREFIX + respawnName(respawn), initial(kindName(respawn.kind()), "S"), color));
            }
            for (ZoneWarsState.MarkerState marker : snapshot.markers()) {
                String label = marker.label() == null || marker.label().isBlank() ? marker.type() : marker.label();
                addTo(set, createWaypoint(marker.x(), y, marker.z(), PREFIX + label, "!", COLOR_GOLD));
            }
        }
        lastSignature = signature;
    }

    // ------------------------------------------------------------------
    // Xaero API access (old and new class layouts)
    // ------------------------------------------------------------------

    private static Object currentWaypointSet() throws Exception {
        try {
            Object session = Class.forName("xaero.common.XaeroMinimapSession")
                    .getMethod("getCurrentSession").invoke(null);
            if (session == null) {
                return null;
            }
            Object manager = call(session, "getWaypointsManager");
            Object world = call(manager, "getCurrentWorld");
            if (world == null) {
                return null;
            }
            return callFirst(world, "getCurrentSet", "getCurrentWaypointSet");
        } catch (ClassNotFoundException oldApiMissing) {
            Object module = Class.forName("xaero.hud.minimap.BuiltInHudModules")
                    .getField("MINIMAP").get(null);
            Object session = call(module, "getCurrentSession");
            if (session == null) {
                return null;
            }
            Object worldManager = call(session, "getWorldManager");
            Object world = call(worldManager, "getCurrentWorld");
            if (world == null) {
                return null;
            }
            return callFirst(world, "getCurrentWaypointSet", "getCurrentSet");
        }
    }

    private static Constructor<?> ctorOrNull(Class<?> type, Class<?>... params) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor(params);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static void ensureWaypointReflection() throws Exception {
        if (waypointCtor != null) {
            return;
        }
        Class<?> type;
        try {
            type = Class.forName("xaero.common.minimap.waypoints.Waypoint");
        } catch (ClassNotFoundException e) {
            type = Class.forName("xaero.hud.minimap.waypoint.Waypoint");
        }
        Constructor<?> ctor = ctorOrNull(type,
                int.class, int.class, int.class, String.class, String.class, int.class, int.class, boolean.class);
        if (ctor == null) {
            ctor = ctorOrNull(type,
                    int.class, int.class, int.class, String.class, String.class, int.class, int.class);
        }
        if (ctor == null) {
            ctor = ctorOrNull(type,
                    int.class, int.class, int.class, String.class, String.class, int.class);
        }
        if (ctor == null) {
            throw new NoSuchMethodException(type.getName() + " waypoint constructor");
        }
        waypointClass = type;
        waypointCtor = ctor;
        getNameMethod = type.getMethod("getName");
    }

    private static Object createWaypoint(int x, int y, int z, String name, String symbol, int color) throws Exception {
        Object waypoint = switch (waypointCtor.getParameterCount()) {
            case 8 -> waypointCtor.newInstance(x, y, z, name, symbol, color, 0, true);
            case 7 -> waypointCtor.newInstance(x, y, z, name, symbol, color, 0);
            default -> waypointCtor.newInstance(x, y, z, name, symbol, color);
        };
        try {
            waypointClass.getMethod("setTemporary", boolean.class).invoke(waypoint, true);
        } catch (NoSuchMethodException ignored) {
            // Older builds take the temporary flag in the constructor only.
        }
        return waypoint;
    }

    private static List<Object> waypointsOf(Object set) throws Exception {
        Object result = callFirst(set, "getList", "getWaypoints");
        List<Object> copy = new ArrayList<>();
        if (result instanceof Iterable<?> iterable) {
            for (Object waypoint : iterable) {
                copy.add(waypoint);
            }
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void addTo(Object set, Object waypoint) throws Exception {
        Object list = callOrNull(set, "getList");
        if (list instanceof List<?> real) {
            ((List<Object>) real).add(waypoint);
            return;
        }
        invokeSingleArg(set, "add", waypoint);
    }

    private static void removeFrom(Object set, Object waypoint) throws Exception {
        Object list = callOrNull(set, "getList");
        if (list instanceof List<?> real) {
            real.remove(waypoint);
            return;
        }
        invokeSingleArg(set, "remove", waypoint);
    }

    private static Object call(Object target, String method) throws Exception {
        Method resolved = target.getClass().getMethod(method);
        resolved.setAccessible(true);
        return resolved.invoke(target);
    }

    private static Object callOrNull(Object target, String method) {
        try {
            return call(target, method);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object callFirst(Object target, String... methods) throws Exception {
        for (String name : methods) {
            try {
                return call(target, name);
            } catch (NoSuchMethodException ignored) {
                // try the next known name
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + String.join("/", methods));
    }

    private static void invokeSingleArg(Object target, String name, Object arg) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(arg)) {
                method.setAccessible(true);
                method.invoke(target, arg);
                return;
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }

    // ------------------------------------------------------------------
    // Presentation helpers
    // ------------------------------------------------------------------

    private static String displayName(ZoneWarsState.PointState point) {
        return point.name() == null || point.name().isBlank() ? point.id() : point.name();
    }

    private static String initial(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static int pointColor(ZoneWarsState.PointState point) {
        if (point.capturingTeam() != null && !"NONE".equals(point.capturingTeam())) {
            return COLOR_YELLOW;
        }
        return switch (point.owner() == null ? "NEUTRAL" : point.owner()) {
            case "RED" -> COLOR_RED;
            case "BLUE" -> COLOR_BLUE;
            default -> COLOR_WHITE;
        };
    }

    private static String respawnName(ZoneWarsState.RespawnState respawn) {
        String kind = kindName(respawn.kind());
        return respawn.name() == null || respawn.name().isBlank() ? kind : kind + " " + respawn.name();
    }

    private static String kindName(String kind) {
        return switch (kind == null ? "" : kind.toUpperCase(Locale.ROOT)) {
            case "TENT" -> "Tent";
            case "RALLY" -> "Rally";
            case "BASE" -> "Base";
            default -> kind == null || kind.isBlank() ? "Spawn" : kind;
        };
    }

    private static int respawnColor(String kind) {
        return switch (kind == null ? "" : kind.toUpperCase(Locale.ROOT)) {
            case "RALLY" -> COLOR_GOLD;
            default -> COLOR_GREEN;
        };
    }

    private static String signature(Minecraft minecraft, ZoneWarsState.Snapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        builder.append(minecraft.level.dimension().location()).append('|').append(snapshot.team());
        for (ZoneWarsState.PointState point : snapshot.points()) {
            builder.append('|').append(point.id()).append(',').append(point.x()).append(',').append(point.z())
                    .append(',').append(point.owner()).append(',').append(point.capturingTeam());
        }
        for (ZoneWarsState.RespawnState respawn : snapshot.respawns()) {
            builder.append('|').append(respawn.kind()).append(',').append(respawn.team()).append(',')
                    .append(respawn.name()).append(',').append(respawn.x()).append(',').append(respawn.z())
                    .append(',').append(respawn.available());
        }
        for (ZoneWarsState.MarkerState marker : snapshot.markers()) {
            builder.append('|').append(marker.type()).append(',').append(marker.label()).append(',')
                    .append(marker.x()).append(',').append(marker.z());
        }
        return builder.toString();
    }
}
'@

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

$repo = (Resolve-Path $RepoPath).Path
$bridgeFile = Join-Path $repo "src/main/java/ru/zonewars/client/map/XaeroWaypointBridge.java"
$clientFile = Join-Path $repo "src/main/java/ru/zonewars/client/ZoneWarsClient.java"
$hudFile    = Join-Path $repo "src/main/java/ru/zonewars/client/ui/ZoneWarsHud.java"

if (-not (Test-Path $clientFile)) {
    throw "ZoneWarsClient.java not found. Run this script from the repo root (or pass -RepoPath)."
}

Push-Location $repo
try {
    $code = Invoke-Native "git rev-parse --is-inside-work-tree"
    if ($code -ne 0) { throw "Not a git repository: $repo" }

    if ($Branch) {
        $code = Invoke-Native "git switch -c $Branch"
        if ($code -ne 0) { $code = Invoke-Native "git switch $Branch" }
        if ($code -ne 0) { throw "Could not create or switch to branch $Branch" }
        Write-Host "[1/5] On branch $Branch" -ForegroundColor Cyan
    } else {
        Write-Host "[1/5] Staying on current branch" -ForegroundColor Cyan
    }

    # --- 1. New bridge class ---------------------------------------------------
    Set-Text $bridgeFile $BridgeJava
    Write-Host "[2/5] XaeroWaypointBridge.java written" -ForegroundColor Cyan

    # --- 2. Register the bridge in ZoneWarsClient ------------------------------
    $client = Get-Text $clientFile
    if ($client -notmatch "XaeroWaypointBridge") {
        $anchor = "ZoneWarsHud.register();"
        if (-not $client.Contains($anchor)) { throw "Anchor not found in ZoneWarsClient.java: $anchor" }
        $client = $client.Replace($anchor, $anchor + [Environment]::NewLine + "        ru.zonewars.client.map.XaeroWaypointBridge.register();")
        Set-Text $clientFile $client
        Write-Host "[3/5] ZoneWarsClient: bridge registered on client setup" -ForegroundColor Cyan
    } else {
        Write-Host "[3/5] ZoneWarsClient already patched - skipping" -ForegroundColor Yellow
    }

    # --- 3. Hide the built-in HUD minimap when Xaero is active -----------------
    $hud = Get-Text $hudFile
    if ($hud -notmatch "XaeroWaypointBridge") {
        $anchor = "drawMiniMap(graphics, client, snapshot);"
        $count = [regex]::Matches($hud, [regex]::Escape($anchor)).Count
        if ($count -ne 1) { throw "Expected exactly 1 drawMiniMap call site in ZoneWarsHud.java, found $count" }
        $hud = $hud.Replace($anchor, "if (!ru.zonewars.client.map.XaeroWaypointBridge.active()) drawMiniMap(graphics, client, snapshot);")
        Set-Text $hudFile $hud
        Write-Host "[4/5] ZoneWarsHud: built-in minimap hidden when Xaero is active" -ForegroundColor Cyan
    } else {
        Write-Host "[4/5] ZoneWarsHud already patched - skipping" -ForegroundColor Yellow
    }

    # --- 4. Build ---------------------------------------------------------------
    if ($Build) {
        Write-Host "[5/5] Building..." -ForegroundColor Cyan
        $code = Invoke-Native "gradlew.bat clean build"
        if ($code -ne 0) {
            throw "Build FAILED - nothing was committed. Backups: *.bak next to each patched file."
        }
        Write-Host "Build OK" -ForegroundColor Green
    }

    # --- 5. Commit / push --------------------------------------------------------
    Get-ChildItem -Path $repo -Recurse -Filter "*.java.bak" | Remove-Item -Force
    Invoke-Native ('git add "src/main/java/ru/zonewars/client"') | Out-Null
    $code = Invoke-Native ('git commit -m "' + $CommitMessage + '"')
    if ($code -ne 0) { throw "git commit failed (nothing to commit?)" }
    Write-Host "Committed: $CommitMessage" -ForegroundColor Green

    if ($Push) {
        if ($Branch) { $code = Invoke-Native "git push -u $Remote $Branch" } else { $code = Invoke-Native "git push" }
        if ($code -ne 0) { throw "git push failed - check your credentials/remote" }
        Write-Host "Pushed to $Remote" -ForegroundColor Green
    } else {
        Write-Host "Not pushed. To push: git push" -ForegroundColor Yellow
    }

    Write-Host ""
    Write-Host "Done. In game (with Xaero's Minimap in the mods folder):" -ForegroundColor Green
    Write-Host "  - [ZW] waypoints for points/tents/rally/pings appear on Xaero minimap + world map"
    Write-Host "  - the built-in ZoneWars MINIMAP panel disappears (Xaero replaces it)"
    Write-Host "  - without Xaero installed everything works exactly as before"
}
finally {
    Pop-Location
}
