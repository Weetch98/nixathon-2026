package me.beratta.nixathon.game.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EconomyService {

    private static final Logger log = LoggerFactory.getLogger(EconomyService.class);

    private static final double UPGRADE_MULTIPLIER = 1.75;
    private static final int BASE_UPGRADE_COST = 50;
    private static final int BASE_INCOME = 20;
    private static final int LEVEL_INCOME_STEP = 8;

    public int upgradeCost(int currentLevel) {
        if (currentLevel < 1) {
            throw new IllegalArgumentException("Tower level must be at least 1");
        }
        int cost = (int) Math.round(BASE_UPGRADE_COST * Math.pow(UPGRADE_MULTIPLIER, currentLevel - 1));
        log.debug("Computed upgrade cost currentLevel={} cost={}", currentLevel, cost);
        return cost;
    }

    public int estimatedIncomeForLevel(int level) {
        int income = BASE_INCOME + ((Math.max(level, 1) - 1) * LEVEL_INCOME_STEP);
        log.debug("Computed estimated income level={} income={}", level, income);
        return income;
    }

    public int estimatedUpgradeReturn(int currentLevel, int turnsRemaining) {
        int nextLevelIncome = estimatedIncomeForLevel(currentLevel + 1);
        int currentIncome = estimatedIncomeForLevel(currentLevel);
        int upgradeReturn = (nextLevelIncome - currentIncome) * Math.max(turnsRemaining, 0);
        log.debug(
                "Computed estimated upgrade return currentLevel={} turnsRemaining={} projectedReturn={}",
                currentLevel,
                turnsRemaining,
                upgradeReturn
        );
        return upgradeReturn;
    }
}
