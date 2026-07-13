package ru.zonewars.client.ui;

import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.ResourceLocation;
import ru.zonewars.client.state.ZoneWarsState;

public final class ZoneWarsHud {

    private static final int PANEL = 0xD20B1117;
    private static final int PANEL_SOFT = 0xA80B1117;
    private static final int STROKE = 0xA8708190;
    private static final int TEXT = 0xFFF0F5F8;
    private static final int MUTED = 0xFF9AAAB6;
    private static final int RED = 0xFFFF6670;
    private static final int BLUE = 0xFF65B2FF;
    private static final int GREEN = 0xFF67D38B;
    private static final int YELLOW = 0xFFE6C766;
    private static final int NEUTRAL = 0xFFD7DEE3;
    private static final int MAP_GROUND = 0xFF20352A;
    private static final int MAP_FOREST = 0xFF16271E;
    private static final int MAP_ROAD = 0xFF536166;
    private static int lastHitSequence;
    private static long hitStartedAt;
    private static ZoneWarsState.HitState activeHit = new ZoneWarsState.HitState(0, 0, false);

    private ZoneWarsHud() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(ZoneWarsHud::onOverlay);
    }

    private static void onOverlay(RenderGuiOverlayEvent.Post event) {
        // Forge fires Post once for every vanilla overlay. Drawing on every event
        // rendered the entire ZoneWars HUD many times per frame and destroyed FPS.
        if (!event.getOverlay().id().equals(VanillaGuiOverlay.HOTBAR.id())) {
            return;
        }
        render(event.getGuiGraphics());
    }

    private static void render(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (client.screen != null && !(client.screen instanceof ChatScreen)) {
            return;
        }

        ZoneWarsState.Snapshot snapshot = ZoneWarsState.snapshot();
        if ("NONE".equals(snapshot.team())) {
            return;
        }

        drawTopBar(graphics, client, snapshot);
        int nextY = drawSquad(graphics, client, snapshot);
        drawKillFeed(graphics, client, snapshot, nextY + 8);
        drawMiniMap(graphics, client, snapshot);
        drawHitMarker(graphics, client, snapshot);
    }

    private static void drawTopBar(GuiGraphics graphics, Minecraft client, ZoneWarsState.Snapshot snapshot) {
        int center = graphics.guiWidth() / 2;
        int yaw = normalizeYaw(client.player.getYRot());
        drawCompass(graphics, client, center, yaw);

        int x = center - 194;
        int y = 36;
        int width = 388;
        graphics.fill(x, y, x + width, y + 38, PANEL);
        graphics.renderOutline(x, y, width, 38, STROKE);
        graphics.fill(center - 1, y + 5, center + 1, y + 33, 0x557A8995);

        graphics.drawCenteredString(client.font, String.valueOf(snapshot.redScore()), center - 154, y + 14, RED);
        graphics.drawCenteredString(client.font, "RED", center - 112, y + 14, RED);
        graphics.drawCenteredString(client.font, formatTime(snapshot.seconds()), center, y + 12, TEXT);
        graphics.drawCenteredString(client.font, "BLUE", center + 112, y + 14, BLUE);
        graphics.drawCenteredString(client.font, String.valueOf(snapshot.blueScore()), center + 154, y + 14, BLUE);

        int count = snapshot.points().size();
        int pointStart = center - (count * 30 - 6) / 2;
        for (int i = 0; i < count; i++) {
            ZoneWarsState.PointState point = snapshot.points().get(i);
            int px = pointStart + i * 30;
            int py = y + 44;
            int color = pointColor(point);
            graphics.fill(px - 11, py, px + 11, py + 21, PANEL);
            graphics.renderOutline(px - 11, py, 22, 21, color);
            graphics.drawCenteredString(client.font, pointLabel(point), px, py + 5, TEXT);
            graphics.fill(px - 8, py + 16, px + 8, py + 18, 0xFF28323A);
            graphics.fill(px - 8, py + 16, px - 8 + Math.round(16 * point.progress() / 100.0f), py + 18, pointFillColor(point));
        }
    }

    private static int drawSquad(GuiGraphics graphics, Minecraft client, ZoneWarsState.Snapshot snapshot) {
        int x = 16;
        int y = 24;
        int row = 0;
        for (ZoneWarsState.PlayerState player : snapshot.players()) {
            if (!player.squad() && !player.self()) {
                continue;
            }
            int top = y + 18 + row * 19;
            if (row == 0) {
                graphics.fill(x, y, x + 196, y + 17, PANEL);
                graphics.renderOutline(x, y, 196, 17, STROKE);
                graphics.drawString(client.font, "SQUAD", x + 7, y + 5, MUTED, false);
            }
            graphics.fill(x, top, x + 196, top + 17, PANEL_SOFT);
            int color = player.self() ? TEXT : GREEN;
            graphics.drawString(client.font, trim(player.name(), 16), x + 7, top + 5, color, false);
            int barX = x + 133;
            int barY = top + 7;
            graphics.fill(barX, barY, barX + 55, barY + 4, 0xFF263039);
            graphics.fill(barX, barY, barX + Math.round(55 * player.health() / 100.0f), barY + 4, healthColor(player.health()));
            row++;
        }
        return y + 18 + row * 19;
    }

    private static void drawKillFeed(GuiGraphics graphics, Minecraft client, ZoneWarsState.Snapshot snapshot, int startY) {
        int row = 0;
        for (ZoneWarsState.KillFeedState entry : snapshot.killFeed()) {
            int y = startY + row * 17;
            graphics.fill(16, y, 246, y + 15, PANEL_SOFT);
            graphics.drawString(client.font, trim(entry.killer(), 12), 23, y + 4, teamColor(entry.killerTeam()), false);
            graphics.drawCenteredString(client.font, ">", 112, y + 4, MUTED);
            graphics.drawString(client.font, trim(entry.weapon(), 9), 122, y + 4, 0xFFC8D1D8, false);
            graphics.drawString(client.font, trim(entry.victim(), 12), 184, y + 4, teamColor(entry.victimTeam()), false);
            row++;
        }
    }

    private static void drawMiniMap(GuiGraphics graphics, Minecraft client, ZoneWarsState.Snapshot snapshot) {
        int mapSize = 148;
        int panelWidth = mapSize + 16;
        int panelHeight = mapSize + 30;
        int panelX = graphics.guiWidth() - panelWidth - 16;
        int panelY = 16;
        int mapX = panelX + 8;
        int mapY = panelY + 22;

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, STROKE);
        graphics.drawString(client.font, "MINIMAP", panelX + 8, panelY + 7, MUTED, false);
        graphics.drawString(client.font, "N", panelX + panelWidth - 14, panelY + 7, TEXT, false);
        drawMiniMapBase(graphics, snapshot, mapX, mapY, mapSize);
        graphics.renderOutline(mapX, mapY, mapSize, mapSize, 0xCC9AABB7);

        for (ZoneWarsState.PointState point : snapshot.points()) {
            int px = miniX(mapX, mapSize, snapshot.bounds(), point.x());
            int py = miniZ(mapY, mapSize, snapshot.bounds(), point.z());
            int color = pointColor(point);
            graphics.fill(px - 6, py - 6, px + 6, py + 6, PANEL);
            graphics.renderOutline(px - 6, py - 6, 12, 12, color);
            graphics.drawCenteredString(client.font, pointLabel(point), px, py - 4, TEXT);
        }

        for (ZoneWarsState.RespawnState respawn : snapshot.respawns()) {
            int px = miniX(mapX, mapSize, snapshot.bounds(), respawn.x());
            int py = miniZ(mapY, mapSize, snapshot.bounds(), respawn.z());
            int color = respawn.available() ? spawnColor(respawn.kind(), respawn.team()) : 0xFF606B73;
            graphics.fill(px - 3, py - 3, px + 4, py + 4, 0xFF0B1117);
            graphics.renderOutline(px - 3, py - 3, 7, 7, color);
        }

        for (ZoneWarsState.MarkerState marker : snapshot.markers()) {
            int px = miniX(mapX, mapSize, snapshot.bounds(), marker.x());
            int py = miniZ(mapY, mapSize, snapshot.bounds(), marker.z());
            int color = markerColor(marker.type());
            graphics.fill(px - 5, py - 5, px + 5, py + 5, PANEL);
            graphics.renderOutline(px - 5, py - 5, 10, 10, color);
            graphics.drawCenteredString(client.font, markerLabel(marker.type()), px, py - 4, color);
        }

        ZoneWarsState.PlayerState self = null;
        for (ZoneWarsState.PlayerState player : snapshot.players()) {
            int px = miniX(mapX, mapSize, snapshot.bounds(), player.x());
            int py = miniZ(mapY, mapSize, snapshot.bounds(), player.z());
            if (player.self()) {
                self = player;
                drawPlayerMarker(graphics, px, py, player.yaw(), TEXT, true);
            } else if (player.squad()) {
                drawPlayerMarker(graphics, px, py, player.yaw(), GREEN, false);
            }
        }

        if (self != null) {
            String coords = "X " + self.x() + "  Z " + self.z();
            graphics.fill(mapX + 1, mapY + mapSize - 13, mapX + client.font.width(coords) + 7, mapY + mapSize - 1, 0xB20B1117);
            graphics.drawString(client.font, coords, mapX + 4, mapY + mapSize - 11, MUTED, false);
        }
    }

    private static void drawMiniMapBase(GuiGraphics graphics, ZoneWarsState.Snapshot snapshot, int x, int y, int size) {
        ResourceLocation texture = mapTexture(snapshot.mapTexture());
        Minecraft client = Minecraft.getInstance();
        if (texture != null && client.getResourceManager().getResource(texture).isPresent()) {
            graphics.blit(texture, x, y, 0.0f, 0.0f, size, size, size, size);
        } else {
            ZoneWarsState.Bounds bounds = snapshot.bounds();
            graphics.fill(x, y, x + size, y + size, MAP_GROUND);
            int edge = Math.max(12, size / 10);
            graphics.fill(x, y, x + size, y + edge, MAP_FOREST);
            graphics.fill(x, y + size - edge, x + size, y + size, MAP_FOREST);
            graphics.fill(x, y, x + edge, y + size, MAP_FOREST);
            graphics.fill(x + size - edge, y, x + size, y + size, MAP_FOREST);
            int centerX = bounds.minX() + bounds.width() / 2;
            int centerZ = bounds.minZ() + bounds.depth() / 2;
            fillMiniWorldRect(graphics, x, y, size, bounds, bounds.minX(), centerZ - 4, bounds.maxX(), centerZ + 4, MAP_ROAD);
            fillMiniWorldRect(graphics, x, y, size, bounds, centerX - 4, bounds.minZ(), centerX + 4, bounds.maxZ(), MAP_ROAD);
        }
        for (int step = 1; step < 4; step++) {
            int gx = x + size * step / 4;
            int gy = y + size * step / 4;
            graphics.fill(gx, y, gx + 1, y + size, 0x226F7E88);
            graphics.fill(x, gy, x + size, gy + 1, 0x226F7E88);
        }
    }

    private static void fillMiniWorldRect(GuiGraphics graphics, int originX, int originY, int size, ZoneWarsState.Bounds bounds, int minX, int minZ, int maxX, int maxZ, int color) {
        int x1 = miniX(originX, size, bounds, minX);
        int x2 = miniX(originX, size, bounds, maxX);
        int y1 = miniZ(originY, size, bounds, minZ);
        int y2 = miniZ(originY, size, bounds, maxZ);
        graphics.fill(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), color);
    }

    private static void drawPlayerMarker(GuiGraphics graphics, int x, int y, int yaw, int color, boolean self) {
        int outline = 0xFF080C10;
        int size = self ? 4 : 3;
        graphics.fill(x - size - 1, y - size - 1, x + size + 2, y + size + 2, outline);
        graphics.fill(x - size, y - size, x + size + 1, y + size + 1, color);
        int direction = ((normalizeDegrees(yaw) + 22) / 45) % 8;
        int dx = switch (direction) {
            case 1, 2, 3 -> -1;
            case 5, 6, 7 -> 1;
            default -> 0;
        };
        int dy = switch (direction) {
            case 3, 4, 5 -> -1;
            case 7, 0, 1 -> 1;
            default -> 0;
        };
        graphics.fill(x - dx - 1, y - dy - 1, x - dx + 2, y - dy + 2, outline);
        graphics.fill(x - dx, y - dy, x - dx + 1, y - dy + 1, color);
    }

    private static void drawCompass(GuiGraphics graphics, Minecraft client, int center, int yaw) {
        int width = Math.min(440, Math.max(260, graphics.guiWidth() - 620));
        int y = 4;
        graphics.drawCenteredString(client.font, yaw + "°", center, y, TEXT);
        graphics.fill(center - width / 2, y + 16, center + width / 2, y + 17, 0x668B9BA8);
        graphics.fill(center - 1, y + 11, center + 1, y + 22, TEXT);
        for (int offset = -120; offset <= 120; offset += 15) {
            int heading = normalizeDegrees(yaw + offset);
            int x = center + Math.round(offset * (width / 240.0f));
            int tickHeight = offset % 45 == 0 ? 8 : 4;
            graphics.fill(x, y + 16, x + 1, y + 16 + tickHeight, 0xAACAD4DC);
            if (offset % 45 == 0) {
                graphics.drawCenteredString(client.font, direction(heading), x, y + 24, MUTED);
            }
        }
    }

    private static void drawHitMarker(GuiGraphics graphics, Minecraft client, ZoneWarsState.Snapshot snapshot) {
        ZoneWarsState.HitState hit = snapshot.hit();
        if (hit.sequence() != lastHitSequence) {
            lastHitSequence = hit.sequence();
            activeHit = hit;
            hitStartedAt = System.currentTimeMillis();
        }
        if (activeHit.sequence() == 0 || System.currentTimeMillis() - hitStartedAt > 650) {
            return;
        }
        int centerX = graphics.guiWidth() / 2;
        int centerY = graphics.guiHeight() / 2;
        int color = activeHit.kill() ? RED : TEXT;
        graphics.fill(centerX - 12, centerY - 13, centerX - 5, centerY - 11, color);
        graphics.fill(centerX + 5, centerY - 13, centerX + 12, centerY - 11, color);
        graphics.fill(centerX - 12, centerY + 11, centerX - 5, centerY + 13, color);
        graphics.fill(centerX + 5, centerY + 11, centerX + 12, centerY + 13, color);
        graphics.drawCenteredString(client.font, activeHit.kill() ? "KILL" : "+" + activeHit.damage(), centerX, centerY + 18, color);
    }

    private static int miniX(int originX, int size, ZoneWarsState.Bounds bounds, int value) {
        return clamp(originX + Math.round((value - bounds.minX()) * size / (float) bounds.width()), originX + 2, originX + size - 2);
    }

    private static int miniZ(int originY, int size, ZoneWarsState.Bounds bounds, int value) {
        return clamp(originY + Math.round((value - bounds.minZ()) * size / (float) bounds.depth()), originY + 2, originY + size - 2);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static ResourceLocation mapTexture(String value) {
        return value == null || value.isBlank() ? null : ResourceLocation.tryParse(value);
    }

    private static int normalizeYaw(float yaw) {
        int value = Math.round(yaw) % 360;
        return value < 0 ? value + 360 : value;
    }

    private static int normalizeDegrees(int yaw) {
        int value = yaw % 360;
        return value < 0 ? value + 360 : value;
    }

    private static String direction(int yaw) {
        if (yaw >= 337 || yaw < 23) return "S";
        if (yaw < 68) return "SW";
        if (yaw < 113) return "W";
        if (yaw < 158) return "NW";
        if (yaw < 203) return "N";
        if (yaw < 248) return "NE";
        if (yaw < 293) return "E";
        return "SE";
    }

    private static String formatTime(int seconds) {
        int safe = Math.max(0, seconds);
        return "%02d:%02d".formatted(safe / 60, safe % 60);
    }

    private static String pointLabel(ZoneWarsState.PointState point) {
        if (!point.name().isBlank()) return point.name().substring(0, 1).toUpperCase();
        return point.id().isBlank() ? "?" : point.id().substring(0, 1).toUpperCase();
    }

    private static int spawnColor(String kind, String team) {
        return switch (kind) {
            case "TENT" -> GREEN;
            case "OUTPOST" -> YELLOW;
            default -> teamColor(team);
        };
    }

    private static int pointColor(ZoneWarsState.PointState point) {
        return switch (point.status()) {
            case "CONTESTED", "NEUTRALIZING" -> YELLOW;
            case "CAPTURING" -> teamColor(point.capturingTeam());
            default -> teamColor(point.owner());
        };
    }

    private static int pointFillColor(ZoneWarsState.PointState point) {
        if ("CAPTURING".equals(point.status()) || "NEUTRALIZING".equals(point.status())) return teamColor(point.capturingTeam());
        return teamColor(point.owner());
    }

    private static int markerColor(String type) {
        return switch (type) {
            case "ATTACK" -> RED;
            case "DEFEND" -> GREEN;
            case "WAYPOINT" -> BLUE;
            default -> YELLOW;
        };
    }

    private static String markerLabel(String type) {
        return switch (type) {
            case "ATTACK" -> "A";
            case "DEFEND" -> "D";
            case "WAYPOINT" -> "W";
            default -> "!";
        };
    }

    private static int teamColor(String team) {
        return switch (team) {
            case "RED" -> RED;
            case "BLUE" -> BLUE;
            default -> NEUTRAL;
        };
    }

    private static int healthColor(int health) {
        if (health > 60) return GREEN;
        if (health > 30) return YELLOW;
        return RED;
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
