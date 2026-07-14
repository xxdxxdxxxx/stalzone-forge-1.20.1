package ru.zonewars.forge;

import com.google.gson.*;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class PersistentStore {
        private static final String PREFIX = "[ZoneWars] ";
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private Path dir;

        void open() {
            dir = FMLPaths.CONFIGDIR.get().resolve("zonewars");
            try {
                Files.createDirectories(dir);
            } catch (IOException exception) {
                System.err.println(PREFIX + "Could not create storage directory: " + exception.getMessage());
            }
        }

        Optional<ArenaData> loadArena() {
            ArenaData fallback = ArenaData.refinery();
            return readObject("arena.json").map(root -> {
                List<LocationSpec> shops = new ArrayList<>();
                for (JsonElement element : array(root, "shopLocations")) {
                    if (element.isJsonObject()) {
                        shops.add(location(element.getAsJsonObject(), new LocationSpec("world", 0.5, 63.0, 0.5, 0.0f, 0.0f)));
                    }
                }
                if (shops.isEmpty()) {
                    shops = fallback.shopLocations();
                }

                List<CapturePointData> points = new ArrayList<>();
                for (JsonElement element : array(root, "capturePoints")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject point = element.getAsJsonObject();
                    points.add(new CapturePointData(
                        stringValue(point, "id", "point" + points.size()),
                        stringValue(point, "displayName", stringValue(point, "id", "Point")),
                        location(object(point, "location"), fallback.capturePoints().get(Math.min(points.size(), fallback.capturePoints().size() - 1)).location()),
                        ZoneWarsRules.captureRadius(doubleValue(point, "radius", 9.0))
                    ));
                }
                if (points.isEmpty()) {
                    points = fallback.capturePoints();
                }
                int maxPlayers = ZoneWarsRules.maxPlayersPerTeam(intValue(root, "maxPlayersPerTeam", fallback.maxPlayersPerTeam()));
                int minPlayers = ZoneWarsRules.minPlayersPerTeam(intValue(root, "minPlayersPerTeam", fallback.minPlayersPerTeam()), maxPlayers);
                return new ArenaData(
                    minPlayers,
                    maxPlayers,
                    ZoneWarsRules.preparationSeconds(intValue(root, "preparationSeconds", fallback.preparationSeconds())),
                    ZoneWarsRules.matchSeconds(intValue(root, "matchSeconds", fallback.matchSeconds())),
                    ZoneWarsRules.overtimeSeconds(intValue(root, "overtimeSeconds", fallback.overtimeSeconds())),
                    ZoneWarsRules.endScreenSeconds(intValue(root, "endScreenSeconds", fallback.endScreenSeconds())),
                    ZoneWarsRules.captureSeconds(intValue(root, "captureSeconds", fallback.captureSeconds())),
                    ZoneWarsRules.pointsPerSecond(intValue(root, "pointsPerSecond", fallback.pointsPerSecond())),
                    location(object(root, "redSpawn"), fallback.redSpawn()),
                    location(object(root, "blueSpawn"), fallback.blueSpawn()),
                    List.copyOf(shops),
                    List.copyOf(points)
                );
            });
        }

        void saveArena(ArenaData arena) {
            JsonObject root = new JsonObject();
            root.addProperty("minPlayersPerTeam", arena.minPlayersPerTeam());
            root.addProperty("maxPlayersPerTeam", arena.maxPlayersPerTeam());
            root.addProperty("preparationSeconds", arena.preparationSeconds());
            root.addProperty("matchSeconds", arena.matchSeconds());
            root.addProperty("overtimeSeconds", arena.overtimeSeconds());
            root.addProperty("endScreenSeconds", arena.endScreenSeconds());
            root.addProperty("captureSeconds", arena.captureSeconds());
            root.addProperty("pointsPerSecond", arena.pointsPerSecond());
            root.add("redSpawn", locationJson(arena.redSpawn()));
            root.add("blueSpawn", locationJson(arena.blueSpawn()));
            JsonArray shops = new JsonArray();
            for (LocationSpec shop : arena.shopLocations()) {
                shops.add(locationJson(shop));
            }
            root.add("shopLocations", shops);
            JsonArray points = new JsonArray();
            for (CapturePointData point : arena.capturePoints()) {
                JsonObject row = new JsonObject();
                row.addProperty("id", point.id());
                row.addProperty("displayName", point.displayName());
                row.addProperty("radius", point.radius());
                row.add("location", locationJson(point.location()));
                points.add(row);
            }
            root.add("capturePoints", points);
            writeObject("arena.json", root);
        }

        ClanState loadClans() {
            Map<String, Clan> loadedClans = new HashMap<>();
            Map<UUID, String> loadedPlayerClans = new HashMap<>();
            readObject("clans.json").ifPresent(root -> {
                for (Map.Entry<String, JsonElement> entry : object(root, "clans").entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject row = entry.getValue().getAsJsonObject();
                    Clan clan = new Clan(
                        stringValue(row, "tag", entry.getKey()),
                        stringValue(row, "color", "WHITE"),
                        intValue(row, "wins", 0),
                        intValue(row, "losses", 0)
                    );
                    loadedClans.put(clan.tag().toLowerCase(Locale.ROOT), clan);
                }
                for (Map.Entry<String, JsonElement> entry : object(root, "playerClans").entrySet()) {
                    try {
                        loadedPlayerClans.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString().toLowerCase(Locale.ROOT));
                    } catch (RuntimeException ignored) {
                    }
                }
            });
            return new ClanState(Map.copyOf(loadedClans), Map.copyOf(loadedPlayerClans));
        }

        void saveClans(ClanState state) {
            JsonObject root = new JsonObject();
            JsonObject clanRows = new JsonObject();
            for (Map.Entry<String, Clan> entry : state.clans().entrySet()) {
                Clan clan = entry.getValue();
                JsonObject row = new JsonObject();
                row.addProperty("tag", clan.tag());
                row.addProperty("color", clan.color());
                row.addProperty("wins", clan.wins());
                row.addProperty("losses", clan.losses());
                clanRows.add(entry.getKey().toLowerCase(Locale.ROOT), row);
            }
            JsonObject playerRows = new JsonObject();
            for (Map.Entry<UUID, String> entry : state.playerClans().entrySet()) {
                playerRows.addProperty(entry.getKey().toString(), entry.getValue());
            }
            root.add("clans", clanRows);
            root.add("playerClans", playerRows);
            writeObject("clans.json", root);
        }

        Map<UUID, PlayerTotalStats> loadPlayerStats() {
            Map<UUID, PlayerTotalStats> rows = new HashMap<>();
            readObject("player_stats.json").ifPresent(root -> {
                for (Map.Entry<String, JsonElement> entry : object(root, "players").entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    try {
                        UUID playerId = UUID.fromString(entry.getKey());
                        JsonObject row = entry.getValue().getAsJsonObject();
                        rows.put(playerId, new PlayerTotalStats(
                            stringValue(row, "name", playerId.toString().substring(0, 8)),
                            intValue(row, "kills", 0),
                            intValue(row, "deaths", 0),
                            intValue(row, "damage", 0),
                            intValue(row, "wins", 0),
                            intValue(row, "losses", 0)
                        ));
                    } catch (RuntimeException ignored) {
                    }
                }
            });
            return Map.copyOf(rows);
        }

        void savePlayerStats(Map<UUID, PlayerTotalStats> stats) {
            JsonObject root = new JsonObject();
            JsonObject players = new JsonObject();
            for (Map.Entry<UUID, PlayerTotalStats> entry : stats.entrySet()) {
                PlayerTotalStats total = entry.getValue();
                JsonObject row = new JsonObject();
                row.addProperty("name", total.name());
                row.addProperty("kills", total.kills());
                row.addProperty("deaths", total.deaths());
                row.addProperty("damage", total.damage());
                row.addProperty("wins", total.wins());
                row.addProperty("losses", total.losses());
                players.add(entry.getKey().toString(), row);
            }
            root.add("players", players);
            writeObject("player_stats.json", root);
        }

        void recordMatch(Optional<TeamColor> winner, int redScore, int blueScore) {
            JsonObject root = readObject("matches.json").orElseGet(JsonObject::new);
            JsonArray rows = array(root, "matches");
            JsonObject match = new JsonObject();
            match.addProperty("winner", winner.map(Enum::name).orElse("DRAW"));
            match.addProperty("redScore", redScore);
            match.addProperty("blueScore", blueScore);
            match.addProperty("createdAt", Instant.now().toString());
            rows.add(match);
            while (rows.size() > 100) {
                rows.remove(0);
            }
            root.add("matches", rows);
            writeObject("matches.json", root);
        }

        void ensureMatchHistory() {
            if (readObject("matches.json").isPresent()) {
                return;
            }
            JsonObject root = new JsonObject();
            root.add("matches", new JsonArray());
            writeObject("matches.json", root);
        }

        private Optional<JsonObject> readObject(String name) {
            if (dir == null) {
                return Optional.empty();
            }
            Path primary = dir.resolve(name);
            Optional<JsonObject> loaded = readObjectPath(primary);
            if (loaded.isPresent()) {
                return loaded;
            }
            Path backup = dir.resolve(name + ".bak");
            Optional<JsonObject> recovered = readObjectPath(backup);
            if (recovered.isPresent()) {
                System.err.println(PREFIX + "Recovered " + name + " from backup.");
            }
            return recovered;
        }

        private Optional<JsonObject> readObjectPath(Path path) {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement element = JsonParser.parseReader(reader);
                return element != null && element.isJsonObject() ? Optional.of(element.getAsJsonObject()) : Optional.empty();
            } catch (RuntimeException | IOException exception) {
                System.err.println(PREFIX + "Could not read " + path + ": " + exception.getMessage());
                return Optional.empty();
            }
        }

        private void writeObject(String name, JsonObject root) {
            if (dir == null) {
                return;
            }
            try {
                Files.createDirectories(dir);
                Path target = dir.resolve(name);
                Path temporary = dir.resolve(name + ".tmp");
                Path backup = dir.resolve(name + ".bak");
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    GSON.toJson(root, writer);
                }
                if (Files.isRegularFile(target)) {
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                System.err.println(PREFIX + "Could not write " + name + ": " + exception.getMessage());
            }
        }

        private static JsonObject locationJson(LocationSpec location) {
            JsonObject object = new JsonObject();
            object.addProperty("world", location.world());
            object.addProperty("x", location.x());
            object.addProperty("y", location.y());
            object.addProperty("z", location.z());
            object.addProperty("yaw", location.yaw());
            object.addProperty("pitch", location.pitch());
            return object;
        }

        private static LocationSpec location(JsonObject object, LocationSpec fallback) {
            if (object == null) {
                return fallback;
            }
            return new LocationSpec(
                stringValue(object, "world", fallback.world()),
                ZoneWarsRules.coordinate(doubleValue(object, "x", fallback.x()), fallback.x()),
                ZoneWarsRules.yCoordinate(doubleValue(object, "y", fallback.y()), fallback.y()),
                ZoneWarsRules.coordinate(doubleValue(object, "z", fallback.z()), fallback.z()),
                (float) ZoneWarsRules.yaw(doubleValue(object, "yaw", fallback.yaw()), fallback.yaw()),
                (float) ZoneWarsRules.pitch(doubleValue(object, "pitch", fallback.pitch()), fallback.pitch())
            );
        }

        private static JsonObject object(JsonObject root, String key) {
            JsonElement element = root.get(key);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        }

        private static JsonArray array(JsonObject root, String key) {
            JsonElement element = root.get(key);
            return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
        }

        private static String stringValue(JsonObject root, String key, String fallback) {
            JsonElement element = root.get(key);
            return element == null || element.isJsonNull() ? fallback : element.getAsString();
        }

        private static int intValue(JsonObject root, String key, int fallback) {
            JsonElement element = root.get(key);
            return element == null || element.isJsonNull() ? fallback : element.getAsInt();
        }

        private static double doubleValue(JsonObject root, String key, double fallback) {
            JsonElement element = root.get(key);
            return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
        }

        private static int boundedInt(JsonObject root, String key, int fallback, int minimum, int maximum) {
            try {
                return Math.max(minimum, Math.min(maximum, intValue(root, key, fallback)));
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }

        private static double boundedDouble(JsonObject root, String key, double fallback, double minimum, double maximum) {
            try {
                double value = doubleValue(root, key, fallback);
                if (!Double.isFinite(value)) {
                    return fallback;
                }
                return Math.max(minimum, Math.min(maximum, value));
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }

        record ClanState(Map<String, Clan> clans, Map<UUID, String> playerClans) {
        }
    }
