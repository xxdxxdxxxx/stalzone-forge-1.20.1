package ru.zonewars.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import ru.zonewars.client.net.ZoneWarsNetworking;
import ru.zonewars.client.state.ZoneWarsState;

import java.util.ArrayList;
import java.util.List;

public final class ZoneMapScreen extends Screen {

    private static final int BACKDROP = 0xF0090C10;
    private static final int MAP_GROUND = 0xFF26332B;
    private static final int MAP_FOREST = 0xFF1D2D23;
    private static final int ROAD = 0xFF515B5E;
    private static final int BUILDING = 0xFF384044;
    private static final int BUILDING_TOP = 0xFF4B565B;
    private static final int PANEL = 0xB013171D;
    private static final int STROKE = 0xFF607080;
    private static final int TEXT = 0xFFE9EEF5;
    private static final int MUTED = 0xFF9CA6B4;
    private static final int RED = 0xFFE54855;
    private static final int BLUE = 0xFF51A7FF;
    private static final int GREEN = 0xFF59D979;
    private static final int YELLOW = 0xFFE7CA60;
    private static final int NEUTRAL = 0xFFE7E0C1;

    private final List<ClickTarget> clickTargets = new ArrayList<>();
    private final List<RectTarget> respawnCards = new ArrayList<>();
    private final boolean respawnMode;
    private ButtonTarget respawnButton;
    private String localSelectedRespawn;
    private long selectionPulseStarted;

    public ZoneMapScreen() {
        this(false);
    }

    public ZoneMapScreen(boolean respawnMode) {
        super(Text.literal("ZoneWars Tactical Map"));
        this.respawnMode = respawnMode;
    }

    public boolean respawnMode() {
        return respawnMode;
    }

    @Override
    public void render(DrawContext graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        ZoneWarsState.Snapshot snapshot = ZoneWarsState.snapshot();
        MapRect map = mapRect(snapshot);
        clickTargets.clear();
        respawnCards.clear();
        respawnButton = null;

        graphics.fill(0, 0, this.width, this.height, BACKDROP);
        drawMapBase(graphics, map, snapshot);
        drawBases(graphics, map, snapshot);
        drawRespawns(graphics, map, snapshot);
        drawCapturePoints(graphics, map, snapshot);
        drawMarkers(graphics, map, snapshot);
        drawPlayers(graphics, map, snapshot);
        drawHeader(graphics, snapshot);
        drawLegend(graphics, snapshot);
        if (respawnMode) {
            drawRespawnControls(graphics, snapshot, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (respawnMode && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (respawnButton != null && respawnButton.contains(mouseX, mouseY) && selectedAvailable(ZoneWarsState.snapshot())) {
                ZoneWarsNetworking.confirmRespawn();
                this.close();
                return true;
            }
            for (RectTarget target : respawnCards) {
                if (target.contains(mouseX, mouseY) && target.available()) {
                    selectRespawn(target.kind());
                    return true;
                }
            }
            for (ClickTarget target : clickTargets) {
                if (target.contains(mouseX, mouseY)) {
                    selectRespawn(target.kind());
                    return true;
                }
            }
        }
        ZoneWarsState.Snapshot snapshot = ZoneWarsState.snapshot();
        MapRect map = mapRect(snapshot);
        if (!respawnMode && inside(map, mouseX, mouseY)) {
            int worldX = screenToWorldX(map, snapshot.bounds(), mouseX);
            int worldZ = screenToWorldZ(map, snapshot.bounds(), mouseY);
            boolean shift = Screen.hasShiftDown();
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && shift) {
                ZoneWarsNetworking.setWaypoint(worldX, worldZ);
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                ZoneWarsNetworking.sendPing("ATTACK", worldX, worldZ);
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && shift) {
                ZoneWarsNetworking.sendPing("DANGER", worldX, worldZ);
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                ZoneWarsNetworking.sendPing("DEFEND", worldX, worldZ);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private MapRect mapRect(ZoneWarsState.Snapshot snapshot) {
        int top = 46;
        int bottom = this.height - 26;
        int left = 24;
        int right = respawnMode ? this.width - 310 : this.width - 24;
        double worldRatio = snapshot.bounds().width() / (double) snapshot.bounds().depth();
        int availableWidth = Math.max(200, right - left);
        int availableHeight = Math.max(160, bottom - top);
        int mapWidth = availableWidth;
        int mapHeight = (int) Math.round(mapWidth / worldRatio);
        if (mapHeight > availableHeight) {
            mapHeight = availableHeight;
            mapWidth = (int) Math.round(mapHeight * worldRatio);
        }
        int x = left + (availableWidth - mapWidth) / 2;
        int y = top + (availableHeight - mapHeight) / 2;
        return new MapRect(x, y, mapWidth, mapHeight);
    }

    private void drawMapBase(DrawContext graphics, MapRect map, ZoneWarsState.Snapshot snapshot) {
        ZoneWarsState.Bounds bounds = snapshot.bounds();
        Identifier texture = mapTexture(snapshot.mapTexture());
        if (texture != null && MinecraftClient.getInstance().getResourceManager().getResource(texture).isPresent()) {
            graphics.drawTexture(texture, map.x(), map.y(), 0.0f, 0.0f, map.width(), map.height(), map.width(), map.height());
            graphics.drawBorder(map.x(), map.y(), map.width(), map.height(), STROKE);
            return;
        }

        graphics.fill(map.x(), map.y(), map.x() + map.width(), map.y() + map.height(), MAP_GROUND);
        graphics.drawBorder(map.x(), map.y(), map.width(), map.height(), STROKE);

        int edge = Math.max(18, Math.min(map.width(), map.height()) / 10);
        graphics.fill(map.x(), map.y(), map.x() + map.width(), map.y() + edge, MAP_FOREST);
        graphics.fill(map.x(), map.y() + map.height() - edge, map.x() + map.width(), map.y() + map.height(), MAP_FOREST);
        graphics.fill(map.x(), map.y(), map.x() + edge, map.y() + map.height(), MAP_FOREST);
        graphics.fill(map.x() + map.width() - edge, map.y(), map.x() + map.width(), map.y() + map.height(), MAP_FOREST);

        int centerX = bounds.minX() + bounds.width() / 2;
        int centerZ = bounds.minZ() + bounds.depth() / 2;
        fillWorldRect(graphics, map, bounds, bounds.minX(), centerZ - 4, bounds.maxX(), centerZ + 4, ROAD);
        fillWorldRect(graphics, map, bounds, centerX - 4, bounds.minZ(), centerX + 4, bounds.maxZ(), ROAD);
        fillWorldRect(graphics, map, bounds, centerX - 34, bounds.minZ(), centerX - 26, bounds.maxZ(), ROAD);
        fillWorldRect(graphics, map, bounds, centerX + 26, bounds.minZ(), centerX + 34, bounds.maxZ(), ROAD);

        for (int i = 0; i < 4; i++) {
            int x = bounds.minX() + bounds.width() * (i + 1) / 5;
            fillWorldRect(graphics, map, bounds, x - 10, centerZ - 28, x + 10, centerZ - 14, BUILDING);
            fillWorldRect(graphics, map, bounds, x - 10, centerZ + 14, x + 10, centerZ + 28, BUILDING);
            fillWorldRect(graphics, map, bounds, x - 7, centerZ - 25, x + 7, centerZ - 17, BUILDING_TOP);
            fillWorldRect(graphics, map, bounds, x - 7, centerZ + 17, x + 7, centerZ + 25, BUILDING_TOP);
        }

        graphics.fill(map.x(), map.y(), map.x() + map.width(), map.y() + 20, 0x553B4148);
        graphics.fill(map.x(), map.y() + map.height() - 20, map.x() + map.width(), map.y() + map.height(), 0x553B4148);
        graphics.fill(map.x(), map.y(), map.x() + 20, map.y() + map.height(), 0x553B4148);
        graphics.fill(map.x() + map.width() - 20, map.y(), map.x() + map.width(), map.y() + map.height(), 0x553B4148);
    }

    private void drawBases(DrawContext graphics, MapRect map, ZoneWarsState.Snapshot snapshot) {
        for (ZoneWarsState.BaseState base : snapshot.bases()) {
            int x = worldX(map, snapshot.bounds(), base.x());
            int y = worldZ(map, snapshot.bounds(), base.z());
            int color = teamColor(base.team());
            graphics.fill(x - 15, y - 12, x + 15, y + 12, 0xCC101820);
            graphics.drawBorder(x - 15, y - 12, 30, 24, color);
            graphics.drawCenteredTextWithShadow(this.textRenderer, "B", x, y - 4, color);
            graphics.drawCenteredTextWithShadow(this.textRenderer, base.team(), x, y + 14, color);
        }
    }

    private void drawRespawns(DrawContext graphics, MapRect map, ZoneWarsState.Snapshot snapshot) {
        String selected = selectedRespawn(snapshot);
        long now = System.currentTimeMillis();
        float pulse = (float) ((Math.sin((now - selectionPulseStarted) / 90.0) + 1.0) * 0.5);
        for (ZoneWarsState.RespawnState respawn : snapshot.respawns()) {
            int x = worldX(map, snapshot.bounds(), respawn.x());
            int y = worldZ(map, snapshot.bounds(), respawn.z());
            int color = respawn.available() ? spawnColor(respawn.kind(), respawn.team()) : 0xFF626B72;
            boolean active = respawn.kind().equals(selected);
            int radius = respawn.kind().equals("BASE") ? 17 : 12;
            if (active) {
                radius += Math.round(pulse * 2.0f);
                graphics.fill(x - radius - 4, y - radius - 4, x + radius + 4, y + radius + 4, 0x332FD17F);
            }
            graphics.fill(x - radius, y - radius, x + radius, y + radius, respawn.available() ? 0xE00B1117 : 0x990B1117);
            graphics.drawBorder(x - radius, y - radius, radius * 2, radius * 2, active ? GREEN : color);
            graphics.drawCenteredTextWithShadow(this.textRenderer, respawn.kind().substring(0, 1), x, y - 4, active ? TEXT : color);
            if (!respawn.available()) {
                String timer = respawn.seconds() < 0 ? "--" : respawn.seconds() + "s";
                graphics.drawCenteredTextWithShadow(this.textRenderer, timer, x, y + radius + 4, MUTED);
            }
            if (respawn.maxHealth() > 0) {
                int barWidth = radius * 2;
                int barY = y + radius + 12;
                graphics.fill(x - radius, barY, x - radius + barWidth, barY + 3, 0xFF20262D);
                graphics.fill(x - radius, barY, x - radius + Math.round(barWidth * respawn.health() / (float) Math.max(1, respawn.maxHealth())), barY + 3, healthColor(respawn.health(), respawn.maxHealth()));
            }
            if (respawn.available()) {
                clickTargets.add(new ClickTarget(respawn.kind(), x, y, radius + 9));
            }
        }
    }

    private void drawCapturePoints(DrawContext graphics, MapRect map, ZoneWarsState.Snapshot snapshot) {
        for (ZoneWarsState.PointState point : snapshot.points()) {
            int x = worldX(map, snapshot.bounds(), point.x());
            int y = worldZ(map, snapshot.bounds(), point.z());
            int color = pointColor(point);
            graphics.fill(x - 22, y - 18, x + 22, y + 18, 0xDD151A20);
            graphics.drawBorder(x - 22, y - 18, 44, 36, color);
            graphics.drawCenteredTextWithShadow(this.textRenderer, pointLabel(point), x, y - 8, TEXT);
            graphics.fill(x - 16, y + 9, x + 16, y + 12, 0xFF2D333B);
            graphics.fill(x - 16, y + 9, x - 16 + Math.round(32 * point.progress() / 100.0f), y + 12, color);
        }
    }

    private void drawPlayers(DrawContext graphics, MapRect map, ZoneWarsState.Snapshot snapshot) {
        for (ZoneWarsState.PlayerState player : snapshot.players()) {
            int x = worldX(map, snapshot.bounds(), player.x());
            int y = worldZ(map, snapshot.bounds(), player.z());
            int color = player.self() ? TEXT : teamColor(player.team());
            int size = player.self() ? 9 : 7;
            graphics.drawCenteredTextWithShadow(this.textRenderer, arrow(player.yaw()), x, y - 4, color);
            graphics.drawBorder(x - size / 2, y - size / 2, size, size, 0xAA070A0D);
            if (player.self() || player.squad()) {
                graphics.drawCenteredTextWithShadow(this.textRenderer, player.name(), x, y + 9, player.squad() && !player.self() ? GREEN : color);
            }
        }
    }

    private void drawMarkers(DrawContext graphics, MapRect map, ZoneWarsState.Snapshot snapshot) {
        for (ZoneWarsState.MarkerState marker : snapshot.markers()) {
            int x = worldX(map, snapshot.bounds(), marker.x());
            int y = worldZ(map, snapshot.bounds(), marker.z());
            int color = markerColor(marker.type());
            int radius = marker.type().equals("WAYPOINT") ? 15 : 12;
            graphics.fill(x - radius, y - radius, x + radius, y + radius, marker.type().equals("WAYPOINT") ? 0xAA0B1A24 : 0x99101010);
            graphics.drawBorder(x - radius, y - radius, radius * 2, radius * 2, color);
            graphics.drawCenteredTextWithShadow(this.textRenderer, markerLabel(marker.type()), x, y - 4, color);
            if (marker.seconds() > 0) {
                graphics.drawCenteredTextWithShadow(this.textRenderer, marker.seconds() + "s", x, y + radius + 3, MUTED);
            }
        }
    }

    private void drawHeader(DrawContext graphics, ZoneWarsState.Snapshot snapshot) {
        int center = this.width / 2;
        graphics.fill(center - 190, 8, center + 190, 38, PANEL);
        graphics.drawBorder(center - 190, 8, 380, 30, STROKE);
        graphics.drawCenteredTextWithShadow(this.textRenderer, "RED " + snapshot.redScore(), center - 126, 18, RED);
        graphics.drawCenteredTextWithShadow(this.textRenderer, formatTime(snapshot.seconds()), center, 18, TEXT);
        graphics.drawCenteredTextWithShadow(this.textRenderer, snapshot.blueScore() + " BLUE", center + 126, 18, BLUE);

        int startX = center - 50;
        for (int i = 0; i < snapshot.points().size(); i++) {
            ZoneWarsState.PointState point = snapshot.points().get(i);
            int x = startX + i * 50;
            graphics.fill(x - 12, 40, x + 12, 58, 0xDD101820);
            graphics.drawBorder(x - 12, 40, 24, 18, pointColor(point));
            graphics.drawCenteredTextWithShadow(this.textRenderer, pointLabel(point), x, 45, TEXT);
        }
    }

    private void drawLegend(DrawContext graphics, ZoneWarsState.Snapshot snapshot) {
        int x = 16;
        int y = this.height - 19;
        String mode = respawnMode ? "Respawn select  " : "";
        String text = mode + "Team " + snapshot.team() + "  Spawn " + snapshot.selectedRespawn() + "  Heading " + snapshot.selfYaw() + " deg";
        graphics.fill(x - 6, y - 5, x + this.textRenderer.getWidth(text) + 6, y + 11, PANEL);
        graphics.drawText(this.textRenderer, text, x, y, MUTED, false);
    }

    private void drawRespawnControls(DrawContext graphics, ZoneWarsState.Snapshot snapshot, int mouseX, int mouseY) {
        int panelX = this.width - 294;
        int panelY = 46;
        int panelWidth = 270;
        int panelHeight = this.height - 72;
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xEE0B1117);
        graphics.drawBorder(panelX, panelY, panelWidth, panelHeight, STROKE);
        graphics.drawText(this.textRenderer, "AVAILABLE SPAWNS", panelX + 12, panelY + 12, MUTED, false);

        String selected = selectedRespawn(snapshot);
        String[] kinds = {"BASE", "TENT", "OUTPOST"};
        int cardY = panelY + 31;
        for (int index = 0; index < kinds.length; index++) {
            String kind = kinds[index];
            ZoneWarsState.RespawnState state = respawn(snapshot, kind);
            boolean available = state != null && state.available();
            boolean active = kind.equals(selected);
            int cardX = panelX + 10;
            int y = cardY + index * 78;
            int width = panelWidth - 20;
            int height = 68;
            boolean hover = mouseX >= cardX && mouseX <= cardX + width && mouseY >= y && mouseY <= y + height;
            int inset = hover && available ? 1 : 0;
            int x1 = cardX + inset;
            int y1 = y + inset;
            int w = width - inset * 2;
            int h = height - inset * 2;
            int fill = active ? 0xEE14271E : available ? 0xDD121A21 : 0x9911171C;
            graphics.fill(x1, y1, x1 + w, y1 + h, fill);
            graphics.drawBorder(x1, y1, w, h, active ? GREEN : available ? 0xFF465661 : 0xFF303940);
            graphics.fill(x1, y1, x1 + 3, y1 + h, active ? GREEN : available ? spawnColor(kind, state == null ? "NONE" : state.team()) : 0xFF4D565D);
            graphics.drawText(this.textRenderer, respawnTitle(kind), x1 + 11, y1 + 10, available ? TEXT : 0xFF68737B, false);
            if (active) {
                graphics.drawText(this.textRenderer, "SELECTED", x1 + w - 61, y1 + 10, GREEN, false);
            }
            String meta = respawnMeta(state, kind);
            graphics.drawText(this.textRenderer, meta, x1 + 11, y1 + 27, available ? MUTED : 0xFF5C666D, false);
            if (state != null && state.maxHealth() > 0) {
                int healthWidth = w - 22;
                int healthY = y1 + h - 13;
                graphics.fill(x1 + 11, healthY, x1 + 11 + healthWidth, healthY + 3, 0xFF263038);
                graphics.fill(x1 + 11, healthY, x1 + 11 + Math.round(healthWidth * state.health() / (float) Math.max(1, state.maxHealth())), healthY + 3, healthColor(state.health(), state.maxHealth()));
            }
            respawnCards.add(new RectTarget(kind, x1, y1, w, h, available));
        }

        int buttonX = panelX + 10;
        int buttonY = panelY + panelHeight - 58;
        int buttonWidth = panelWidth - 20;
        int buttonHeight = 38;
        respawnButton = new ButtonTarget(buttonX, buttonY, buttonWidth, buttonHeight);
        boolean buttonHover = respawnButton.contains(mouseX, mouseY);
        boolean ready = selectedAvailable(snapshot);
        int buttonFill = ready ? (buttonHover ? 0xFF328ED0 : 0xFF246EA8) : 0xFF273039;
        int buttonStroke = ready ? BLUE : 0xFF4A555E;
        graphics.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + buttonHeight, buttonFill);
        graphics.drawBorder(buttonX, buttonY, buttonWidth, buttonHeight, buttonStroke);
        graphics.drawCenteredTextWithShadow(this.textRenderer, ready ? "DEPLOY" : "SPAWN UNAVAILABLE", buttonX + buttonWidth / 2, buttonY + 8, ready ? TEXT : MUTED);
        graphics.drawCenteredTextWithShadow(this.textRenderer, "ENTER  ·  " + selected, buttonX + buttonWidth / 2, buttonY + 22, ready ? 0xFFD8EBF8 : 0xFF68737B);

        graphics.drawText(this.textRenderer, "1-3 SELECT  ·  CLICK MAP MARKER", panelX + 12, panelY + panelHeight - 12, 0xFF778995, false);
        graphics.drawText(this.textRenderer, "SELECT DEPLOYMENT", 24, 17, TEXT, false);
        graphics.drawText(this.textRenderer, "Choose a safe return point", 24, 31, MUTED, false);
    }

    private void selectRespawn(String kind) {
        localSelectedRespawn = kind;
        selectionPulseStarted = System.currentTimeMillis();
        ZoneWarsNetworking.chooseRespawn(kind);
    }

    private String selectedRespawn(ZoneWarsState.Snapshot snapshot) {
        return localSelectedRespawn == null || localSelectedRespawn.isBlank() ? snapshot.selectedRespawn() : localSelectedRespawn;
    }

    private boolean selectedAvailable(ZoneWarsState.Snapshot snapshot) {
        ZoneWarsState.RespawnState state = respawn(snapshot, selectedRespawn(snapshot));
        return state != null && state.available();
    }

    private ZoneWarsState.RespawnState respawn(ZoneWarsState.Snapshot snapshot, String kind) {
        for (ZoneWarsState.RespawnState state : snapshot.respawns()) {
            if (state.kind().equals(kind)) {
                return state;
            }
        }
        return null;
    }

    private String respawnTitle(String kind) {
        return switch (kind) {
            case "TENT" -> "FIELD TENT";
            case "OUTPOST" -> "SQUAD OUTPOST";
            default -> "BASE";
        };
    }

    private String respawnMeta(ZoneWarsState.RespawnState state, String kind) {
        if (state == null) {
            return "Not deployed";
        }
        if (!state.available()) {
            return state.seconds() > 0 ? "Wave in " + state.seconds() + "s" : "Unavailable";
        }
        if ("BASE".equals(kind)) {
            return "Always available";
        }
        return state.health() + " / " + state.maxHealth() + " HP";
    }

    private void fillWorldRect(DrawContext graphics, MapRect map, ZoneWarsState.Bounds bounds, int minX, int minZ, int maxX, int maxZ, int color) {
        int x1 = worldX(map, bounds, minX);
        int x2 = worldX(map, bounds, maxX);
        int y1 = worldZ(map, bounds, minZ);
        int y2 = worldZ(map, bounds, maxZ);
        graphics.fill(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), color);
    }

    private int worldX(MapRect map, ZoneWarsState.Bounds bounds, int x) {
        return map.x() + Math.round((x - bounds.minX()) * map.width() / (float) bounds.width());
    }

    private int worldZ(MapRect map, ZoneWarsState.Bounds bounds, int z) {
        return map.y() + Math.round((z - bounds.minZ()) * map.height() / (float) bounds.depth());
    }

    private int screenToWorldX(MapRect map, ZoneWarsState.Bounds bounds, double x) {
        return bounds.minX() + Math.round((float) ((x - map.x()) * bounds.width() / map.width()));
    }

    private int screenToWorldZ(MapRect map, ZoneWarsState.Bounds bounds, double y) {
        return bounds.minZ() + Math.round((float) ((y - map.y()) * bounds.depth() / map.height()));
    }

    private boolean inside(MapRect map, double x, double y) {
        return x >= map.x() && x <= map.x() + map.width() && y >= map.y() && y <= map.y() + map.height();
    }

    private String pointLabel(ZoneWarsState.PointState point) {
        if (!point.name().isBlank()) {
            return point.name().substring(0, 1).toUpperCase();
        }
        return point.id().isBlank() ? "?" : point.id().substring(0, 1).toUpperCase();
    }

    private String arrow(int yaw) {
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

    private String formatTime(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        return "%02d:%02d".formatted(safeSeconds / 60, safeSeconds % 60);
    }

    private int spawnColor(String kind, String team) {
        return switch (kind) {
            case "TENT" -> GREEN;
            case "OUTPOST" -> YELLOW;
            default -> teamColor(team);
        };
    }

    private int pointColor(ZoneWarsState.PointState point) {
        return switch (point.status()) {
            case "CONTESTED" -> YELLOW;
            case "CAPTURING" -> teamColor(point.capturingTeam());
            case "NEUTRALIZING" -> YELLOW;
            default -> teamColor(point.owner());
        };
    }

    private int teamColor(String team) {
        return switch (team) {
            case "RED" -> RED;
            case "BLUE" -> BLUE;
            default -> NEUTRAL;
        };
    }

    private int markerColor(String type) {
        return switch (type) {
            case "ATTACK" -> RED;
            case "DEFEND" -> GREEN;
            case "WAYPOINT" -> BLUE;
            default -> YELLOW;
        };
    }

    private String markerLabel(String type) {
        return switch (type) {
            case "ATTACK" -> "A";
            case "DEFEND" -> "D";
            case "WAYPOINT" -> "W";
            default -> "!";
        };
    }

    private int healthColor(int health, int maxHealth) {
        float ratio = health / (float) Math.max(1, maxHealth);
        if (ratio > 0.6f) {
            return GREEN;
        }
        if (ratio > 0.3f) {
            return YELLOW;
        }
        return RED;
    }

    private Identifier mapTexture(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Identifier.tryParse(value);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (respawnMode) {
            ZoneWarsState.Snapshot snapshot = ZoneWarsState.snapshot();
            if (keyCode == GLFW.GLFW_KEY_1) {
                selectIfAvailable(snapshot, "BASE");
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_2) {
                selectIfAvailable(snapshot, "TENT");
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_3) {
                selectIfAvailable(snapshot, "OUTPOST");
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER && selectedAvailable(snapshot)) {
                ZoneWarsNetworking.confirmRespawn();
                this.close();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void selectIfAvailable(ZoneWarsState.Snapshot snapshot, String kind) {
        ZoneWarsState.RespawnState state = respawn(snapshot, kind);
        if (state != null && state.available()) {
            selectRespawn(kind);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !respawnMode;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private record MapRect(int x, int y, int width, int height) {
    }

    private record ClickTarget(String kind, int x, int y, int radius) {
        boolean contains(double mouseX, double mouseY) {
            double dx = mouseX - x;
            double dy = mouseY - y;
            return dx * dx + dy * dy <= radius * radius;
        }
    }

    private record RectTarget(String kind, int x, int y, int width, int height, boolean available) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    private record ButtonTarget(int x, int y, int width, int height) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }
}
