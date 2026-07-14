package ru.zonewars.forge;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class SquadService {
        static final int MAX_SQUAD_SIZE = 5;
        private final Map<UUID, Squad> squads = new HashMap<>();
        private final Map<UUID, UUID> squadByPlayer = new HashMap<>();
        private final Map<UUID, Invitation> invitations = new HashMap<>();

        Squad create(UUID leader) {
            leave(leader);
            Squad squad = new Squad(UUID.randomUUID(), leader);
            squads.put(squad.id(), squad);
            squadByPlayer.put(leader, squad.id());
            return squad;
        }

        Optional<Squad> squadOf(UUID playerId) {
            UUID squadId = squadByPlayer.get(playerId);
            return squadId == null ? Optional.empty() : Optional.ofNullable(squads.get(squadId));
        }

        void invite(UUID inviter, UUID target) {
            if (inviter.equals(target)) {
                throw new IllegalStateException("You cannot invite yourself.");
            }
            Squad squad = squadOf(inviter).orElseThrow(() -> new IllegalStateException("Create a squad first."));
            if (!squad.leader().equals(inviter)) {
                throw new IllegalStateException("Only squad leader can invite.");
            }
            if (squad.isFull(MAX_SQUAD_SIZE)) {
                throw new IllegalStateException("Squad is full.");
            }
            if (squadByPlayer.containsKey(target)) {
                throw new IllegalStateException("Target is already in a squad.");
            }
            invitations.put(target, new Invitation(squad.id(), Instant.now().plus(Duration.ofSeconds(60))));
        }

        boolean hasPendingInvite(UUID playerId) {
            Invitation invite = invitations.get(playerId);
            if (invite == null) {
                return false;
            }
            if (invite.expiresAt().isBefore(Instant.now()) || !squads.containsKey(invite.squadId())) {
                invitations.remove(playerId);
                return false;
            }
            return true;
        }

        JoinResult acceptInvite(UUID playerId) {
            Invitation invite = invitations.remove(playerId);
            if (invite == null || invite.expiresAt().isBefore(Instant.now())) {
                return JoinResult.NO_INVITE;
            }
            Squad squad = squads.get(invite.squadId());
            if (squad == null) {
                return JoinResult.NO_SQUAD;
            }
            if (squad.isFull(MAX_SQUAD_SIZE)) {
                return JoinResult.FULL;
            }
            leave(playerId);
            squad.add(playerId, MAX_SQUAD_SIZE);
            squadByPlayer.put(playerId, squad.id());
            return JoinResult.JOINED;
        }

        void leave(UUID playerId) {
            UUID squadId = squadByPlayer.remove(playerId);
            if (squadId == null) {
                return;
            }
            Squad squad = squads.get(squadId);
            if (squad == null) {
                return;
            }
            squad.remove(playerId);
            if (squad.isEmpty()) {
                squads.remove(squadId);
            }
        }

        enum JoinResult {
            JOINED,
            NO_INVITE,
            NO_SQUAD,
            FULL
        }

        private record Invitation(UUID squadId, Instant expiresAt) {
        }
    }
