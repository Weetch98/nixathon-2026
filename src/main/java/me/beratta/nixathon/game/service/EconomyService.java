package me.beratta.nixathon.game.service;

import org.springframework.stereotype.Service;

@Service
public class EconomyService {

    private static final double UPGRADE_MULTIPLIER = 1.75;
    private static final int BASE_UPGRADE_COST = 50;
    private static final int BASE_INCOME = 20;
    private static final int LEVEL_INCOME_STEP = 8;

    public int upgradeCost(int currentLevel) {
        if (currentLevel < 1) {
            throw new IllegalArgumentException("Tower level must be at least 1");
        }
        return (int) Math.round(BASE_UPGRADE_COST * Math.pow(UPGRADE_MULTIPLIER, currentLevel - 1));
    }

    public int estimatedIncomeForLevel(int level) {
        return BASE_INCOME + ((Math.max(level, 1) - 1) * LEVEL_INCOME_STEP);
    }

    public int estimatedUpgradeReturn(int currentLevel, int turnsRemaining) {
        int nextLevelIncome = estimatedIncomeForLevel(currentLevel + 1);
        int currentIncome = estimatedIncomeForLevel(currentLevel);
        return (nextLevelIncome - currentIncome) * Math.max(turnsRemaining, 0);
    }
}
