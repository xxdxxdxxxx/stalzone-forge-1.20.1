package ru.zonewars.forge;

record RespawnPoint(RespawnKind kind, TeamColor team, String name, LocationSpec location, LocationSpec blockLocation, int health, int maxHealth, long availableAt) {
    RespawnPoint withHealth(int value) {
        return new RespawnPoint(kind, team, name, location, blockLocation, value, maxHealth, availableAt);
    }
}
