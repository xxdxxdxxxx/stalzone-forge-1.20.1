package ru.zonewars.forge;

import java.util.Optional;
import java.util.UUID;

record TacticalMarker(String type, TeamColor team, Optional<UUID> squadId, String label, int x, int z, long expiresAt) {
    boolean expired() {
        return expiresAt != Long.MAX_VALUE && System.currentTimeMillis() > expiresAt;
    }
}
