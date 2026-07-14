package ru.zonewars.forge;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

final class Squad {
        private final UUID id;
        UUID leader;
        private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

        Squad(UUID id, UUID leader) {
            this.id = id;
            this.leader = leader;
            this.members.add(leader);
        }

        UUID id() {
            return id;
        }

        UUID leader() {
            return leader;
        }

        Set<UUID> members() {
            return Set.copyOf(members);
        }

        boolean contains(UUID playerId) {
            return members.contains(playerId);
        }

        boolean isFull(int maxSize) {
            return members.size() >= maxSize;
        }

        boolean add(UUID playerId, int maxSize) {
            if (isFull(maxSize)) {
                return false;
            }
            return members.add(playerId);
        }

        void remove(UUID playerId) {
            boolean removed = members.remove(playerId);
            if (removed && playerId.equals(leader) && !members.isEmpty()) {
                leader = members.iterator().next();
            }
        }

        boolean isEmpty() {
            return members.isEmpty();
        }
    }
