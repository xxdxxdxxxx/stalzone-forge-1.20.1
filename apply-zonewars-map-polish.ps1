# apply-zonewars-map-polish.ps1
# 1) Replaces the built-in square MINIMAP panel with a custom round radar (top-right).
# 2) Rewrites XaeroWaypointBridge: clean waypoint names (no "[ZW]" clutter), identity-tracked cleanup,
#    and auto-opens the PDA-style deployment map when a respawn choice is pending.
# 3) Restyles ZoneMapScreen as a PDA device: frame, scanlines, boot animation.
#
# Usage (from C:\stalzone-forge):
#   powershell -ExecutionPolicy Bypass -File .\apply-zonewars-map-polish.ps1 -Build -Push

param(
    [string]$RepoPath = "C:\stalzone-forge",
    [switch]$Build,
    [switch]$Push,
    [string]$Remote = "origin",
    [string]$CommitMessage = "Round HUD radar, cleaner Xaero waypoints, PDA-style respawn map with boot animation"
)

$ErrorActionPreference = 'Stop'

function Invoke-Native {
    param([string]$Command)
    $output = & cmd /c "$Command 2>&1"
    $code = $LASTEXITCODE
    if ($output) { $output | ForEach-Object { Write-Host $_ } }
    if ($code -ne 0) { throw "Command failed ($code): $Command" }
}

function Get-Text([string]$Path) {
    if (-not (Test-Path $Path)) { throw "File not found: $Path" }
    return [System.IO.File]::ReadAllText($Path)
}

function Set-Text([string]$Path, [string]$Content) {
    if (Test-Path $Path) { Copy-Item $Path "$Path.bak" -Force }
    [System.IO.File]::WriteAllText($Path, $Content)
}

Set-Location $RepoPath

# ============================================================================
# [1/4] XaeroWaypointBridge v2 (full rewrite)
# ============================================================================
$bridgePath = "src\main\java\ru\zonewars\client\map\XaeroWaypointBridge.java"
$bridgeJava = @'
package ru.zonewars.client.map;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import ru.zonewars.client.state.ZoneWarsState;
import ru.zonewars.client.ui.ZoneMapScreen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client-side glue between ZoneWars and Xaero's maps.
 *
 * - Mirrors capture points, own-team respawns and pings into Xaero's Minimap /
 *   World Map as temporary waypoints (reflection only, no compile dependency).
 * - Opens the PDA-style deployment map automatically when the server asks the
 *   player to pick a respawn.
 */
public final class XaeroWaypointBridge {

    private static final int SYNC_INTERVAL_TICKS = 40;
    private static final String LEGACY_PREFIX = "[ZW] ";

    private static final int COLOR_RED = 12;
    private static final int COLOR_BLUE = 9;
    private static final int COLOR_WHITE = 15;
    private static final int COLOR_YELLOW = 14;
    private static final int COLOR_GREEN = 10;
    private static final int COLOR_GOLD = 6;
    private static final int COLOR_GRAY = 7;

    private static boolean xaeroLoaded;
    private static boolean broken;
    private static boolean promptHandled;
    private static int ticker;
    private static String lastSignature = "";
    private static final List<Object> ownedWaypoints = new ArrayList<>();
    private static Class<?> waypointClass;
    private static Constructor<?> waypointCtor;
    private static Method getNameMethod;

    private XaeroWaypointBridge() {
    }

    public static void register() {
        xaeroLoaded = ModList.get().isLoaded("xaerominimap");
        MinecraftForge.EVENT_BUS.addListener(XaeroWaypointBridge::onClientTick);
        if (xaeroLoaded) {
            System.out.println("[ZoneWars] Xaero's Minimap detected: arena waypoints enabled.");
        }
    }

    /** Kept for API compatibility; the HUD now always renders its own round radar. */
    public static boolean active() {
        return xaeroLoaded && !broken;
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ZoneWarsState.Snapshot snapshot = ZoneWarsState.snapshot();

        // Auto-open the PDA deployment map when a respawn choice is pending.
        if (!snapshot.respawnPrompt()) {
            promptHandled = false;
        } else if (!promptHandled && minecraft.screen == null && !"NONE".equals(snapshot.team())) {
            minecraft.setScreen(new ZoneMapScreen(true));
            promptHandled = true;
        }

        if (!xaeroLoaded || broken) {
            return;
        }
        if (++ticker < SYNC_INTERVAL_TICKS) {
            return;
        }
        ticker = 0;
        try {
            sync(minecraft, snapshot);
        } catch (Throwable error) {
            broken = true;
            System.out.println("[ZoneWars] Xaero waypoint bridge disabled: " + error);
        }
    }

    private static void sync(Minecraft minecraft, ZoneWarsState.Snapshot snapshot) throws Exception {
        Object set = currentWaypointSet();
        if (set == null) {
            return;
        }
        ensureWaypointReflection();
        String signature = buildSignature(minecraft, snapshot);
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;

        removeOwned(set);
        if ("NONE".equals(snapshot.team())) {
            return;
        }
        int y = minecraft.player.getBlockY();

        for (ZoneWarsState.PointState point : snapshot.points()) {
            String label = pointLabel(point);
            addOwned(set, point.x(), y, point.z(), label, label, pointColor(point));
        }
        for (ZoneWarsState.RespawnState respawn : snapshot.respawns()) {
            if (!snapshot.team().equals(respawn.team()) || "BASE".equals(respawn.kind())) {
                continue;
            }
            String title = respawnTitle(respawn.kind());
            int color = respawn.available()
                    ? ("TENT".equals(respawn.kind()) ? COLOR_GREEN : COLOR_GOLD)
                    : COLOR_GRAY;
            addOwned(set, respawn.x(), y, respawn.z(), title, title.substring(0, 1), color);
        }
        for (ZoneWarsState.MarkerState marker : snapshot.markers()) {
            addOwned(set, marker.x(), y, marker.z(), markerTitle(marker), "!", COLOR_GOLD);
        }
    }

    // ------------------------------------------------------------------ naming

    private static String pointLabel(ZoneWarsState.PointState point) {
        String source = !point.name().isBlank() ? point.name() : point.id();
        return source.isBlank() ? "?" : source.substring(0, 1).toUpperCase();
    }

    private static int pointColor(ZoneWarsState.PointState point) {
        String status = point.status();
        if ("CAPTURING".equals(status) || "CONTESTED".equals(status) || "NEUTRALIZING".equals(status)) {
            return COLOR_YELLOW;
        }
        return switch (point.owner()) {
            case "RED" -> COLOR_RED;
            case "BLUE" -> COLOR_BLUE;
            default -> COLOR_WHITE;
        };
    }

    private static String respawnTitle(String kind) {
        return switch (kind) {
            case "TENT" -> "Tent";
            case "OUTPOST", "RALLY" -> "Rally";
            default -> "Spawn";
        };
    }

    private static String markerTitle(ZoneWarsState.MarkerState marker) {
        return marker.label() == null || marker.label().isBlank() ? "Ping" : marker.label();
    }

    private static String buildSignature(Minecraft minecraft, ZoneWarsState.Snapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        builder.append(minecraft.level.dimension().location()).append('|').append(snapshot.team());
        for (ZoneWarsState.PointState point : snapshot.points()) {
            builder.append('|').append(point.id()).append(',').append(point.x()).append(',').append(point.z())
                    .append(',').append(point.owner()).append(',').append(point.status())
                    .append(',').append(point.capturingTeam());
        }
        for (ZoneWarsState.RespawnState respawn : snapshot.respawns()) {
            builder.append('|').append(respawn.kind()).append(',').append(respawn.x()).append(',').append(respawn.z())
                    .append(',').append(respawn.team()).append(',').append(respawn.available());
        }
        for (ZoneWarsState.MarkerState marker : snapshot.markers()) {
            builder.append('|').append(marker.type()).append(',').append(marker.x()).append(',').append(marker.z());
        }
        return builder.toString();
    }

    // -------------------------------------------------------------- set access

    private static Object currentWaypointSet() throws Exception {
        try {
            Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null) {
                return null;
            }
            Object manager = call(session, "getWaypointsManager");
            if (manager == null) {
                return null;
            }
            Object world = call(manager, "getCurrentWorld");
            if (world == null) {
                return null;
            }
            return callFirst(world, "getCurrentSet", "getCurrentWaypointSet");
        } catch (ClassNotFoundException outdated) {
            Object module = Class.forName("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
            Object session = call(module, "getCurrentSession");
            if (session == null) {
                return null;
            }
            Object worldManager = call(session, "getWorldManager");
            if (worldManager == null) {
                return null;
            }
            Object world = call(worldManager, "getCurrentWorld");
            if (world == null) {
                return null;
            }
            return callFirst(world, "getCurrentWaypointSet", "getCurrentSet");
        }
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object callFirst(Object target, String... names) throws Exception {
        for (String name : names) {
            try {
                return call(target, name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + " has none of the expected accessors");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> waypointList(Object set) {
        try {
            Object result = callFirst(set, "getList", "getWaypoints");
            if (result instanceof List) {
                return (List<Object>) result;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void addOwned(Object set, int x, int y, int z, String name, String symbol, int color) throws Exception {
        Object waypoint = createWaypoint(x, y, z, name, symbol, color);
        List<Object> list = waypointList(set);
        if (list != null) {
            list.add(waypoint);
        } else {
            invokeSingleArg(set, "add", waypoint);
        }
        ownedWaypoints.add(waypoint);
    }

    private static void removeOwned(Object set) throws Exception {
        List<Object> list = waypointList(set);
        if (list != null) {
            list.removeAll(ownedWaypoints);
            if (getNameMethod != null) {
                Iterator<Object> iterator = list.iterator();
                while (iterator.hasNext()) {
                    Object existing = iterator.next();
                    Object name = existing == null ? null : getNameMethod.invoke(existing);
                    if (name instanceof String value && value.startsWith(LEGACY_PREFIX)) {
                        iterator.remove();
                    }
                }
            }
        } else {
            for (Object waypoint : ownedWaypoints) {
                try {
                    invokeSingleArg(set, "remove", waypoint);
                } catch (Exception ignored) {
                }
            }
        }
        ownedWaypoints.clear();
    }

    private static void invokeSingleArg(Object target, String nameFragment, Object argument) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterCount() == 1 && method.getName().toLowerCase().contains(nameFragment)) {
                method.setAccessible(true);
                method.invoke(target, argument);
                return;
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + " has no single-arg '" + nameFragment + "' method");
    }

    // -------------------------------------------------------- waypoint factory

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
}
'@
Set-Text $bridgePath $bridgeJava
Write-Host "[1/4] XaeroWaypointBridge rewritten (clean names, respawn auto-open)"

# ============================================================================
# [2/4] ZoneWarsHud: round radar instead of the square MINIMAP panel
# ============================================================================
$hudPath = "src\main\java\ru\zonewars\client\ui\ZoneWarsHud.java"
$hud = Get-Text $hudPath
if ($hud.Contains("drawRoundMiniMap")) {
    Write-Host "[2/4] ZoneWarsHud already patched - skipping"
} else {
    $callPattern = '(?:if \(!ru\.zonewars\.client\.map\.XaeroWaypointBridge\.active\(\)\)\s*)?drawMiniMap\(graphics, client, snapshot\);'
    $callRegex = [regex]$callPattern
    if (-not $callRegex.IsMatch($hud)) { throw "ZoneWarsHud: minimap call site not found" }
    $hud = $callRegex.Replace($hud, 'drawRoundMiniMap(graphics, client, snapshot);', 1)

    $hudMethods = @'

    // ------------------------------------------------------------ round radar

    private static void drawRoundMiniMap(GuiGraphics graphics, Minecraft client, ZoneWarsState.Snapshot snapshot) {
        int radius = 56;
        int cx = graphics.guiWidth() - radius - 16;
        int cy = radius + 40;
        double px = client.player.getX();
        double pz = client.player.getZ();
        double range = 110.0;

        // Circular backdrop drawn as horizontal strips.
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.floor(Math.sqrt((double) radius * radius - (double) dy * dy));
            graphics.fill(cx - half, cy + dy, cx + half, cy + dy + 1, 0xD20D1410);
        }
        // Inner range ring + crosshair.
        drawRing(graphics, cx, cy, radius - 21, 0x2ECAD7C2);
        graphics.fill(cx - radius + 6, cy, cx + radius - 6, cy + 1, 0x1CCAD7C2);
        graphics.fill(cx, cy - radius + 6, cx + 1, cy + radius - 6, 0x1CCAD7C2);
        // Rim.
        drawRing(graphics, cx, cy, radius, 0xFF222B21);
        drawRing(graphics, cx, cy, radius - 1, 0xFF71835C);
        graphics.drawCenteredString(client.font, "N", cx, cy - radius + 5, TEXT);

        for (ZoneWarsState.RespawnState respawn : snapshot.respawns()) {
            if (!snapshot.team().equals(respawn.team())) {
                continue;
            }
            int[] pos = radarPos(cx, cy, radius, range, px, pz, respawn.x(), respawn.z());
            int color = respawn.available() ? spawnColor(respawn.kind(), respawn.team()) : 0xFF606B73;
            graphics.fill(pos[0] - 2, pos[1] - 2, pos[0] + 3, pos[1] + 3, 0xFF0B1117);
            graphics.renderOutline(pos[0] - 2, pos[1] - 2, 5, 5, color);
        }
        for (ZoneWarsState.MarkerState marker : snapshot.markers()) {
            int[] pos = radarPos(cx, cy, radius, range, px, pz, marker.x(), marker.z());
            graphics.drawCenteredString(client.font, markerLabel(marker.type()), pos[0], pos[1] - 4, markerColor(marker.type()));
        }
        for (ZoneWarsState.PlayerState player : snapshot.players()) {
            if (player.self() || !player.squad()) {
                continue;
            }
            int[] pos = radarPos(cx, cy, radius, range, px, pz, player.x(), player.z());
            graphics.fill(pos[0] - 1, pos[1] - 1, pos[0] + 2, pos[1] + 2, GREEN);
        }
        for (ZoneWarsState.PointState point : snapshot.points()) {
            int[] pos = radarPos(cx, cy, radius, range, px, pz, point.x(), point.z());
            int color = pointColor(point);
            if (pos[2] == 1) {
                // Out of range: small chevron pinned to the rim.
                graphics.fill(pos[0] - 2, pos[1] - 2, pos[0] + 3, pos[1] + 3, color);
            } else {
                graphics.fill(pos[0] - 5, pos[1] - 5, pos[0] + 5, pos[1] + 5, 0xD90B1117);
                graphics.renderOutline(pos[0] - 5, pos[1] - 5, 10, 10, color);
                graphics.drawCenteredString(client.font, pointLabel(point), pos[0], pos[1] - 4, TEXT);
            }
        }

        graphics.drawCenteredString(client.font, radarArrow(snapshot.selfYaw()), cx, cy - 4, TEXT);
        graphics.drawCenteredString(client.font, (int) px + " " + (int) pz, cx, cy + radius + 5, MUTED);
    }

    private static int[] radarPos(int cx, int cy, int radius, double range, double px, double pz, int x, int z) {
        double dx = x - px;
        double dz = z - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        int clamped = 0;
        if (dist > range) {
            dx = dx / dist * range;
            dz = dz / dist * range;
            clamped = 1;
        }
        double scale = (radius - 7) / range;
        return new int[] { cx + (int) Math.round(dx * scale), cy + (int) Math.round(dz * scale), clamped };
    }

    private static void drawRing(GuiGraphics graphics, int cx, int cy, int r, int color) {
        int steps = Math.max(60, r * 7);
        for (int i = 0; i < steps; i++) {
            double angle = Math.PI * 2.0 * i / steps;
            int x = cx + (int) Math.round(Math.cos(angle) * r);
            int y = cy + (int) Math.round(Math.sin(angle) * r);
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static String radarArrow(int yaw) {
        int normalized = ((yaw % 360) + 360) % 360;
        if (normalized >= 45 && normalized < 135) {
            return "<";
        }
        if (normalized >= 135 && normalized < 225) {
            return "^";
        }
        if (normalized >= 225 && normalized < 315) {
            return ">";
        }
        return "v";
    }
'@

    $braceIndex = $hud.LastIndexOf('}')
    if ($braceIndex -lt 0) { throw "ZoneWarsHud: closing brace not found" }
    $hud = $hud.Substring(0, $braceIndex) + $hudMethods + "`n}`n"
    Set-Text $hudPath $hud
    Write-Host "[2/4] ZoneWarsHud: round radar installed (top-right)"
}

# ============================================================================
# [3/4] ZoneMapScreen: PDA frame, scanlines, boot animation
# ============================================================================
$screenPath = "src\main\java\ru\zonewars\client\ui\ZoneMapScreen.java"
$screen = Get-Text $screenPath
if ($screen.Contains("drawPdaOverlay")) {
    Write-Host "[3/4] ZoneMapScreen already patched - skipping"
} else {
    if (-not $screen.Contains("private long selectionPulseStarted;")) { throw "ZoneMapScreen: field anchor not found" }
    $screen = $screen.Replace(
        "private long selectionPulseStarted;",
        "private long selectionPulseStarted;`n    private final long openedAtMillis = System.currentTimeMillis();")

    $overlayCallRegex = [regex]'(if \(respawnMode\) \{\s*drawRespawnControls\(graphics, snapshot, mouseX, mouseY\);\s*\})'
    if (-not $overlayCallRegex.IsMatch($screen)) { throw "ZoneMapScreen: render() anchor not found" }
    $overlayReplacement = '$1' + "`n        drawPdaOverlay(graphics);"
    $screen = $overlayCallRegex.Replace($screen, $overlayReplacement, 1)

    $screenMethods = @'

    private void drawPdaOverlay(GuiGraphics graphics) {
        int w = this.width;
        int h = this.height;
        long now = System.currentTimeMillis();
        long age = now - openedAtMillis;

        // CRT scanlines.
        for (int y = 0; y < h; y += 3) {
            graphics.fill(0, y, w, y + 1, 0x0810130E);
        }
        // Phosphor-green vignette.
        graphics.fill(0, 0, w, 14, 0x3306140B);
        graphics.fill(0, h - 14, w, h, 0x3306140B);
        graphics.fill(0, 0, 14, h, 0x3306140B);
        graphics.fill(w - 14, 0, w, h, 0x3306140B);

        // Rugged device frame.
        int frame = 6;
        int frameColor = 0xFF3A4433;
        int frameLight = 0xFF57644B;
        int frameDark = 0xFF242B1F;
        graphics.fill(0, 0, w, frame, frameColor);
        graphics.fill(0, h - frame, w, h, frameColor);
        graphics.fill(0, 0, frame, h, frameColor);
        graphics.fill(w - frame, 0, w, h, frameColor);
        graphics.fill(0, frame, w, frame + 1, frameLight);
        graphics.fill(0, h - frame - 1, w, h - frame, frameDark);

        // Corner plates with screws.
        int plate = 18;
        int[][] corners = { { 0, 0 }, { w - plate, 0 }, { 0, h - plate }, { w - plate, h - plate } };
        for (int[] corner : corners) {
            graphics.fill(corner[0], corner[1], corner[0] + plate, corner[1] + plate, frameColor);
            graphics.renderOutline(corner[0], corner[1], plate, plate, frameDark);
            graphics.fill(corner[0] + 7, corner[1] + 7, corner[0] + 11, corner[1] + 11, frameDark);
            graphics.fill(corner[0] + 8, corner[1] + 8, corner[0] + 10, corner[1] + 10, 0xFF6E7C5D);
        }

        // Status line, bottom-right.
        String status = respawnMode ? "PDA // DEPLOYMENT UPLINK" : "PDA // TACTICAL MAP";
        int statusWidth = this.font.width(status);
        graphics.drawString(this.font, status, w - statusWidth - 26, h - 17, 0xFF87A06B, false);
        if ((now / 500) % 2 == 0) {
            graphics.fill(w - 22, h - 16, w - 16, h - 10, 0xFF59D979);
        }

        // Boot animation: flicker, fade from black, sweep line.
        if (age < 420) {
            float t = age / 420.0f;
            int alpha = (int) ((1.0f - t) * 235.0f);
            if (age < 160 && (age / 40) % 2 == 0) {
                alpha = Math.min(255, alpha + 40);
            }
            graphics.fill(0, 0, w, h, (alpha << 24) | 0x000A0E08);
            int sweepY = (int) (t * h);
            graphics.fill(0, Math.max(0, sweepY - 2), w, Math.min(h, sweepY + 2), 0x5578A05C);
        }
    }
'@

    $braceIndex = $screen.LastIndexOf('}')
    if ($braceIndex -lt 0) { throw "ZoneMapScreen: closing brace not found" }
    $screen = $screen.Substring(0, $braceIndex) + $screenMethods + "`n}`n"
    Set-Text $screenPath $screen
    Write-Host "[3/4] ZoneMapScreen: PDA frame + boot animation installed"
}

# ============================================================================
# [4/4] Build, commit, push
# ============================================================================
if ($Build) {
    Write-Host "[4/4] Building..."
    try {
        Invoke-Native ".\gradlew.bat --no-daemon clean build"
        Write-Host "Build OK"
    } catch {
        throw "Build FAILED - nothing was committed. Backups: *.bak next to each patched file."
    }
} else {
    Write-Host "[4/4] Skipping build (no -Build flag)"
}

Invoke-Native "git add -A"
$pending = & cmd /c "git status --porcelain 2>&1"
if ($pending) {
    Invoke-Native "git commit -m `"$CommitMessage`""
    Write-Host "Committed: $CommitMessage"
} else {
    Write-Host "Nothing to commit"
}

if ($Push) {
    Invoke-Native "git push $Remote HEAD"
    Write-Host "Pushed to $Remote"
} else {
    Write-Host "Skipping push (no -Push flag)"
}

Write-Host ""
Write-Host "Done. What changed:"
Write-Host " - Round radar (top-right): points A/B/C, own tent/rally, squad mates, pings; coords under it"
Write-Host " - Xaero waypoints now have clean names (A/B/C, Tent, Rally, Ping)"
Write-Host " - On death the PDA-style deployment map opens automatically (frame, scanlines, boot animation)"
Write-Host " - Tip: disable Xaero's own minimap in its settings so only the round radar is shown"
