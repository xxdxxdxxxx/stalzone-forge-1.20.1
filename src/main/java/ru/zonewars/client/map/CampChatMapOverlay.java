package ru.zonewars.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import ru.zonewars.client.net.ZoneWarsNetworking;
import ru.zonewars.client.state.ZoneWarsState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ZoneWars integration with the campchat P
DA (ZCraft STALKER PDA) "Map" tab.
 *
 * campchat by kltyton - used with the author's permission, non-commercial.
 * Pure reflection: no compile-time dependency on campchat or Xaero's World
 * Map, and nothing inside the campchat jar is modified.
 *
 * v2: besides drawing markers, this class hosts the full deployment
 * (respawn) flow inside the PDA: spawn cards, DEPLOY button, hotkeys 1-3
 * and ENTER, plus tactical pings (Shift+LMB waypoint, Ctrl+LMB attack,
 * Ctrl+RMB danger). It can also open the PDA programmatically (M key and
 * on death), with respawn icons drawn directly on the map when campchat is
 * missing.
 */
public final class CampChatMapOverlay {
    private static final String SCREEN_CLASS = "com.kltyton.campchat.client.gui.CampChatScreen";
 private static final ResourceLocation ZW_ICON_BASE = new ResourceLocation("zonewars", "textures/gui/map/base.png");
 private static final ResourceLocation ZW_ICON_TENT = new ResourceLocation("zonewars", "textures/gui/map/tent.png");
 private static final ResourceLocation ZW_ICON_OUTPOST = new ResourceLocation("zonewars", "textures/gui/map/outpost.png");

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

    private static long lastDeployAt;
    private static boolean respawnUiActive;
    private static final List<int[]> cardRects = new ArrayList<>();
    private static final List<String> cardKinds = new ArrayList<>();
    private static int[] deployRect;
    private static int deployOpenAttempts;
    private static long lastLogAt;
    private static boolean transformValid;
    private static double lastCenterX;
    private static double lastCenterY;
    private static double lastCameraX;
    private static double lastCameraZ;
    private static double lastScale;
    private static int lastPanelX;
    private static int lastPanelY;
    private static int lastPanelW;
    private static int lastPanelH;

    private CampChatMapOverlay() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(CampChatMapOverlay::onScreenRender);
        MinecraftForge.EVENT_BUS.addListener(CampChatMapOverlay::onMousePressed);
 MinecraftForge.EVENT_BUS.addListener(CampChatMapOverlay::onMouseDragged);
        MinecraftForge.EVENT_BUS.addListener(CampChatMapOverlay::onKeyPressed);
    }

    // ------------------------------------------------------------ open PDA

    /** Opens the campchat PDA on the Map tab. Returns false if campchat is unavailable. */
    public static boolean openPda(Minecraft minecraft) {
        try {
            Class screenClass = Class.forName(SCREEN_CLASS);
            Screen screen = (Screen) screenClass.getDeclaredConstructor().newInstance();
            minecraft.setScreen(screen);
            try {
                ensureScreenReflection(screenClass);
                Class tabClass = Class.forName(SCREEN_CLASS + "$MainTab");
                Object mapTab = null;
                for (Object constant : tabClass.getEnumConstants()) {
                    if (constant instanceof Enum tabEnum && "MAP".equals(tabEnum.name())) {
                        mapTab = constant;
                    }
                }
                if (mapTab != null) {
                    activeTabField.set(screen, mapTab);
                }
            } catch (Throwable ignored) {
                // PDA still opens, just not forced onto the Map tab.
            }
            return true;
        } catch (Throwable t) {
            logOnce("PDA open failed: " + t);
            return false;
        }
    }

    /** Death flow: respawn selection lives on the campchat PDA map now. */
    public static void openDeployment(Minecraft minecraft) {
        if (System.currentTimeMillis() - lastDeployAt < 2500L) {
            return;
        }
        if (minecraft.screen != null && SCREEN_CLASS.equals(minecraft.screen.getClass().getName())) {
            return;
        }
        if (minecraft.player != null && minecraft.player.isAlive()) {
            deployOpenAttempts = 0;
        } else if (deployOpenAttempts > 40) {
            // campchat keeps rejecting the screen: leave the vanilla death screen usable.
            return;
        } else {
            deployOpenAttempts++;
        }
        openPda(minecraft);
    }

    private static void logOnce(String message) {
        long now = System.currentTimeMillis();
        if (now - lastLogAt > 5000L) {
            lastLogAt = now;
            System.out.println("[ZoneWars] " + message);
        }
    }

    // ------------------------------------------------------------- render

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        if (broken) {
            return;
        }
        Screen screen = event.getScreen();
        if (screen == null || !SCREEN_CLASS.equals(screen.getClass().getName())) {
            return;
        }
        respawnUiActive = false;
        deployRect = null;
        cardRects.clear();
        cardKinds.clear();
        transformValid = false;
        try {
            render(event.getGuiGraphics(), screen);
        } catch (Throwable t) {
            broken = true;
        }
    }

    private static void render(GuiGraphics graphics, Screen screen) throws Exception {
        ensureScreenReflection(screen.getClass());
        Object tab = activeTabField.get(screen);
        if (!(tab instanceof Enum tabEnum) || !"MAP".equals(tabEnum.name())) {
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

        lastPanelX = px;
        lastPanelY = py;
        lastPanelW = pw;
        lastPanelH = ph;

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
                double uiScale = minecraft.getWindow().getGuiScale();
                if (uiScale > 0.0001) {
                    scale /= uiScale;
                }
                if (scale > 0.0001) {
                    drawMarkers(graphics, minecraft, snapshot,
                            new WorldTransform(px + pw / 2.0, py + ph / 2.0, cameraX, cameraZ, scale));
                    anchored = true;
                    lastCenterX = px + pw / 2.0;
                    lastCenterY = py + ph / 2.0;
                    lastCameraX = cameraX;
                    lastCameraZ = cameraZ;
                    lastScale = scale;
                    transformValid = true;
                }
            }
            if (!anchored) {
                drawFallback(graphics, minecraft, snapshot, px, py, pw, ph);
            }
        } finally {
            graphics.disableScissor();
        }
        drawHeader(graphics, minecraft, snapshot, px, py, anchored);
        drawDeploymentPanel(graphics, minecraft, snapshot, px, py, pw, ph);
        respawnUiActive = true;
    }

       private static void drawMarkers(GuiGraphics graphics, Minecraft minecraft,
 ZoneWarsState.Snapshot snapshot, WorldTransform transform) {
 Font font = minecraft.font;
 for (ZoneWarsState.PointState point : snapshot.points()) {
 drawChip(graphics, font, transform.x(point.x()), transform.y(point.z()),
 pointColor(point), initial(displayName(point)));
 }
 for (ZoneWarsState.PlayerState player : snapshot.players()) {
 if (player.self()) continue;
 int x = transform.x(player.x());
 int y = transform.y(player.z());
 graphics.fill(x - 3, y - 3, x + 3, y + 3, 0xD0101614);
 graphics.fill(x - 1, y - 1, x + 2, y + 2, teamColor(player.team()));
 }
 }

 private static void drawPlayerMarker(GuiGraphics graphics, int x, int y, float yaw) {
 // Covers Xaero's default red player arrow inside the embedded PDA map.
 graphics.fill(x - 8, y - 8, x + 9, y + 9, 0xD810171A);
 graphics.renderOutline(x - 8, y - 8, 17, 17, 0x7849C8F2);
 graphics.pose().pushPose();
 graphics.pose().translate(x, y, 0.0f);
 graphics.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(yaw + 180.0f));
 int cyan = 0xFF49C8F2;
 graphics.fill(-1, -6, 2, -2, cyan);
 graphics.fill(-3, -3, 4, 1, cyan);
 graphics.fill(-5, 0, -1, 3, cyan);
 graphics.fill(1, 0, 5, 3, cyan);
 graphics.pose().popPose();
 }

    // ------------------------------------------------- deployment (respawn)

     private static void drawDeploymentPanel(GuiGraphics graphics, Minecraft minecraft,
 ZoneWarsState.Snapshot snapshot, int px, int py, int pw, int ph) {
 Font font = minecraft.font;
 long now = System.currentTimeMillis();
 double mouseX = minecraft.mouseHandler.xpos() * (double) minecraft.getWindow().getGuiScaledWidth()
 / (double) Math.max(1, minecraft.getWindow().getScreenWidth());
 double mouseY = minecraft.mouseHandler.ypos() * (double) minecraft.getWindow().getGuiScaledHeight()
 / (double) Math.max(1, minecraft.getWindow().getScreenHeight());
 if (transformValid && lastScale > 0.0001) {
 int index = 0;
 for (ZoneWarsState.RespawnState respawn : snapshot.respawns()) {
 if (!snapshot.team().equals(respawn.team()) || index >= 5) { continue; }
 int x = (int) Math.round(lastCenterX + (respawn.x() - lastCameraX) * lastScale);
 int y = (int) Math.round(lastCenterY + (respawn.z() - lastCameraZ) * lastScale);
 x = Math.max(px + 20, Math.min(px + pw - 20, x));
 y = Math.max(py + 34, Math.min(py + ph - 30, y));
 boolean selected = respawn.kind() != null && respawn.kind().equals(snapshot.selectedRespawn());
 boolean hovered = mouseX >= x - 15 && mouseX <= x + 15 && mouseY >= y - 15 && mouseY <= y + 15;
 drawDeployIcon(graphics, font, x, y, respawn, selected, hovered, snapshot.respawnPrompt(), index + 1, now);
 cardRects.add(new int[] { x - 15, y - 15, x + 15, y + 15 });
 cardKinds.add(respawn.kind());
 index++;
 }
 }
 if (!snapshot.respawnPrompt()) { deployRect = null; return; }
 int barW = Math.max(170, Math.min(230, pw / 3));
 int x2 = px + pw - 8; int x1 = x2 - barW;
 int y1 = py + ph - 30; int y2 = py + ph - 8;
 String kind = snapshot.selectedRespawn();
 boolean hasSelection = kind != null && !kind.isBlank();
 graphics.fill(x1, y1, x2, y2, hasSelection ? 0xF0173A26 : 0xE0101613);
 int barOutline = 0xFF39424B;
 if (hasSelection) {
 int alpha = Math.max(120, Math.min(255, (int) (188.0 + 67.0 * Math.sin((now % 1200L) / 1200.0 * Math.PI * 2.0))));
 barOutline = (alpha << 24) | (ACCENT & 0xFFFFFF);
 }
 graphics.renderOutline(x1, y1, barW, y2 - y1, barOutline);
 String label = hasSelection ? "DEPLOY: " + kindTitle(kind) + " [ENTER]" : "SELECT A SPAWN POINT";
 graphics.drawCenteredString(font, label, (x1 + x2) / 2, y1 + 7, hasSelection ? ACCENT : DISABLED);
 deployRect = hasSelection ? new int[] { x1, y1, x2, y2 } : null;
 graphics.drawString(font, "1-3 / CLICK ICON В· CLICK AGAIN = DEPLOY", x1, y1 - 10, 0xFF7E8B82, false);
 }

       private static void drawDeployIcon(GuiGraphics graphics, Font font, int x, int y,
 ZoneWarsState.RespawnState respawn, boolean selected, boolean hovered, boolean canDeploy,
 int hotkey, long now) {
 int color = respawnColor(respawn.kind(), respawn.team());
 float pulse = (float) ((Math.sin((now % 1200L) / 1200.0 * Math.PI * 2.0) + 1.0) * 0.5);
 if (selected) {
 int spread = 15 + Math.round(pulse * 3.0f);
 int alpha = 115 + Math.round(pulse * 100.0f);
 drawSelectionBrackets(graphics, x, y, spread, (alpha << 24) | 0x00FFFFFF);
 int ring = 18 + (int) ((now % 900L) / 900.0f * 8.0f);
 int ringAlpha = 120 - (int) ((now % 900L) / 900.0f * 110.0f);
 graphics.renderOutline(x - ring, y - ring, ring * 2, ring * 2, (ringAlpha << 24) | 0x00FFFFFF);
 } else if (hovered && respawn.available()) {
 drawSelectionBrackets(graphics, x, y, 15, 0x96FFFFFF);
 }
 graphics.fill(x - 12, y - 12, x + 12, y + 12, 0xE810171A);
 graphics.renderOutline(x - 12, y - 12, 24, 24, selected ? 0xA8FFFFFF : (0x88000000 | (color & 0xFFFFFF)));
 drawKindGlyph(graphics, x, y, respawn.kind(), color);
 }

 private static void drawSelectionBrackets(GuiGraphics graphics, int x, int y, int spread, int color) {
 int arm = 6;
 graphics.fill(x - spread, y - spread, x - spread + arm, y - spread + 2, color);
 graphics.fill(x - spread, y - spread, x - spread + 2, y - spread + arm, color);
 graphics.fill(x + spread - arm, y - spread, x + spread, y - spread + 2, color);
 graphics.fill(x + spread - 2, y - spread, x + spread, y - spread + arm, color);
 graphics.fill(x - spread, y + spread - 2, x - spread + arm, y + spread, color);
 graphics.fill(x - spread, y + spread - arm, x - spread + 2, y + spread, color);
 graphics.fill(x + spread - arm, y + spread - 2, x + spread, y + spread, color);
 graphics.fill(x + spread - 2, y + spread - arm, x + spread, y + spread, color);
 }

             private static void drawKindGlyph(GuiGraphics graphics, int x, int y, String kind, int color) {
 String normalized = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
 ResourceLocation texture = "TENT".equals(normalized) ? ZW_ICON_TENT
 : (("OUTPOST".equals(normalized) || "RALLY".equals(normalized)) ? ZW_ICON_OUTPOST : ZW_ICON_BASE);
 float red = ((color >> 16) & 255) / 255.0f;
 float green = ((color >> 8) & 255) / 255.0f;
 float blue = (color & 255) / 255.0f;
 float alpha = ((color >>> 24) & 255) / 255.0f;
 com.mojang.blaze3d.systems.RenderSystem.enableBlend();
 com.mojang.blaze3d.systems.RenderSystem.setShaderColor(red, green, blue, alpha);
 graphics.blit(texture, x - 8, y - 8, 0.0f, 0.0f, 16, 16, 16, 16);
 com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
 }

    private static void deployNow(Minecraft minecraft) {
        deployOpenAttempts = 0;
        XaeroWaypointBridge.markDeploying();
        ZoneWarsNetworking.confirmRespawn();
        if (minecraft.player != null && minecraft.player.isDeadOrDying()) {
            // The real respawn: vanilla packet -> server PlayerRespawnEvent ->
            // teleport to the selected deployment point.
            minecraft.player.respawn();
        }
        lastDeployAt = System.currentTimeMillis();
        minecraft.setScreen(null);
    }

    // -------------------------------------------------------------- input

     private static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
 if (broken) return;
 Screen screen = event.getScreen();
 if (screen == null || !SCREEN_CLASS.equals(screen.getClass().getName())) return;
 double mx = event.getMouseX();
 double my = event.getMouseY();
 if (mx >= lastPanelX && mx < lastPanelX + lastPanelW && my >= lastPanelY && my < lastPanelY + lastPanelH) {
 // Prevent Xaero's right-button chunk-selection rectangle from being created.
 event.setCanceled(true);
 }
 }
 private static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
 if (broken) return;
 Screen screen = event.getScreen();
 if (screen == null || !SCREEN_CLASS.equals(screen.getClass().getName())) return;
 Minecraft minecraft = Minecraft.getInstance();
 double mx = event.getMouseX(); double my = event.getMouseY(); int button = event.getButton();
 try {
 if (respawnUiActive && button == 0) {
 for (int i = 0; i < cardRects.size(); i++) {
 if (within(cardRects.get(i), mx, my)) {
 String kind = cardKinds.get(i); ZoneWarsState.Snapshot snap = ZoneWarsState.snapshot();
 if (kind != null && kind.equals(snap.selectedRespawn()) && snap.respawnPrompt()) deployNow(minecraft);
 else ZoneWarsNetworking.chooseRespawn(kind);
 event.setCanceled(true); return;
 }
 }
 if (within(deployRect, mx, my)) { deployNow(minecraft); event.setCanceled(true); return; }
 }
 boolean onMap = transformValid && lastScale > 0.0001 && mx >= lastPanelX && mx < lastPanelX + lastPanelW && my >= lastPanelY && my < lastPanelY + lastPanelH;
 if (onMap) {
 int worldX = (int) Math.round(lastCameraX + (mx - lastCenterX) / lastScale);
 int worldZ = (int) Math.round(lastCameraZ + (my - lastCenterY) / lastScale);
 if (button == 0 && Screen.hasShiftDown()) { ZoneWarsNetworking.setWaypoint(worldX, worldZ); event.setCanceled(true); }
 else if (button == 0 && Screen.hasControlDown()) { ZoneWarsNetworking.sendPing("ATTACK", worldX, worldZ); event.setCanceled(true); }
 else if (button == 1 && Screen.hasControlDown()) { ZoneWarsNetworking.sendPing("DANGER", worldX, worldZ); event.setCanceled(true); }
 else if (button == 1) {
 // Xaero starts chunk-selection on RMB drag. PDA does not need that mode.
 event.setCanceled(true);
 }
 }
 } catch (Throwable ignored) { }
 }

    private static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (broken || !respawnUiActive) {
            return;
        }
        Screen screen = event.getScreen();
        if (screen == null || !SCREEN_CLASS.equals(screen.getClass().getName())) {
            return;
        }
        int key = event.getKeyCode();
        if ((key == 257 || key == 335) && ZoneWarsState.snapshot().respawnPrompt()) { // ENTER / KP_ENTER
            deployNow(Minecraft.getInstance());
            event.setCanceled(true);
            return;
        }
        if (key >= 49 && key <= 51) { // 1..3
            int index = key - 49;
            if (index < cardKinds.size()) {
                ZoneWarsNetworking.chooseRespawn(cardKinds.get(index));
                event.setCanceled(true);
            }
        }
    }

    private static boolean within(int[] rect, double mx, double my) {
        return rect != null && mx >= rect[0] && mx < rect[2] && my >= rect[1] && my < rect[3];
    }

    // ----------------------------------------------------------- fallback

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
        lastCenterX = px + pw / 2.0;
        lastCenterY = py + ph / 2.0;
        lastCameraX = centerWorldX;
        lastCameraZ = centerWorldZ;
        lastScale = scale;
        transformValid = true;
        drawMarkers(graphics, minecraft, snapshot,
                new WorldTransform(px + pw / 2.0, py + ph / 2.0, centerWorldX, centerWorldZ, scale));
    }

    private static void drawHeader(GuiGraphics graphics, Minecraft minecraft,
            ZoneWarsState.Snapshot snapshot, int px, int py, boolean anchored) {
        String mode = snapshot.respawnPrompt() ? "DEPLOYMENT" : snapshot.phase();
        String text = "ZW UPLINK // " + mode + (anchored ? "" : " // TACTICAL GRID");
        int width = minecraft.font.width(text);
        graphics.fill(px + 4, py + 4, px + 10 + width, py + 16, 0xB00D1210);
        graphics.drawString(minecraft.font, text, px + 7, py + 6, ACCENT);
    }

     private static void drawChip(GuiGraphics graphics, Font font, int x, int y, int color, String label) {
 int half = 8;
 graphics.fill(x - half, y - half, x + half, y + half, 0xE8101614);
 graphics.renderOutline(x - half, y - half, half * 2, half * 2, color);
 graphics.fill(x - 5, y - 5, x + 5, y + 5, 0xB0141A17);
 graphics.drawCenteredString(font, label, x, y - 4, color);
 // Symmetric corner accents improve readability over detailed terrain.
 graphics.fill(x - 10, y - 10, x - 5, y - 9, color);
 graphics.fill(x - 10, y - 10, x - 9, y - 5, color);
 graphics.fill(x + 5, y - 10, x + 10, y - 9, color);
 graphics.fill(x + 9, y - 10, x + 10, y - 5, color);
 graphics.fill(x - 10, y + 9, x - 5, y + 10, color);
 graphics.fill(x - 10, y + 5, x - 9, y + 10, color);
 graphics.fill(x + 5, y + 9, x + 10, y + 10, color);
 graphics.fill(x + 9, y + 5, x + 10, y + 10, color);
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

    private static String kindTitle(String kind) {
        String normalized = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        if ("TENT".equals(normalized)) {
            return "FIELD TENT";
        }
        if ("OUTPOST".equals(normalized)) {
            return "SQUAD OUTPOST";
        }
        return "BASE";
    }

      private static int respawnColor(String kind, String team) {
 // Every spawn icon uses the owning team color: RED or BLUE.
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

    private static void ensureScreenReflection(Class screenClass) throws Exception {
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

    private static void ensureRectReflection(Class rectClass) throws Exception {
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

    private static boolean ensureCameraReflection(Class mapClass) {
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

    private static Field findField(Class type, String name) {
        for (Class current = type; current != null; current = current.getSuperclass()) {
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