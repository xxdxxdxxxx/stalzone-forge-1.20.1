package ru.zonewars.forge;

enum RespawnKind {
    BASE,
    TENT,
    OUTPOST;

    static RespawnKind parse(String value) {
        for (RespawnKind kind : values()) {
            if (kind.name().equalsIgnoreCase(value)) {
                return kind;
            }
        }
        return BASE;
    }
}
