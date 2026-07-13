package ru.zonewars.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import ru.zonewars.client.state.ZoneWarsState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Draws ZoneWars tactical markers on top of the campchat PDA "Map" tab.
 *
 * campchat (ZCraft STALKER PDA) by kltyton - used with the author's permission,
 * non-commercial. Pure reflection: no compile-time dependency on campchat or
 * Xaero's World Map, and nothing inside the campchat jar is modified.
 *
 * If the embedded Xaero map is present, markers are anchored to its camera
 * (cameraX / cameraZ / scale). Otherwise a self-contained tactical grid is
 * rendered inside the map panel so the PDA map tab is never empty.
 */
public final class CampChatMapOverlay {
    private static final String SCREEN_CLASS = "com.kltyton.campchat.client.gui.CampChatScreen";

    private static final int RED = 0xFFE54855;
    private static final int BLUE = 0xFF51A7FF;
    private static final int GREEN = 0xFF59D979;
    private static final int YELLOW = 0xFFE7CA60;
    private static final int NEUTRAL = 0xFFE7E0C1;
    private static final int TEXT = 0xFFE9EEF5;
    private static final int ACCENT = 0xFF7CE28A;
    private static final int DISABLED = 0xFF8A93A0;

    private static boolean broken;
    private static boolean cameraBroken;
    private static Field activeTabField;
    private static Field mapPanelField;
    private static Field embeddedMapField;
    private static Method rectX;
    private static Method rectY;
    private static Method rectW;
    private static Method rectH;
    private static Field cameraXField;
    private static Field cameraZField;
    private static Field scaleField;

    private CampChatMapOverlay() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(CampChatMapOverlay::onScreenRender);
    }

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        if (broken) {
            return;
        }
        Screen screen = event.getScreen();
        if (screen == null || !SCREEN_CLASS.equals(screen.getClass().getName())) {
            return;
        }
        try {
            render(event.getGuiGraphics(), screen);
        } catch (Throwable t) {
            broken = true;
        }
    }

    private static void render(GuiGraphics graphics, Screen screen) throws Exception {
        ensureScreenReflection(screen.getClass());
        Object tab = activeTabField.get(screen);
        if (!(tab instanceof Enum<?> tabEnum) || !"MAP".equals(tabEnum.name())) {
            return;
        }
        Object panel = mapPanelField.get(screen);
        if (panel == null) {
            return;
        }
        ensureRectReflection(panel.getClass());
        int px = ((Number) rectX.invoke(panel)).intValue();
        int py = ((Number) rectY.invoke(panel)).intValue();
        int pw = ((Number) rectW.invoke(panel)).intValue();
        int ph = ((Number) rectH.invoke(panel)).intValue();
        if (pw <= 20 || ph <= 20) {
            return;
        }

        ZoneWarsState.Snapshot snapshot = ZoneWarsState.snapshot();
        if (snapshot == null || "NONE".equals(snapshot.team())) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Object guiMap = embeddedMapField.get(screen);

        boolean anchored = false;
        graphics.enableScissor(px, py, px + pw, py + ph);
        try {
            if (guiMap != null && ensureCameraReflection(guiMap.getClass())) {
                double cameraX = ((Number) cameraXField.get(guiMap)).doubleValue();
                double cameraZ = ((Number) cameraZField.get(guiMap)).doubleValue();
                double scale = ((Number) scaleField.get(guiMap)).doubleValue();
                // Xaero's GuiMap scale is expressed in raw framebuffer pixels per
                // block, while GuiGraphics works in gui-scaled pixels. Without this
                // correction markers drift away from their true positions.
                double uiScale = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
                if (uiScale > 0.0001) {
                    scale /= uiScale;
                }
                if (scale > 0.0001) {
                    drawMarkers(graphics, minecraft, snapshot,
                            new WorldTransform(px + pw / 2.0, py + ph / 2.0, cameraX, cameraZ, scale));
                    anchored = true;
                }
            }
            if (!anchored) {
                drawFallback(graphics, minecraft, snapshot, px, py, pw, ph);
            }
        } finally {
            graphics.disableScissor();
        }
        drawHeader(graphics, minecraft, snapshot, px, py, anchored);
    }

    private static void drawMarkers(GuiGraphics graphics, Minecraft minecraft,
                                    ZoneWarsState.Snapshot snapshot, WorldTransform transform) {
        Font font = minecraft.font;
        for (ZoneWarsState.PointState point : snapshot.points()) {
            drawChip(graphics, font, transform.x(point.x()), transform.y(point.z()),
                    pointColor(point), initial(displayName(point)));
        }
        for (ZoneWarsState.RespawnState respawn : snapshot.respawns()) {
            if (!snapshot.team().equals(respawn.team())) {
                continue;
            }
            int color = respawn.available() ? respawnColor(respawn.kind(), respawn.team()) : DISABLED;
            drawChip(graphics, font, transform.x(respawn.x()), transform.y(respawn.z()),
                    color, initial(kindLabel(respawn.kind())));
        }
        for (ZoneWarsState.MarkerState marker : snapshot.markers()) {
            drawChip(graphics, font, transform.x(marker.x()), transform.y(marker.z()),
                    markerColor(marker.type()), markerLabel(marker.type()));
        }
        for (ZoneWarsState.PlayerState player : snapshot.players()) {
            if (player.self()) {
                continue;
            }
            int x = transform.x(player.x());
            int y = transform.y(player.z());
            graphics.fill(x - 2, y - 2, x + 2, y + 2, 0xFF101614);
            graphics.fill(x - 1, y - 1, x + 1, y + 1, GREEN);
        }
        if (minecraft.player != null) {
            int x = transform.x(minecraft.player.getX());
            int y = transform.y(minecraft.player.getZ());
            graphics.fill(x - 3, y - 3, x + 3, y + 3, 0xFF101614);
            graphics.fill(x - 2, y - 2, x + 2, y + 2, 0xFFFFFFFF);
            graphics.drawCenteredString(font, arrow(snapshot.selfYaw()), x, y - 12, TEXT);
        }
    }

    private static void drawFallback(GuiGraphics graphics, Minecraft minecraft,
                                     ZoneWarsState.Snapshot snapshot, int px, int py, int pw, int ph) {
        graphics.fill(px, py, px + pw, py + ph, 0xE60D1210);
        for (int gx = px; gx <= px + pw; gx += 48) {
            graphics.fill(gx, py, gx + 1, py + ph, 0x2259D979);
        }
        for (int gy = py; gy <= py + ph; gy += 48) {
            graphics.fill(px, gy, px + pw, gy + 1, 0x2259D979);
        }
        ZoneWarsState.Bounds bounds = snapshot.bounds();
        if (bounds == null || bounds.width() <= 0 || bounds.depth() <= 0) {
            return;
        }
        int pad = 18;
        double scaleX = (pw - pad * 2) / (double) bounds.width();
        double scaleZ = (ph - pad * 2) / (double) bounds.depth();
        double scale = Math.min(scaleX, scaleZ);
        double centerWorldX = (bounds.minX() + bounds.maxX()) / 2.0;
        double centerWorldZ = (bounds.minZ() + bounds.maxZ()) / 2.0;
        drawMarkers(graphics, minecraft, snapshot,
                new WorldTransform(px + pw / 2.0, py + ph / 2.0, centerWorldX, centerWorldZ, scale));
    }

    private static void drawHeader(GuiGraphics graphics, Minecraft minecraft,
                                   ZoneWarsState.Snapshot snapshot, int px, int py, boolean anchored) {
        String text = "ZW UPLINK // " + snapshot.phase() + (anchored ? "" : " // TACTICAL GRID");
        int width = minecraft.font.width(text);
        graphics.fill(px + 4, py + 4, px + 10 + width, py + 16, 0xB00D1210);
        graphics.drawString(minecraft.font, text, px + 7, py + 6, ACCENT);
    }

    private static void drawChip(GuiGraphics graphics, Font font, int x, int y, int color, String label) {
        graphics.fill(x - 6, y - 6, x + 6, y + 6, 0xF0101614);
        graphics.fill(x - 5, y - 5, x + 5, y + 5, color);
        graphics.fill(x - 4, y - 4, x + 4, y + 4, 0xE0141A17);
        graphics.drawCenteredString(font, label, x, y - 4, color);
    }

    private static String arrow(int yaw) {
        int dir = Math.floorMod(Math.round((yaw + 45) / 90.0f), 4);
        return switch (dir) {
            case 0 -> "v";
            case 1 -> "<";
            case 2 -> "^";
            default -> ">";
        };
    }

    private static String displayName(ZoneWarsState.PointState point) {
        return point.name() == null || point.name().isBlank() ? point.id() : point.name();
    }

    private static String initial(String value) {
        return value == null || value.isBlank() ? "?" : value.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static String kindLabel(String kind) {
        String normalized = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        if ("TENT".equals(normalized)) {
            return "Tent";
        }
        if ("OUTPOST".equals(normalized)) {
            return "Rally";
        }
        return "Base";
    }

    private static int respawnColor(String kind, String team) {
        String normalized = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        if ("TENT".equals(normalized)) {
            return GREEN;
        }
        if ("OUTPOST".equals(normalized)) {
            return YELLOW;
        }
        return teamColor(team);
    }

    private static int pointColor(ZoneWarsState.PointState point) {
        String status = point.status() == null ? "" : point.status().toUpperCase(Locale.ROOT);
        if ("CONTESTED".equals(status) || "NEUTRALIZING".equals(status)) {
            return YELLOW;
        }
        if ("CAPTURING".equals(status)) {
            return teamColor(point.capturingTeam());
        }
        return teamColor(point.owner());
    }

    private static int markerColor(String type) {
        String normalized = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if ("ATTACK".equals(normalized)) {
            return RED;
        }
        if ("DEFEND".equals(normalized)) {
            return GREEN;
        }
        if ("WAYPOINT".equals(normalized)) {
            return BLUE;
        }
        return YELLOW;
    }

    private static String markerLabel(String type) {
        String normalized = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if ("ATTACK".equals(normalized)) {
            return "A";
        }
        if ("DEFEND".equals(normalized)) {
            return "D";
        }
        if ("WAYPOINT".equals(normalized)) {
            return "W";
        }
        return "!";
    }

    private static int teamColor(String team) {
        if ("RED".equals(team)) {
            return RED;
        }
        if ("BLUE".equals(team)) {
            return BLUE;
        }
        return NEUTRAL;
    }

    private static void ensureScreenReflection(Class<?> screenClass) throws Exception {
        if (activeTabField != null) {
            return;
        }
        activeTabField = screenClass.getDeclaredField("activeTab");
        activeTabField.setAccessible(true);
        mapPanelField = screenClass.getDeclaredField("mapPanel");
        mapPanelField.setAccessible(true);
        embeddedMapField = screenClass.getDeclaredField("embeddedMap");
        embeddedMapField.setAccessible(true);
    }

    private static void ensureRectReflection(Class<?> rectClass) throws Exception {
        if (rectX != null) {
            return;
        }
        rectX = rectClass.getDeclaredMethod("x");
        rectY = rectClass.getDeclaredMethod("y");
        rectW = rectClass.getDeclaredMethod("w");
        rectH = rectClass.getDeclaredMethod("h");
        rectX.setAccessible(true);
        rectY.setAccessible(true);
        rectW.setAccessible(true);
        rectH.setAccessible(true);
    }

    private static boolean ensureCameraReflection(Class<?> mapClass) {
        if (cameraXField != null && cameraZField != null && scaleField != null) {
            return true;
        }
        if (cameraBroken) {
            return false;
        }
        cameraXField = findField(mapClass, "cameraX");
        cameraZField = findField(mapClass, "cameraZ");
        scaleField = findField(mapClass, "scale");
        if (cameraXField == null || cameraZField == null || scaleField == null) {
            cameraBroken = true;
            return false;
        }
        return true;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // keep walking up the hierarchy
            }
        }
        return null;
    }

    private record WorldTransform(double centerX, double centerY, double cameraX, double cameraZ, double scale) {
        int x(double worldX) {
            return (int) Math.round(centerX + (worldX - cameraX) * scale);
        }

        int y(double worldZ) {
            return (int) Math.round(centerY + (worldZ - cameraZ) * scale);
        }
    }
}