package ru.zonewars.forge;

record PlayerTotalStats(String name, int kills, int deaths, int damage, int wins, int losses) {
    static PlayerTotalStats fromMatch(String name, PlayerMatchStats matchStats, boolean won) {
        return new PlayerTotalStats(name, matchStats.kills(), matchStats.deaths(), matchStats.damage(), won ? 1 : 0, won ? 0 : 1);
    }

    PlayerTotalStats plus(String name, PlayerMatchStats matchStats, boolean won) {
        return new PlayerTotalStats(
            name,
            kills + matchStats.kills(),
            deaths + matchStats.deaths(),
            damage + matchStats.damage(),
            wins + (won ? 1 : 0),
            losses + (won ? 0 : 1)
        );
    }
}
