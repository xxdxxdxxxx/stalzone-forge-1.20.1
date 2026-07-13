package ru.zonewars.client.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public final class ZoneWarsState {

    private static volatile Snapshot snapshot = Snapshot.empty();

    private ZoneWarsState() {
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static void update(String raw) {
        if (raw != null && raw.trim().startsWith("{")) {
            updateJson(raw);
            return;
        }
        String phase = "WAITING";
        String team = "NONE";
        String selectedRespawn = "BASE";
        int redScore = 0;
        int blueScore = 0;
        int seconds = 0;
        int selfYaw = 0;
        boolean respawnPrompt = false;
        String mapTexture = "";
        Bounds bounds = new Bounds(-64, -64, 64, 64);
        List<BaseState> bases = new ArrayList<>();
        List<PointState> points = new ArrayList<>();
        List<PlayerState> players = new ArrayList<>();
        List<RespawnState> respawns = new ArrayList<>();
        List<MarkerState> markers = new ArrayList<>();
        List<KillFeedState> killFeed = new ArrayList<>();
        HitState hit = new HitState(0, 0, false);
        StatsState selfStats = new StatsState(0, 0, 0);

        for (String part : raw.split(";")) {
            int index = part.indexOf('=');
            if (index < 0) {
                continue;
            }
            String key = part.substring(0, index);
            String value = part.substring(index + 1);
            switch (key) {
                case "phase" -> phase = value;
                case "team" -> team = value;
                case "redScore" -> redScore = parseInt(value);
                case "blueScore" -> blueScore = parseInt(value);
                case "seconds" -> seconds = parseInt(value);
                case "selfYaw" -> selfYaw = parseInt(value);
                case "selectedRespawn" -> selectedRespawn = value;
                case "respawnPrompt" -> respawnPrompt = parseBool(value);
                case "mapTexture" -> mapTexture = value;
                case "bounds" -> bounds = parseBounds(value);
                case "bases" -> bases = parseBases(value);
                case "points" -> points = parsePoints(value);
                case "players" -> players = parsePlayers(value);
                case "respawns" -> respawns = parseRespawns(value);
                default -> {
                }
            }
        }

        snapshot = new Snapshot(
            phase,
            team,
            redScore,
            blueScore,
            seconds,
            selfYaw,
            selectedRespawn,
            respawnPrompt,
            mapTexture,
            bounds,
            List.copyOf(bases),
            List.copyOf(points),
            List.copyOf(players),
            List.copyOf(respawns),
            List.copyOf(markers),
            List.copyOf(killFeed),
            hit,
            selfStats
        );
    }

    private static void updateJson(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            String phase = string(root, "phase", "WAITING");
            String team = string(root, "team", "NONE");
            int redScore = integer(root, "redScore", 0);
            int blueScore = integer(root, "blueScore", 0);
            int seconds = integer(root, "seconds", 0);
            int selfYaw = integer(root, "selfYaw", 0);
            String selectedRespawn = string(root, "selectedRespawn", "BASE");
            boolean respawnPrompt = bool(root, "respawnPrompt", false);
            String mapTexture = string(root, "mapTexture", "");
            Bounds bounds = bounds(root.getAsJsonArray("bounds"));

            List<BaseState> bases = new ArrayList<>();
            for (JsonObject object : objects(root, "bases")) {
                bases.add(new BaseState(string(object, "team", "NONE"), string(object, "name", ""), integer(object, "x", 0), integer(object, "z", 0)));
            }

            List<PointState> points = new ArrayList<>();
            for (JsonObject object : objects(root, "points")) {
                points.add(new PointState(
                    string(object, "id", ""),
                    string(object, "name", ""),
                    string(object, "owner", "NEUTRAL"),
                    integer(object, "progress", 0),
                    integer(object, "x", 0),
                    integer(object, "z", 0),
                    string(object, "status", "NEUTRAL"),
                    string(object, "capturingTeam", "NONE")
                ));
            }

            List<PlayerState> players = new ArrayList<>();
            for (JsonObject object : objects(root, "players")) {
                players.add(new PlayerState(
                    string(object, "name", ""),
                    string(object, "team", "NONE"),
                    bool(object, "squad", false),
                    bool(object, "self", false),
                    integer(object, "x", 0),
                    integer(object, "z", 0),
                    integer(object, "yaw", 0),
                    integer(object, "health", 0)
                ));
            }

            List<RespawnState> respawns = new ArrayList<>();
            for (JsonObject object : objects(root, "respawns")) {
                respawns.add(new RespawnState(
                    string(object, "kind", ""),
                    string(object, "team", "NONE"),
                    string(object, "name", ""),
                    integer(object, "x", 0),
                    integer(object, "z", 0),
                    bool(object, "available", false),
                    integer(object, "seconds", 0),
                    integer(object, "health", 0),
                    integer(object, "maxHealth", 0)
                ));
            }

            List<MarkerState> markers = new ArrayList<>();
            for (JsonObject object : objects(root, "markers")) {
                markers.add(new MarkerState(
                    string(object, "type", "DANGER"),
                    string(object, "team", "NONE"),
                    string(object, "label", ""),
                    integer(object, "x", 0),
                    integer(object, "z", 0),
                    integer(object, "seconds", -1),
                    bool(object, "own", false)
                ));
            }

            List<KillFeedState> killFeed = new ArrayList<>();
            for (JsonObject object : objects(root, "killFeed")) {
                killFeed.add(new KillFeedState(
                    string(object, "killer", ""),
                    string(object, "killerTeam", "NONE"),
                    string(object, "victim", ""),
                    string(object, "victimTeam", "NONE"),
                    string(object, "weapon", ""),
                    integer(object, "seconds", 0)
                ));
            }

            JsonObject hitObject = root.has("hit") ? root.getAsJsonObject("hit") : new JsonObject();
            HitState hit = new HitState(integer(hitObject, "sequence", 0), integer(hitObject, "damage", 0), bool(hitObject, "kill", false));
            JsonObject statsObject = root.has("selfStats") ? root.getAsJsonObject("selfStats") : new JsonObject();
            StatsState selfStats = new StatsState(integer(statsObject, "kills", 0), integer(statsObject, "deaths", 0), integer(statsObject, "damage", 0));

            snapshot = new Snapshot(phase, team, redScore, blueScore, seconds, selfYaw, selectedRespawn, respawnPrompt, mapTexture, bounds, List.copyOf(bases), List.copyOf(points), List.copyOf(players), List.copyOf(respawns), List.copyOf(markers), List.copyOf(killFeed), hit, selfStats);
        } catch (RuntimeException ignored) {
            // Keep the previous snapshot so one malformed packet does not blank the HUD.
        }
    }

    private static Bounds parseBounds(String value) {
        String[] columns = value.split(",");
        if (columns.length < 4) {
            return new Bounds(-64, -64, 64, 64);
        }
        return new Bounds(parseInt(columns[0]), parseInt(columns[1]), parseInt(columns[2]), parseInt(columns[3]));
    }

    private static List<BaseState> parseBases(String value) {
        List<BaseState> bases = new ArrayList<>();
        for (String row : rows(value)) {
            String[] columns = row.split(",");
            if (columns.length < 4) {
                continue;
            }
            bases.add(new BaseState(columns[0], columns[1], parseInt(columns[2]), parseInt(columns[3])));
        }
        return bases;
    }

    private static List<PointState> parsePoints(String value) {
        List<PointState> points = new ArrayList<>();
        for (String row : rows(value)) {
            String[] columns = row.split(",");
            if (columns.length < 6) {
                continue;
            }
            String status = columns.length >= 7 ? columns[6] : ("NEUTRAL".equals(columns[2]) ? "NEUTRAL" : "OWNED");
            String capturingTeam = columns.length >= 8 ? columns[7] : "NONE";
            points.add(new PointState(columns[0], columns[1], columns[2], parseInt(columns[3]), parseInt(columns[4]), parseInt(columns[5]), status, capturingTeam));
        }
        return points;
    }

    private static List<PlayerState> parsePlayers(String value) {
        List<PlayerState> players = new ArrayList<>();
        for (String row : rows(value)) {
            String[] columns = row.split(",");
            if (columns.length < 8) {
                continue;
            }
            players.add(new PlayerState(
                columns[0],
                columns[1],
                parseBool(columns[2]),
                parseBool(columns[3]),
                parseInt(columns[4]),
                parseInt(columns[5]),
                parseInt(columns[6]),
                parseInt(columns[7])
            ));
        }
        return players;
    }

    private static List<RespawnState> parseRespawns(String value) {
        List<RespawnState> respawns = new ArrayList<>();
        for (String row : rows(value)) {
            String[] columns = row.split(",");
            if (columns.length < 6) {
                continue;
            }
            respawns.add(new RespawnState(
                columns[0],
                columns[1],
                columns[2],
                parseInt(columns[3]),
                parseInt(columns[4]),
                parseBool(columns[5]),
                columns.length >= 7 ? parseInt(columns[6]) : 0,
                columns.length >= 8 ? parseInt(columns[7]) : 0,
                columns.length >= 9 ? parseInt(columns[8]) : 0
            ));
        }
        return respawns;
    }

    private static List<String> rows(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("\\|"));
    }

    private static boolean parseBool(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static List<JsonObject> objects(JsonObject root, String key) {
        JsonArray array = root.has(key) && root.get(key).isJsonArray() ? root.getAsJsonArray(key) : new JsonArray();
        List<JsonObject> objects = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                objects.add(element.getAsJsonObject());
            }
        }
        return objects;
    }

    private static Bounds bounds(JsonArray array) {
        if (array == null || array.size() < 4) {
            return new Bounds(-64, -64, 64, 64);
        }
        return new Bounds(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt(), array.get(3).getAsInt());
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    public record Snapshot(
        String phase,
        String team,
        int redScore,
        int blueScore,
        int seconds,
        int selfYaw,
        String selectedRespawn,
        boolean respawnPrompt,
        String mapTexture,
        Bounds bounds,
        List<BaseState> bases,
        List<PointState> points,
        List<PlayerState> players,
        List<RespawnState> respawns,
        List<MarkerState> markers,
        List<KillFeedState> killFeed,
        HitState hit,
        StatsState selfStats
    ) {
        public static Snapshot empty() {
            return new Snapshot("WAITING", "NONE", 0, 0, 0, 0, "BASE", false, "", new Bounds(-64, -64, 64, 64), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), new HitState(0, 0, false), new StatsState(0, 0, 0));
        }
    }

    public record Bounds(int minX, int minZ, int maxX, int maxZ) {
        public int width() {
            return Math.max(1, maxX - minX);
        }

        public int depth() {
            return Math.max(1, maxZ - minZ);
        }
    }

    public record BaseState(String team, String name, int x, int z) {
    }

    public record PointState(String id, String name, String owner, int progress, int x, int z, String status, String capturingTeam) {
    }

    public record PlayerState(String name, String team, boolean squad, boolean self, int x, int z, int yaw, int health) {
    }

    public record RespawnState(String kind, String team, String name, int x, int z, boolean available, int seconds, int health, int maxHealth) {
    }

    public record MarkerState(String type, String team, String label, int x, int z, int seconds, boolean own) {
    }

    public record KillFeedState(String killer, String killerTeam, String victim, String victimTeam, String weapon, int seconds) {
    }

    public record HitState(int sequence, int damage, boolean kill) {
    }

    public record StatsState(int kills, int deaths, int damage) {
    }
}
