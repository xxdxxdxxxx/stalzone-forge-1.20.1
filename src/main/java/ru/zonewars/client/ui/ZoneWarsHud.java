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
 private static final ResourceLocation ZW_MAP_ICON_BASE = new ResourceLocation("zonewars", "textures/gui/map/base.png");
 private static final ResourceLocation ZW_MAP_ICON_TENT = new ResourceLocation("zonewars", "textures/gui/map/tent.png");
 private static final ResourceLocation ZW_MAP_ICON_OUTPOST = new ResourceLocation("zonewars", "textures/gui/map/outpost.png");

    private static final int PANEL = 0xB80B1117;
    private static final int PANEL_SOFT = 0x8F0B1117;
    private static final int STROKE = 0x88708190;
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
        drawRoundMiniMap(graphics, client, snapshot);
        drawHitMarker(graphics, client, snapshot);
    }

      private static void drawTopBar(GuiGraphics graphics, Minecraft client, ZoneWarsState.Snapshot snapshot) {
 int center = graphics.guiWidth() / 2;
 int yaw = normalizeYaw(client.player.getYRot());
 drawCompass(graphics, client, center, yaw);

 int x = center - 128;
 int y = 34;
 int width = 256;
 int height = 22;
 graphics.fill(x, y, x + width, y + height, PANEL);
 graphics.fill(x, y, center, y + 1, 0xCCB9474F);
 graphics.fill(center, y, x + width, y + 1, 0xCC4787B8);
 graphics.renderOutline(x, y, width, height, STROKE);
 graphics.fill(center - 1, y + 4, center + 1, y + height - 4, 0x557A8995);
 graphics.drawString(client.font, "RED", x + 12, y + 7, RED, false);
 graphics.drawCenteredString(client.font, String.valueOf(snapshot.redScore()), center - 43, y + 7, RED);
 graphics.drawCenteredString(client.font, formatTime(snapshot.seconds()), center, y + 7, TEXT);
 graphics.drawCenteredString(client.font, String.valueOf(snapshot.blueScore()), center + 43, y + 7, BLUE);
 graphics.drawString(client.font, "BLUE", x + width - 12 - client.font.width("BLUE"), y + 7, BLUE, false);
 int count = snapshot.points().size();
 int gap = 25;
 int pointStart = center - ((count - 1) * gap) / 2;
 for (int i = 0; i < count; i++) {
 ZoneWarsState.PointState point = snapshot.points().get(i);
 int px = pointStart + i * gap;
 int py = y + height + 5;
 int color = pointColor(point);
 graphics.fill(px - 10, py, px + 10, py + 19, PANEL);
 graphics.renderOutline(px - 10, py, 20, 19, color);
 graphics.drawCenteredString(client.font, pointLabel(point), px, py + 5, TEXT);
 graphics.fill(px - 8, py + 16, px + 8, py + 18, 0xFF28323A);
 graphics.fill(px - 8, py + 16, px - 8 + Math.round(16 * point.progress() / 100.0f), py + 18, pointFillColor(point));
 }
 }

     private static int drawSquad(GuiGraphics graphics, Minecraft client, ZoneWarsState.Snapshot snapshot) {
 int x = 8;
 int y = 28;
 int width = 146;
 int rowHeight = 18;
 int row = 0;
 for (ZoneWarsState.PlayerState player : snapshot.players()) {
 if (!player.squad() && !player.self()) {
 continue;
 }
 int top = y + row * rowHeight;
 graphics.fill(x, top, x + width, top + 16, PANEL_SOFT);
 graphics.fill(x, top, x + 2, top + 16, player.self() ? TEXT : GREEN);
 int color = player.self() ? TEXT : GREEN;
 graphics.drawString(client.font, trim(player.name(), 14), x + 7, top + 4, color, false);
 int barX = x + 99;
 int barY = top + 7;
 int barWidth = 40;
 int health = clamp(player.health(), 0, 100);
 graphics.fill(barX, barY, barX + barWidth, barY + 3, 0xFF263039);
 graphics.fill(barX, barY, barX + Math.round(barWidth * health / 100.0f), barY + 3, healthColor(health));
 row++;
 }
 return y + row * rowHeight;
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

     private static void drawCompass(GuiGraphics graphics, Minecraft client, int center, int yaw) {
 int width = Math.min(430, Math.max(280, graphics.guiWidth() - 650));
 int y = 2;
 graphics.drawCenteredString(client.font, yaw + "\u00B0", center, y, TEXT);
 graphics.fill(center - width / 2, y + 14, center + width / 2, y + 15, 0x4C8B9BA8);
 graphics.fill(center - 1, y + 10, center + 1, y + 19, 0xDDECF2F5);
 for (int offset = -120; offset <= 120; offset += 15) {
 int heading = normalizeDegrees(yaw + offset);
 int x = center + Math.round(offset * (width / 240.0f));
 int tickHeight = offset % 45 == 0 ? 5 : 3;
 graphics.fill(x, y + 14, x + 1, y + 14 + tickHeight, 0x8ECAD4DC);
 if (offset % 45 == 0) {
 graphics.drawCenteredString(client.font, direction(heading), x, y + 19, MUTED);
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

    // ------------------------------------------------------------ round radar

    private static void drawRoundMiniMap(GuiGraphics graphics, Minecraft client, ZoneWarsState.Snapshot snapshot) {
        if (ru.zonewars.client.map.XaeroWaypointBridge.active()) {
 return;
 }
        int radius = 58;
        int cx = graphics.guiWidth() - radius - 10;
        int cy = radius + 12;
        double px = client.player.getX();
        double pz = client.player.getZ();
        double range = 40.0;

        ensureStaticOverlays(client, radius);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        int overlaySize = radius * 2 + 2;
        if (discLocation != null) {
            graphics.blit(discLocation, cx - radius - 1, cy - radius - 1, 0.0f, 0.0f, overlaySize, overlaySize, overlaySize, overlaySize);
        }
        drawRadarTerrain(graphics, client, cx, cy, radius, range, px, pz);
        if (ringLocation != null) {
            graphics.blit(ringLocation, cx - radius - 1, cy - radius - 1, 0.0f, 0.0f, overlaySize, overlaySize, overlaySize, overlaySize);
        }
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

        drawSelfArrow(graphics, client, cx, cy);
        graphics.drawCenteredString(client.font, (int) px + " " + (int) pz, cx, cy + radius + 5, MUTED);
    }

    private static void drawXaeroMapIcons(GuiGraphics graphics, Minecraft client, ZoneWarsState.Snapshot snapshot) {
 int radius = 62;
 int cx = graphics.guiWidth() - 74;
 int cy = 74;
 double range = 80.0;
 double playerX = client.player.getX();
 double playerZ = client.player.getZ();
 for (ZoneWarsState.RespawnState respawn : snapshot.respawns()) {
 int[] pos = radarPos(cx, cy, radius, range, playerX, playerZ, respawn.x(), respawn.z());
 int color = respawn.available() ? teamColor(respawn.team()) : 0xFF687078;
 drawTintedMapIcon(graphics, respawnTexture(respawn.kind()), pos[0], pos[1], 12, color);
 if (respawn.kind() != null && respawn.kind().equals(snapshot.selectedRespawn())) {
 drawMiniSelection(graphics, pos[0], pos[1], 8, 0xC8FFFFFF);
 }
 }
 for (ZoneWarsState.PointState point : snapshot.points()) {
 int[] pos = radarPos(cx, cy, radius, range, playerX, playerZ, point.x(), point.z());
 int color = pointColor(point);
 graphics.fill(pos[0] - 6, pos[1] - 6, pos[0] + 6, pos[1] + 6, 0xD80B1117);
 graphics.renderOutline(pos[0] - 6, pos[1] - 6, 12, 12, color);
 graphics.drawCenteredString(client.font, pointLabel(point), pos[0], pos[1] - 4, color);
 }
 for (ZoneWarsState.PlayerState player : snapshot.players()) {
 if (player.self() || !player.squad()) continue;
 int[] pos = radarPos(cx, cy, radius, range, playerX, playerZ, player.x(), player.z());
 graphics.fill(pos[0] - 2, pos[1] - 2, pos[0] + 3, pos[1] + 3, GREEN);
 }
 }
 private static ResourceLocation respawnTexture(String kind) {
 if ("TENT".equals(kind)) return ZW_MAP_ICON_TENT;
 if ("OUTPOST".equals(kind) || "RALLY".equals(kind)) return ZW_MAP_ICON_OUTPOST;
 return ZW_MAP_ICON_BASE;
 }
 private static void drawTintedMapIcon(GuiGraphics graphics, ResourceLocation texture, int x, int y, int size, int color) {
 float red = ((color >> 16) & 255) / 255.0f;
 float green = ((color >> 8) & 255) / 255.0f;
 float blue = (color & 255) / 255.0f;
 float alpha = ((color >>> 24) & 255) / 255.0f;
 com.mojang.blaze3d.systems.RenderSystem.enableBlend();
 com.mojang.blaze3d.systems.RenderSystem.setShaderColor(red, green, blue, alpha);
 graphics.blit(texture, x - size / 2, y - size / 2, 0.0f, 0.0f, size, size, 32, 32);
 com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
 }
 private static void drawMiniSelection(GuiGraphics graphics, int x, int y, int spread, int color) {
 int arm = 3;
 graphics.fill(x - spread, y - spread, x - spread + arm, y - spread + 1, color);
 graphics.fill(x - spread, y - spread, x - spread + 1, y - spread + arm, color);
 graphics.fill(x + spread - arm, y - spread, x + spread, y - spread + 1, color);
 graphics.fill(x + spread - 1, y - spread, x + spread, y - spread + arm, color);
 graphics.fill(x - spread, y + spread - 1, x - spread + arm, y + spread, color);
 graphics.fill(x - spread, y + spread - arm, x - spread + 1, y + spread, color);
 graphics.fill(x + spread - arm, y + spread - 1, x + spread, y + spread, color);
 graphics.fill(x + spread - 1, y + spread - arm, x + spread, y + spread, color);
 }
 private static void drawSelfArrow(GuiGraphics graphics, Minecraft client, int cx, int cy) {
        float yaw = client.player.getYRot() + 180.0f;
        graphics.pose().pushPose();
        graphics.pose().translate((float) cx, (float) cy, 0.0f);
        graphics.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(yaw));
        if (arrowLocation != null) {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            graphics.blit(arrowLocation, -6, -6, 12, 12, 0.0f, 0.0f, 24, 24, 24, 24);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        } else {
            graphics.fill(-1, -6, 1, -3, TEXT);
            graphics.fill(-2, -3, 2, 0, TEXT);
            graphics.fill(-3, 0, 3, 2, TEXT);
        }
        graphics.pose().popPose();
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

    // ------------------------------------------------- baked radar overlays

    private static net.minecraft.client.renderer.texture.DynamicTexture discTexture;
    private static net.minecraft.resources.ResourceLocation discLocation;
    private static net.minecraft.client.renderer.texture.DynamicTexture ringTexture;
    private static net.minecraft.resources.ResourceLocation ringLocation;
    private static int overlayRadius = -1;
    private static boolean overlaysBroken;
    private static net.minecraft.client.renderer.texture.DynamicTexture arrowTexture;
    private static net.minecraft.resources.ResourceLocation arrowLocation;

    private static void ensureStaticOverlays(Minecraft client, int radius) {
        if (overlaysBroken || overlayRadius == radius) {
            return;
        }
        try {
            int size = radius * 2 + 2;
            int c = radius;
            if (discTexture == null) {
                discTexture = new net.minecraft.client.renderer.texture.DynamicTexture(size, size, true);
                discLocation = client.getTextureManager().register("zonewars_radar_disc", discTexture);
                ringTexture = new net.minecraft.client.renderer.texture.DynamicTexture(size, size, true);
                ringLocation = client.getTextureManager().register("zonewars_radar_rings", ringTexture);
            }
            com.mojang.blaze3d.platform.NativeImage disc = discTexture.getPixels();
            com.mojang.blaze3d.platform.NativeImage rings = ringTexture.getPixels();
            if (disc == null || rings == null) {
                overlaysBroken = true;
                return;
            }
            int discColor = argbToAbgr(0xFF0D1410);
            int edgeDark = argbToAbgr(0xFF222B21);
            int edgeBright = argbToAbgr(0xFF71835C);
            int innerRing = argbToAbgr(0x2ECAD7C2);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double dist = Math.sqrt((double) (x - c) * (x - c) + (double) (y - c) * (y - c));
                    disc.setPixelRGBA(x, y, withCoverage(discColor, coverageInside(dist, radius + 0.3)));
                    int ring = withCoverage(edgeDark, coverageRing(dist, radius + 0.3, 0.9));
                    ring = blendOver(ring, withCoverage(edgeBright, coverageRing(dist, radius - 0.9, 1.0)));
                    ring = blendOver(ring, withCoverage(innerRing, coverageRing(dist, radius - 21.0, 0.8)));
                    rings.setPixelRGBA(x, y, ring);
                }
            }
            int cross = argbToAbgr(0x1CCAD7C2);
            for (int i = -(radius - 6); i <= radius - 6; i++) {
                if (rings.getPixelRGBA(c + i, c) == 0) {
                    rings.setPixelRGBA(c + i, c, cross);
                }
                if (rings.getPixelRGBA(c, c + i) == 0) {
                    rings.setPixelRGBA(c, c + i, cross);
                }
            }
            discTexture.upload();
            ringTexture.upload();
            buildArrowTexture(client);
            overlayRadius = radius;
        } catch (Throwable error) {
            overlaysBroken = true;
        }
    }

    /** Anti-aliased kite arrow baked once; rotated smoothly by the pose matrix. */
    private static void buildArrowTexture(Minecraft client) {
        if (arrowTexture != null) {
            return;
        }
        int size = 24;
        arrowTexture = new net.minecraft.client.renderer.texture.DynamicTexture(size, size, true);
        arrowLocation = client.getTextureManager().register("zonewars_radar_arrow", arrowTexture);
        com.mojang.blaze3d.platform.NativeImage image = arrowTexture.getPixels();
        if (image == null) {
            arrowLocation = null;
            return;
        }
        double[][] outer = { { 12.0, 1.5 }, { 21.5, 22.5 }, { 12.0, 17.5 }, { 2.5, 22.5 } };
        double[][] inner = { { 12.0, 4.6 }, { 18.4, 20.4 }, { 12.0, 15.8 }, { 5.6, 20.4 } };
        int dark = argbToAbgr(0xFF0A0F0C);
        int bright = argbToAbgr(0xFFF0F5F8);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float outerCov = kiteCoverage(x, y, outer);
                if (outerCov <= 0.0f) {
                    image.setPixelRGBA(x, y, 0);
                    continue;
                }
                float innerCov = kiteCoverage(x, y, inner);
                image.setPixelRGBA(x, y, blendOver(withCoverage(dark, outerCov), withCoverage(bright, innerCov)));
            }
        }
        arrowTexture.upload();
        arrowTexture.setFilter(true, false);
    }

    private static float kiteCoverage(int px, int py, double[][] kite) {
        int hits = 0;
        for (int sy = 0; sy < 4; sy++) {
            for (int sx = 0; sx < 4; sx++) {
                double x = px + (sx + 0.5) / 4.0;
                double y = py + (sy + 0.5) / 4.0;
                if (inTriangle(x, y, kite[0], kite[1], kite[2]) || inTriangle(x, y, kite[0], kite[2], kite[3])) {
                    hits++;
                }
            }
        }
        return hits / 16.0f;
    }

    private static boolean inTriangle(double x, double y, double[] a, double[] b, double[] c) {
        double d1 = edgeCross(x, y, a, b);
        double d2 = edgeCross(x, y, b, c);
        double d3 = edgeCross(x, y, c, a);
        boolean hasNeg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean hasPos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(hasNeg && hasPos);
    }

    private static double edgeCross(double x, double y, double[] a, double[] b) {
        return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
    }

    private static float coverageInside(double dist, double r) {
        return (float) Math.max(0.0, Math.min(1.0, r - dist + 0.5));
    }

    private static float coverageRing(double dist, double r, double halfWidth) {
        return (float) Math.max(0.0, Math.min(1.0, halfWidth - Math.abs(dist - r) + 0.5));
    }

    private static int withCoverage(int abgr, float coverage) {
        if (coverage <= 0.0f) {
            return 0;
        }
        int a = (int) (((abgr >>> 24) & 0xFF) * Math.min(1.0f, coverage));
        return (a << 24) | (abgr & 0x00FFFFFF);
    }

    private static int blendOver(int dst, int src) {
        int srcA = (src >>> 24) & 0xFF;
        if (srcA == 0) {
            return dst;
        }
        int dstA = (dst >>> 24) & 0xFF;
        if (dstA == 0 || srcA == 255) {
            return src;
        }
        float s = srcA / 255.0f;
        int outA = Math.min(255, srcA + Math.round(dstA * (1.0f - s)));
        int c1 = Math.round(((src >>> 16) & 0xFF) * s + ((dst >>> 16) & 0xFF) * (1.0f - s));
        int c2 = Math.round(((src >>> 8) & 0xFF) * s + ((dst >>> 8) & 0xFF) * (1.0f - s));
        int c3 = Math.round((src & 0xFF) * s + (dst & 0xFF) * (1.0f - s));
        return (outA << 24) | (c1 << 16) | (c2 << 8) | c3;
    }

    private static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int darkenAbgr(int abgr, float factor) {
        int a = (abgr >>> 24) & 0xFF;
        int c1 = (int) (((abgr >>> 16) & 0xFF) * factor);
        int c2 = (int) (((abgr >>> 8) & 0xFF) * factor);
        int c3 = (int) ((abgr & 0xFF) * factor);
        return (a << 24) | (c1 << 16) | (c2 << 8) | c3;
    }

    // ------------------------------------------------- radar terrain layer

    private static final int TERRAIN_TEX = 160;
    private static net.minecraft.client.renderer.texture.DynamicTexture terrainTexture;
    private static net.minecraft.resources.ResourceLocation terrainLocation;
    private static long terrainBuiltAt;
    private static double terrainAnchorX;
    private static double terrainAnchorZ;
    private static double terrainRange;
    private static boolean terrainBroken;
    private static int radarVoidColor;

    private static void drawRadarTerrain(GuiGraphics graphics, Minecraft client, int cx, int cy, int radius, double range, double px, double pz) {
        if (terrainBroken || client.level == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            if (terrainTexture == null || now - terrainBuiltAt > 1500L
                    || Math.abs(px - terrainAnchorX) > range * 0.25
                    || Math.abs(pz - terrainAnchorZ) > range * 0.25
                    || terrainRange != range) {
                rebuildTerrain(client, px, pz, range);
            }
            if (terrainLocation == null) {
                return;
            }
            int r = radius - 2;
            double pxPerBlock = (radius - 7) / range;
            double texPerBlock = (TERRAIN_TEX / 2.0) / (range * 1.4);
            double texPerScreen = texPerBlock / pxPerBlock;
            float offU = (float) (TERRAIN_TEX / 2.0 + (px - terrainAnchorX) * texPerBlock);
            float offV = (float) (TERRAIN_TEX / 2.0 + (pz - terrainAnchorZ) * texPerBlock);
            int step = 3;
            for (int dy = -r; dy <= r; dy += step) {
                int rowH = Math.min(step, r + 1 - dy);
                if (rowH <= 0) {
                    continue;
                }
                int edge = Math.max(Math.abs(dy), Math.abs(dy + rowH - 1));
                int half = (int) Math.floor(Math.sqrt(Math.max(0.0, (double) r * r - (double) edge * edge)));
                if (half <= 0) {
                    continue;
                }
                float u = offU + (float) (-half * texPerScreen);
                float v = offV + (float) (dy * texPerScreen);
                int uWidth = Math.max(1, (int) Math.round(half * 2 * texPerScreen));
                int vHeight = Math.max(1, (int) Math.round(rowH * texPerScreen));
                graphics.blit(terrainLocation, cx - half, cy + dy, half * 2, rowH, u, v, uWidth, vHeight, TERRAIN_TEX, TERRAIN_TEX);
            }
        } catch (Throwable error) {
            terrainBroken = true;
        }
    }

    private static void rebuildTerrain(Minecraft client, double px, double pz, double range) {
        if (terrainTexture == null) {
            terrainTexture = new net.minecraft.client.renderer.texture.DynamicTexture(TERRAIN_TEX, TERRAIN_TEX, true);
            terrainLocation = client.getTextureManager().register("zonewars_radar_terrain", terrainTexture);
            radarVoidColor = argbToAbgr(0xFF0D1410);
        }
        com.mojang.blaze3d.platform.NativeImage image = terrainTexture.getPixels();
        if (image == null) {
            return;
        }
        double texPerBlock = (TERRAIN_TEX / 2.0) / (range * 1.4);
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        int[] lastHeights = new int[TERRAIN_TEX];
        java.util.Arrays.fill(lastHeights, Integer.MIN_VALUE);
        for (int ty = 0; ty < TERRAIN_TEX; ty++) {
            for (int tx = 0; tx < TERRAIN_TEX; tx++) {
                int wx = (int) Math.floor(px + (tx - TERRAIN_TEX / 2.0) / texPerBlock);
                int wz = (int) Math.floor(pz + (ty - TERRAIN_TEX / 2.0) / texPerBlock);
                image.setPixelRGBA(tx, ty, sampleTerrainColor(client.level, pos, wx, wz, lastHeights, tx));
            }
        }
        terrainTexture.upload();
        terrainAnchorX = px;
        terrainAnchorZ = pz;
        terrainRange = range;
        terrainBuiltAt = System.currentTimeMillis();
    }

    private static int sampleTerrainColor(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos.MutableBlockPos pos, int x, int z, int[] lastHeights, int tx) {
        pos.set(x, 0, z);
        if (!level.hasChunkAt(pos)) {
            lastHeights[tx] = Integer.MIN_VALUE;
            return radarVoidColor;
        }
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        if (y <= level.getMinBuildHeight()) {
            lastHeights[tx] = Integer.MIN_VALUE;
            return radarVoidColor;
        }
        pos.set(x, y - 1, z);
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        net.minecraft.world.level.material.MapColor color = state.getMapColor(level, pos);
        if (color == net.minecraft.world.level.material.MapColor.NONE) {
            lastHeights[tx] = y;
            return radarVoidColor;
        }
        int north = lastHeights[tx] == Integer.MIN_VALUE
                ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z - 1)
                : lastHeights[tx];
        net.minecraft.world.level.material.MapColor.Brightness brightness;
        if (y > north) {
            brightness = net.minecraft.world.level.material.MapColor.Brightness.HIGH;
        } else if (y < north) {
            brightness = net.minecraft.world.level.material.MapColor.Brightness.LOW;
        } else {
            brightness = net.minecraft.world.level.material.MapColor.Brightness.NORMAL;
        }
        lastHeights[tx] = y;
        return darkenAbgr(color.calculateRGBColor(brightness), 0.78f);
    }
}