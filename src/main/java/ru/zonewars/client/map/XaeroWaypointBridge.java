package ru.zonewars.client.map;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import ru.zonewars.client.state.ZoneWarsState;

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

    private static volatile long deployHoldUntil;

    /** Pause auto-reopening of the deployment map right after DEPLOY is pressed. */
    public static void markDeploying() {
        deployHoldUntil = System.currentTimeMillis() + 4000L;
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
        } else if (!promptHandled && System.currentTimeMillis() >= deployHoldUntil && minecraft.screen == null && !"NONE".equals(snapshot.team())) {
            ru.zonewars.client.map.CampChatMapOverlay.openDeployment(minecraft);
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
            if (!snapshot.team().equals(respawn.team())) {
                continue;
            }
            String title = respawnTitle(respawn.kind());
            int color = !respawn.available() ? COLOR_GRAY : ("BASE".equals(respawn.kind()) ? ("RED".equals(respawn.team()) ? COLOR_RED : COLOR_BLUE) : ("TENT".equals(respawn.kind()) ? COLOR_GREEN : COLOR_GOLD));
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
            default -> "Base";
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