package ru.zonewars.forge;

enum TeamColor {
    RED,
    BLUE;

    static TeamColor parse(String value) {
        for (TeamColor team : values()) {
            if (team.name().equalsIgnoreCase(value)) {
                return team;
            }
        }
        throw new IllegalArgumentException("Unknown team: " + value);
    }
}
