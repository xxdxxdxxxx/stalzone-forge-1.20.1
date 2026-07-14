package ru.zonewars.forge;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class ClanService {
        private final PersistentStore storage;
        private final Map<String, Clan> clans = new HashMap<>();
        private final Map<UUID, String> playerClans = new HashMap<>();

        ClanService(PersistentStore storage) {
            this.storage = storage;
        }

        void load(PersistentStore.ClanState state) {
            clans.clear();
            clans.putAll(state.clans());
            playerClans.clear();
            playerClans.putAll(state.playerClans());
        }

        CreateResult create(ServerPlayer player, String rawTag, String rawColor) {
            String tag = sanitizeTag(rawTag);
            String color = rawColor == null ? "WHITE" : rawColor.toUpperCase(Locale.ROOT);
            if (tag.isBlank() || tag.length() > 12) {
                return CreateResult.BAD_TAG;
            }
            if (clans.containsKey(tag.toLowerCase(Locale.ROOT))) {
                return CreateResult.EXISTS;
            }
            Clan clan = new Clan(tag, color, 0, 0);
            clans.put(tag.toLowerCase(Locale.ROOT), clan);
            playerClans.put(player.getUUID(), tag.toLowerCase(Locale.ROOT));
            save();
            return CreateResult.CREATED;
        }

        boolean join(ServerPlayer player, String rawTag) {
            String tag = sanitizeTag(rawTag).toLowerCase(Locale.ROOT);
            if (!clans.containsKey(tag)) {
                return false;
            }
            playerClans.put(player.getUUID(), tag);
            save();
            return true;
        }

        void leave(UUID playerId) {
            playerClans.remove(playerId);
            save();
        }

        Optional<Clan> clanOf(UUID playerId) {
            String tag = playerClans.get(playerId);
            return tag == null ? Optional.empty() : Optional.ofNullable(clans.get(tag));
        }

        void recordMatchResults(Map<UUID, Boolean> winnersByPlayer) {
            Set<String> wonClans = new HashSet<>();
            Set<String> lostClans = new HashSet<>();
            for (Map.Entry<UUID, Boolean> entry : winnersByPlayer.entrySet()) {
                Optional<Clan> clan = clanOf(entry.getKey());
                if (clan.isEmpty()) {
                    continue;
                }
                if (entry.getValue()) {
                    wonClans.add(clan.get().tag().toLowerCase(Locale.ROOT));
                } else {
                    lostClans.add(clan.get().tag().toLowerCase(Locale.ROOT));
                }
            }
            wonClans.forEach(tag -> clans.computeIfPresent(tag, (key, clan) -> clan.win()));
            lostClans.stream().filter(tag -> !wonClans.contains(tag)).forEach(tag -> clans.computeIfPresent(tag, (key, clan) -> clan.loss()));
            save();
        }

        String statsSummary() {
            if (clans.isEmpty()) {
                return "No clans yet.";
            }
            List<Clan> rows = new ArrayList<>(clans.values());
            rows.sort(Comparator.comparingInt(Clan::wins).reversed());
            List<String> rendered = new ArrayList<>();
            for (Clan clan : rows) {
                rendered.add(clan.tag() + " " + clan.wins() + "/" + clan.losses());
            }
            return String.join(" | ", rendered);
        }

        private String sanitizeTag(String tag) {
            return tag == null ? "" : tag.replaceAll("[^A-Za-z0-9_-]", "");
        }

        PersistentStore.ClanState snapshot() {
            return new PersistentStore.ClanState(Map.copyOf(clans), Map.copyOf(playerClans));
        }

        private void save() {
            storage.saveClans(snapshot());
        }

        enum CreateResult {
            CREATED,
            BAD_TAG,
            EXISTS
        }
    }

    record Clan(String tag, String color, int wins, int losses) {
        Clan win() {
            return new Clan(tag, color, wins + 1, losses);
        }

        Clan loss() {
            return new Clan(tag, color, wins, losses + 1);
        }

        String summary() {
            return "[" + tag + "] color=" + color + " W/L=" + wins + "/" + losses;
        }
    }
