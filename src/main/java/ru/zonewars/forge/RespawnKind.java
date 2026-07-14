package ru.zonewars.forge;

enum RespawnKind {
    BASE,
    TENT,
    OUTPOST;

    static java.util.Optional<RespawnKind> parse(String value) {
        for (RespawnKind kind : values()) {
            if (kind.name().equalsIgnoreCase(value)) {
                return java.util.Optional.of(kind);
            }
        }
        return java.util.Optional.empty();
    }
}
