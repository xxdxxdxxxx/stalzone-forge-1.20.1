package ru.zonewars.forge;

import java.util.List;

record ArenaData(
    int minPlayersPerTeam,
    int maxPlayersPerTeam,
    int preparationSeconds,
    int matchSeconds,
    int overtimeSeconds,
    int endScreenSeconds,
    int captureSeconds,
    int pointsPerSecond,
    LocationSpec redSpawn,
    LocationSpec blueSpawn,
    List<LocationSpec> shopLocations,
    List<CapturePointData> capturePoints
) {
    static ArenaData refinery() {
        return new ArenaData(
            0,
            15,
            10,
            1800,
            180,
            12,
            10,
            1,
            new LocationSpec("world", -48.5, 63.0, 0.5, 90.0f, 0.0f),
            new LocationSpec("world", 48.5, 63.0, 0.5, -90.0f, 0.0f),
            List.of(),
            List.of(
                new CapturePointData("alpha", "Alpha", new LocationSpec("world", -32.5, 63.0, -12.5, 0.0f, 0.0f), 9.0),
                new CapturePointData("bravo", "Bravo", new LocationSpec("world", 0.5, 63.0, 0.5, 0.0f, 0.0f), 9.0),
                new CapturePointData("charlie", "Charlie", new LocationSpec("world", 32.5, 63.0, 12.5, 0.0f, 0.0f), 9.0)
            )
        );
    }

    ArenaData withRedSpawn(LocationSpec location) {
        return new ArenaData(minPlayersPerTeam, maxPlayersPerTeam, preparationSeconds, matchSeconds, overtimeSeconds, endScreenSeconds, captureSeconds, pointsPerSecond, location, blueSpawn, shopLocations, capturePoints);
    }

    ArenaData withBlueSpawn(LocationSpec location) {
        return new ArenaData(minPlayersPerTeam, maxPlayersPerTeam, preparationSeconds, matchSeconds, overtimeSeconds, endScreenSeconds, captureSeconds, pointsPerSecond, redSpawn, location, shopLocations, capturePoints);
    }

    ArenaData withShopLocations(List<LocationSpec> shops) {
        return new ArenaData(minPlayersPerTeam, maxPlayersPerTeam, preparationSeconds, matchSeconds, overtimeSeconds, endScreenSeconds, captureSeconds, pointsPerSecond, redSpawn, blueSpawn, List.copyOf(shops), capturePoints);
    }

    ArenaData withCapturePoints(List<CapturePointData> points) {
        return new ArenaData(minPlayersPerTeam, maxPlayersPerTeam, preparationSeconds, matchSeconds, overtimeSeconds, endScreenSeconds, captureSeconds, pointsPerSecond, redSpawn, blueSpawn, shopLocations, List.copyOf(points));
    }
}
