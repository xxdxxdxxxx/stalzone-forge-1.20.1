package ru.zonewars.forge;

final class PlayerMatchStats {
    private int kills;
    private int deaths;
    private int damage;

    void addKill() {
        kills++;
    }

    void addDeath() {
        deaths++;
    }

    void addDamage(int amount) {
        long next = (long) damage + Math.max(0, amount);
        damage = (int) Math.min(Integer.MAX_VALUE, next);
    }

    int kills() {
        return kills;
    }

    int deaths() {
        return deaths;
    }

    int damage() {
        return damage;
    }
}
