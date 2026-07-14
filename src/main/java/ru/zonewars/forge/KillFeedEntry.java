package ru.zonewars.forge;

import java.time.Instant;

record KillFeedEntry(String killer, TeamColor killerTeam, String victim, TeamColor victimTeam, String weapon, Instant createdAt) {
}
