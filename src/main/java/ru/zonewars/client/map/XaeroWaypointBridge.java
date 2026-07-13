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