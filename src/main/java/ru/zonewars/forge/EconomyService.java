package ru.zonewars.forge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class EconomyService {
    private final int startingMoney;
    private final int captureIncomePerSecond;
    private final int killReward;
    private final int captureReward;
    private final Map<UUID, Integer> balances = new HashMap<>();

    EconomyService(int startingMoney, int captureIncomePerSecond, int killReward, int captureReward) {
        this.startingMoney = startingMoney;
        this.captureIncomePerSecond = captureIncomePerSecond;
        this.killReward = killReward;
        this.captureReward = captureReward;
    }

    void resetPlayer(UUID playerId) {
        balances.put(playerId, startingMoney);
    }

    int balance(UUID playerId) {
        return balances.getOrDefault(playerId, 0);
    }

    void add(UUID playerId, int amount) {
        balances.put(playerId, ZoneWarsRules.addMoney(balance(playerId), amount));
    }

    boolean spend(UUID playerId, int amount) {
        int current = balance(playerId);
        if (amount < 0 || current < amount) {
            return false;
        }
        balances.put(playerId, current - amount);
        return true;
    }

    int captureIncomePerSecond() {
        return captureIncomePerSecond;
    }

    int killReward() {
        return killReward;
    }

    int captureReward() {
        return captureReward;
    }
}
