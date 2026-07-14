package ru.zonewars.forge;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class MatchStatsService {
        private static final int FEED_LIMIT = 6;
        private static final int FEED_TTL_SECONDS = 12;
        private static final int DAMAGE_DEDUP_WINDOW_MILLIS = 75;

        private final ClanService clans;
        private final PersistentStore storage;
        private final Map<UUID, PlayerMatchStats> stats = new HashMap<>();
        private final Map<UUID, PlayerTotalStats> totals = new HashMap<>();
        private final Map<UUID, HitMarker> hitMarkers = new HashMap<>();
        private final Map<UUID, Integer> hitSequences = new HashMap<>();
        private final Map<String, Instant> recentDamageHits = new HashMap<>();
        private final Map<UUID, Instant> recentVictimKills = new HashMap<>();
        private final LinkedList<KillFeedEntry> killFeed = new LinkedList<>();

        MatchStatsService(ClanService clans, PersistentStore storage) {
            this.clans = clans;
            this.storage = storage;
        }

        void resetMatch() {
            stats.clear();
            hitMarkers.clear();
            hitSequences.clear();
            recentDamageHits.clear();
            recentVictimKills.clear();
            killFeed.clear();
        }

        void recordDamage(ServerPlayer attacker, ServerPlayer victim, int damage) {
            if (attacker.equals(victim)) {
                return;
            }
            String hitKey = attacker.getUUID() + ">" + victim.getUUID();
            Instant now = Instant.now();
            recentDamageHits.entrySet().removeIf(entry ->
                Duration.between(entry.getValue(), now).toMillis() > 2_000L);
            Instant recent = recentDamageHits.get(hitKey);
            if (recent != null && Duration.between(recent, now).toMillis() < DAMAGE_DEDUP_WINDOW_MILLIS) {
                return;
            }
            recentDamageHits.put(hitKey, now);
            stats(attacker.getUUID()).addDamage(damage);
            int nextSequence = hitSequences.merge(attacker.getUUID(), 1, Integer::sum);
            hitMarkers.put(attacker.getUUID(), new HitMarker(nextSequence, damage, false));
        }

        boolean recordKill(ServerPlayer killer, ServerPlayer victim, String weapon, ForgeMatchService matches) {
            if (killer.equals(victim)) {
                return false;
            }
            Instant now = Instant.now();
            recentVictimKills.entrySet().removeIf(entry ->
                Duration.between(entry.getValue(), now).toSeconds() > 10L);
            Instant recent = recentVictimKills.get(victim.getUUID());
            if (recent != null && Duration.between(recent, now).toMillis() < 900) {
                return false;
            }
            recentVictimKills.put(victim.getUUID(), now);

            stats(killer.getUUID()).addKill();
            stats(victim.getUUID()).addDeath();
            int nextSequence = hitSequences.merge(killer.getUUID(), 1, Integer::sum);
            hitMarkers.put(killer.getUUID(), new HitMarker(nextSequence, 0, true));

            TeamColor killerTeam = matches.teamOf(killer.getUUID()).orElse(TeamColor.RED);
            TeamColor victimTeam = matches.teamOf(victim.getUUID()).orElse(TeamColor.BLUE);
            killFeed.addFirst(new KillFeedEntry(killer.getName().getString(), killerTeam, victim.getName().getString(), victimTeam, cleanWeapon(weapon), now));
            while (killFeed.size() > FEED_LIMIT) {
                killFeed.removeLast();
            }
            return true;
        }

        List<KillFeedEntry> killFeed() {
            Instant now = Instant.now();
            killFeed.removeIf(entry -> Duration.between(entry.createdAt(), now).toSeconds() > FEED_TTL_SECONDS);
            return List.copyOf(killFeed);
        }

        HitMarker hitMarker(UUID playerId) {
            return hitMarkers.getOrDefault(playerId, new HitMarker(0, 0, false));
        }

        PlayerMatchStats stats(UUID playerId) {
            return stats.computeIfAbsent(playerId, ignored -> new PlayerMatchStats());
        }

        void loadTotals(Map<UUID, PlayerTotalStats> loadedTotals) {
            totals.clear();
            totals.putAll(loadedTotals);
        }

        PlayerTotalStats totalStats(UUID playerId, String fallbackName) {
            return totals.getOrDefault(playerId, new PlayerTotalStats(fallbackName, 0, 0, 0, 0, 0));
        }

        private Map<UUID, PlayerTotalStats> totalsSnapshot() {
            return Map.copyOf(totals);
        }

        void handleMatchEnd(Optional<TeamColor> winner, int redScore, int blueScore, Map<UUID, TeamColor> teams, MinecraftServer server) {
            Map<UUID, Boolean> winnersByPlayer = new HashMap<>();
            for (Map.Entry<UUID, TeamColor> entry : teams.entrySet()) {
                boolean won = winner.map(value -> value == entry.getValue()).orElse(false);
                winnersByPlayer.put(entry.getKey(), won);
                PlayerMatchStats matchStats = stats(entry.getKey());
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                String name = player == null ? entry.getKey().toString().substring(0, 8) : player.getName().getString();
                totals.merge(
                    entry.getKey(),
                    PlayerTotalStats.fromMatch(name, matchStats, won),
                    (oldStats, addStats) -> oldStats.plus(name, matchStats, won)
                );
            }
            clans.recordMatchResults(winnersByPlayer);
            storage.savePlayerStats(totals);
            storage.recordMatch(winner, redScore, blueScore);
            broadcastSummary(teams, server);
        }

        private void broadcastSummary(Map<UUID, TeamColor> teams, MinecraftServer server) {
            List<Map.Entry<UUID, PlayerMatchStats>> leaders = new ArrayList<>(stats.entrySet());
            leaders.sort(Comparator
                .<Map.Entry<UUID, PlayerMatchStats>>comparingInt(entry -> entry.getValue().kills()).reversed()
                .thenComparing(entry -> entry.getValue().deaths()));
            StringBuilder line = new StringBuilder("Top match stats: ");
            for (int i = 0; i < Math.min(3, leaders.size()); i++) {
                Map.Entry<UUID, PlayerMatchStats> entry = leaders.get(i);
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                String name = player == null ? entry.getKey().toString().substring(0, 8) : player.getName().getString();
                if (i > 0) {
                    line.append(" | ");
                }
                line.append(name).append(" ").append(entry.getValue().kills()).append("/").append(entry.getValue().deaths());
            }
            for (UUID playerId : teams.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    tell(player, line.toString());
                }
            }
        }

        private String cleanWeapon(String weapon) {
            if (weapon == null || weapon.isBlank() || weapon.equals("null")) {
                return "weapon";
            }
            int colon = weapon.indexOf(':');
            return (colon >= 0 ? weapon.substring(colon + 1) : weapon).replace('_', ' ');
        }

    private static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}
