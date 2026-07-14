package ru.zonewars.forge;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
final class ForgeMatchService {
        private final EconomyService economy;
        private final MatchStatsService stats;
        private final Map<UUID, TeamColor> teams = new HashMap<>();
        private final EnumMap<TeamColor, Integer> scores = new EnumMap<>(TeamColor.class);
        private final List<CapturePoint> points = new ArrayList<>();
        private ArenaData arena = ArenaData.refinery();
        private MatchPhase phase = MatchPhase.WAITING;
        private int phaseSecondsRemaining;

        ForgeMatchService(EconomyService economy, MatchStatsService stats) {
            this.economy = economy;
            this.stats = stats;
            resetScores();
            resetPoints();
        }

        ArenaData arena() {
            return arena;
        }

        void loadArena(ArenaData loadedArena) {
            arena = loadedArena;
            resetPoints();
        }

        MatchPhase phase() {
            return phase;
        }

        int score(TeamColor team) {
            return scores.getOrDefault(team, 0);
        }

        int phaseSecondsRemaining() {
            return phaseSecondsRemaining;
        }

        List<CapturePoint> points() {
            return List.copyOf(points);
        }

        boolean isParticipant(UUID playerId) {
            return teams.containsKey(playerId);
        }

        Collection<UUID> players() {
            return List.copyOf(teams.keySet());
        }

        Optional<TeamColor> teamOf(UUID playerId) {
            return Optional.ofNullable(teams.get(playerId));
        }

        boolean isFriendly(ServerPlayer first, ServerPlayer second) {
            Optional<TeamColor> firstTeam = teamOf(first.getUUID());
            Optional<TeamColor> secondTeam = teamOf(second.getUUID());
            return firstTeam.isPresent() && firstTeam.equals(secondTeam);
        }

        boolean canShoot(UUID playerId) {
            return isParticipant(playerId) && (phase == MatchPhase.ACTIVE || phase == MatchPhase.OVERTIME);
        }

        TeamColor autoTeam() {
            return teamSize(TeamColor.RED) <= teamSize(TeamColor.BLUE) ? TeamColor.RED : TeamColor.BLUE;
        }

        JoinResult join(ServerPlayer player, TeamColor team) {
            if (phase == MatchPhase.ACTIVE || phase == MatchPhase.OVERTIME || phase == MatchPhase.ENDED) {
                return JoinResult.MATCH_ACTIVE;
            }
            if (teamSize(team) >= arena.maxPlayersPerTeam()) {
                return JoinResult.TEAM_FULL;
            }

            teams.put(player.getUUID(), team);
            economy.resetPlayer(player.getUUID());
            teleportToBase(player);
            return JoinResult.JOINED;
        }

        void leave(ServerPlayer player) {
            teams.remove(player.getUUID());
        }

        boolean isNearShop(ServerPlayer player, double maxDistance) {
            if (arena.shopLocations().isEmpty()) {
                return true;
            }
            double maxDistanceSquared = maxDistance * maxDistance;
            for (LocationSpec shop : arena.shopLocations()) {
                if (!CapturePoint.worldMatches(player.serverLevel(), shop.world())) {
                    continue;
                }
                double dx = player.getX() - shop.x();
                double dy = player.getY() - shop.y();
                double dz = player.getZ() - shop.z();
                if (dx * dx + dy * dy + dz * dz <= maxDistanceSquared) {
                    return true;
                }
            }
            return false;
        }

        StartResult start(MinecraftServer server) {
            if (phase != MatchPhase.WAITING) {
                return StartResult.MATCH_RUNNING;
            }
            if (teamSize(TeamColor.RED) < arena.minPlayersPerTeam() || teamSize(TeamColor.BLUE) < arena.minPlayersPerTeam()) {
                return StartResult.NOT_ENOUGH_PLAYERS;
            }
            resetScores();
            resetPoints();
            phase = MatchPhase.PREPARING;
            phaseSecondsRemaining = arena.preparationSeconds();
            teleportAllToBases(server);
            broadcast(server, "Preparation started.");
            return StartResult.STARTED;
        }

        void stop(MinecraftServer server) {
            resetToWaiting(server);
            broadcast(server, "Arena reset.");
        }

        void tick(MinecraftServer server) {
            if (phase == MatchPhase.ENDED) {
                phaseSecondsRemaining--;
                if (phaseSecondsRemaining <= 0) {
                    resetToWaiting(server);
                }
                return;
            }
            if (phase != MatchPhase.PREPARING && phase != MatchPhase.ACTIVE && phase != MatchPhase.OVERTIME) {
                return;
            }

            phaseSecondsRemaining--;
            if (phase == MatchPhase.PREPARING && phaseSecondsRemaining <= 0) {
                phase = MatchPhase.ACTIVE;
                phaseSecondsRemaining = arena.matchSeconds();
                broadcast(server, "Match started. Capture the points.");
                return;
            }

            if (phase == MatchPhase.ACTIVE || phase == MatchPhase.OVERTIME) {
                tickIncome();
                if (phaseSecondsRemaining <= 0) {
                    if (phase == MatchPhase.ACTIVE && leadingTeam().isEmpty() && arena.overtimeSeconds() > 0) {
                        phase = MatchPhase.OVERTIME;
                        phaseSecondsRemaining = arena.overtimeSeconds();
                        broadcast(server, "Overtime started.");
                    } else {
                        finishByScore(server);
                    }
                } else if (phase == MatchPhase.OVERTIME && leadingTeam().isPresent()) {
                    finishByScore(server);
                }
            }
        }

        void fastTick(MinecraftServer server, double secondsPerTick) {
            if (phase != MatchPhase.ACTIVE && phase != MatchPhase.OVERTIME) {
                return;
            }
            Collection<ServerPlayer> online = server.getPlayerList().getPlayers();
            for (CapturePoint point : points) {
                CapturePoint.TickResult result = point.tick(online, teams, arena.captureSeconds(), secondsPerTick);
                if (result.statusChanged()) {
                    announcePoint(server, point, result.status());
                }
                result.capturedBy().ifPresent(team -> {
                    rewardCapture(server, point, team);
                    broadcast(server, team.name() + " captured " + point.data().displayName() + ".");
                });
            }
        }

        String summary() {
            return phase.name() + " | RED " + score(TeamColor.RED) + " - BLUE " + score(TeamColor.BLUE) + " | " + teams.size() + " players";
        }

        void setSpawn(TeamColor team, LocationSpec location) {
            arena = team == TeamColor.RED ? arena.withRedSpawn(location) : arena.withBlueSpawn(location);
        }

        void addPoint(CapturePointData point) {
            List<CapturePointData> newPoints = new ArrayList<>(arena.capturePoints());
            newPoints.add(point);
            arena = arena.withCapturePoints(newPoints);
            resetPoints();
        }

        void addShop(LocationSpec location) {
            List<LocationSpec> shops = new ArrayList<>(arena.shopLocations());
            shops.add(location);
            arena = arena.withShopLocations(shops);
        }

        private long teamSize(TeamColor team) {
            return teams.values().stream().filter(existing -> existing == team).count();
        }

        private void tickIncome() {
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            for (CapturePoint point : points) {
                if (point.status() != CapturePointStatus.OWNED) {
                    continue;
                }
                point.owner().ifPresent(owner -> {
                    scores.merge(owner, arena.pointsPerSecond(), Integer::sum);
                    if (server == null) {
                        return;
                    }
                    LocationSpec location = point.data().location();
                    double radiusSquared = point.data().radius() * point.data().radius();
                    for (Map.Entry<UUID, TeamColor> entry : teams.entrySet()) {
                        if (entry.getValue() != owner) {
                            continue;
                        }
                        ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                        if (player == null || !CapturePoint.worldMatches(player.serverLevel(), location.world())) {
                            continue;
                        }
                        double dx = player.getX() - location.x();
                        double dz = player.getZ() - location.z();
                        if (dx * dx + dz * dz <= radiusSquared) {
                            economy.add(entry.getKey(), economy.captureIncomePerSecond());
                        }
                    }
                });
            }
        }

        private void rewardCapture(MinecraftServer server, CapturePoint point, TeamColor team) {
            double radiusSquared = point.data().radius() * point.data().radius();
            for (UUID playerId : teams.keySet()) {
                if (teams.get(playerId) != team) {
                    continue;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !point.worldMatches(player.serverLevel(), point.data().location().world())) {
                    continue;
                }
                double dx = player.getX() - point.data().location().x();
                double dz = player.getZ() - point.data().location().z();
                if (dx * dx + dz * dz <= radiusSquared) {
                    economy.add(playerId, economy.captureReward());
                    tell(player, "Capture reward: +" + economy.captureReward());
                }
            }
        }

        private Optional<TeamColor> leadingTeam() {
            int red = score(TeamColor.RED);
            int blue = score(TeamColor.BLUE);
            if (red == blue) {
                return Optional.empty();
            }
            return Optional.of(red > blue ? TeamColor.RED : TeamColor.BLUE);
        }

        private void finishByScore(MinecraftServer server) {
            phase = MatchPhase.ENDED;
            phaseSecondsRemaining = arena.endScreenSeconds();
            Optional<TeamColor> winner = leadingTeam();
            stats.handleMatchEnd(winner, score(TeamColor.RED), score(TeamColor.BLUE), Map.copyOf(teams), server);
            broadcast(server, winner.map(team -> team.name() + " wins.").orElse("Draw."));
        }

        private void resetToWaiting(MinecraftServer server) {
            phase = MatchPhase.WAITING;
            phaseSecondsRemaining = 0;
            resetScores();
            resetPoints();
            teleportAllToBases(server);
        }

        private void resetScores() {
            scores.put(TeamColor.RED, 0);
            scores.put(TeamColor.BLUE, 0);
        }

        private void resetPoints() {
            points.clear();
            arena.capturePoints().forEach(point -> points.add(new CapturePoint(point)));
        }

        private void teleportAllToBases(MinecraftServer server) {
            for (Map.Entry<UUID, TeamColor> entry : teams.entrySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    teleportToBase(player);
                }
            }
        }

        void teleportToBase(ServerPlayer player) {
            TeamColor team = teamOf(player.getUUID()).orElse(TeamColor.RED);
            LocationSpec spawn = team == TeamColor.RED ? arena.redSpawn() : arena.blueSpawn();
            teleport(player, spawn);
        }

        void teleport(ServerPlayer player, LocationSpec location) {
            ServerLevel world = resolveLevel(player.server, location.world()).orElse(player.serverLevel());
            player.teleportTo(world, location.x(), location.y(), location.z(), location.yaw(), location.pitch());
        }

        Optional<ServerLevel> resolveLevel(MinecraftServer server, String worldName) {
            if (worldName == null || worldName.isBlank() || worldName.equals("world")) {
                return Optional.of(server.overworld());
            }
            ResourceLocation worldId = ResourceLocation.tryParse(worldName);
            if (worldId == null) {
                return Optional.empty();
            }
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, worldId);
            return Optional.ofNullable(server.getLevel(key));
        }

        private void announcePoint(MinecraftServer server, CapturePoint point, CapturePointStatus status) {
            switch (status) {
                case CONTESTED -> broadcast(server, point.data().displayName() + " is contested.");
                case NEUTRALIZING -> broadcast(server, point.data().displayName() + " is being neutralized.");
                case OWNED -> point.owner().ifPresent(team -> broadcast(server, team.name() + " controls " + point.data().displayName() + "."));
                default -> {
                }
            }
        }

        void broadcast(MinecraftServer server, String message) {
            for (UUID playerId : teams.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    tell(player, message);
                }
            }
        }
        private static void tell(ServerPlayer player, String message) {
            player.sendSystemMessage(Component.literal("[ZoneWars] " + message));
        }


        enum JoinResult {
            JOINED,
            TEAM_FULL,
            MATCH_ACTIVE
        }

        enum StartResult {
            STARTED,
            NOT_ENOUGH_PLAYERS,
            MATCH_RUNNING
        }
    }


