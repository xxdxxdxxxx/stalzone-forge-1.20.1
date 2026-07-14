package ru.zonewars.forge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
final class CapturePoint {
        private final CapturePointData data;
        private TeamColor owner;
        private TeamColor capturingTeam;
        private double progress;
        private CapturePointStatus status = CapturePointStatus.NEUTRAL;

        CapturePoint(CapturePointData data) {
            this.data = data;
        }

        CapturePointData data() {
            return data;
        }

        Optional<TeamColor> owner() {
            return Optional.ofNullable(owner);
        }

        double progress() {
            return progress;
        }

        TeamColor capturingTeam() {
            return capturingTeam;
        }

        CapturePointStatus status() {
            return status;
        }

        TickResult tick(Collection<ServerPlayer> players, Map<UUID, TeamColor> teams, int captureSeconds, double secondsPerTick) {
            Map<TeamColor, Integer> present = new EnumMap<>(TeamColor.class);

            for (ServerPlayer player : players) {
                TeamColor team = teams.get(player.getUUID());
                if (team == null || !worldMatches(player.serverLevel(), data.location().world())) {
                    continue;
                }
                if (!player.isAlive() || player.isSpectator()) {
                    continue;
                }
                double dx = player.getX() - data.location().x();
                double dy = player.getY() - data.location().y();
                double dz = player.getZ() - data.location().z();
                if (ZoneWarsRules.insideCaptureCylinder(dx, dy, dz, data.radius())) {
                    present.merge(team, 1, Integer::sum);
                }
            }

            TeamColor oldOwner = owner;
            CapturePointStatus oldStatus = status;
            TeamColor oldCapturingTeam = capturingTeam;
            double baseStep = (100.0 / Math.max(1, captureSeconds)) * Math.max(0.0, secondsPerTick);

            if (present.isEmpty()) {
                if (owner != null && capturingTeam != owner && progress > 0.0) {
                    progress = Math.max(0.0, progress - baseStep * 0.5);
                    status = CapturePointStatus.NEUTRALIZING;
                    if (progress == 0.0) {
                        capturingTeam = owner;
                        progress = 100.0;
                        status = CapturePointStatus.OWNED;
                    }
                    return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
                }
                if (owner == null && progress > 0.0) {
                    progress = Math.max(0.0, progress - baseStep * 0.5);
                    if (progress == 0.0) {
                        capturingTeam = null;
                        status = CapturePointStatus.NEUTRAL;
                    } else {
                        status = CapturePointStatus.NEUTRALIZING;
                    }
                    return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
                }
                status = owner == null ? CapturePointStatus.NEUTRAL : CapturePointStatus.OWNED;
                capturingTeam = owner;
                progress = owner == null ? 0.0 : 100.0;
                return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
            }

            int red = present.getOrDefault(TeamColor.RED, 0);
            int blue = present.getOrDefault(TeamColor.BLUE, 0);
            if (red == blue) {
                status = CapturePointStatus.CONTESTED;
                return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
            }

            TeamColor team = red > blue ? TeamColor.RED : TeamColor.BLUE;
            int advantage = Math.abs(red - blue);
            double step = baseStep * Math.max(1, advantage);
            if (team == owner && capturingTeam == owner) {
                capturingTeam = team;
                progress = 100.0;
                status = CapturePointStatus.OWNED;
                return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
            }

            if (team == owner) {
                status = CapturePointStatus.NEUTRALIZING;
                progress = Math.max(0.0, progress - step);
                if (progress <= 0.0) {
                    capturingTeam = owner;
                    progress = 100.0;
                    status = CapturePointStatus.OWNED;
                }
                return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
            }

            if (capturingTeam != team) {
                if (owner != null && capturingTeam == owner) {
                    capturingTeam = team;
                    progress = 0.0;
                } else if (progress <= step) {
                    capturingTeam = team;
                    progress = 0.0;
                } else {
                    status = CapturePointStatus.NEUTRALIZING;
                    progress = Math.max(0.0, progress - step);
                    return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
                }
            }

            status = CapturePointStatus.CAPTURING;
            progress = Math.min(100.0, progress + step);

            Optional<TeamColor> captured = Optional.empty();
            if (progress >= 100.0) {
                owner = team;
                progress = 100.0;
                status = CapturePointStatus.OWNED;
                captured = Optional.of(team);
            }
            return new TickResult(captured, changed(oldOwner, oldStatus, oldCapturingTeam), status);
        }

        private boolean changed(TeamColor oldOwner, CapturePointStatus oldStatus, TeamColor oldCapturingTeam) {
            return oldStatus != status || oldOwner != owner || oldCapturingTeam != capturingTeam;
        }

        static boolean worldMatches(ServerLevel world, String configuredLevel) {
            if (configuredLevel == null || configuredLevel.isBlank() || configuredLevel.equals("world")) {
                return world.dimension() == Level.OVERWORLD;
            }
            ResourceLocation id = world.dimension().location();
            return id.toString().equals(configuredLevel) || id.getPath().equals(configuredLevel);
        }

        record TickResult(Optional<TeamColor> capturedBy, boolean statusChanged, CapturePointStatus status) {
        }
    }
