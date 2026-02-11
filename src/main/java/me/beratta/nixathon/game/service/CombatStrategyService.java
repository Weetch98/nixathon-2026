package me.beratta.nixathon.game.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.beratta.nixathon.game.dto.CombatActionResponse;
import me.beratta.nixathon.game.dto.CombatRequest;
import me.beratta.nixathon.game.dto.EnemyTowerState;
import me.beratta.nixathon.game.dto.TowerState;
import org.springframework.stereotype.Service;

@Service
public class CombatStrategyService {

    private final EconomyService economyService;

    public CombatStrategyService(
            EconomyService economyService
    ) {
        this.economyService = economyService;
    }

    public List<CombatActionResponse> planCombat(CombatRequest request) {
        TowerState playerTower = request.playerTower();
        int totalResources = playerTower.resources();
        int upgradeCost = economyService.upgradeCost(playerTower.level());
        List<CombatActionResponse> proposedActions = new ArrayList<>();
        int armorSpend;
        List<Integer> attackTargetIds = request.enemyTowers().stream().filter(enemyTowerState -> enemyTowerState.hp() > 0).map(
            EnemyTowerState::playerId).toList();
        Integer targetId = attackTargetIds.stream().reduce(0, Math::min);

        if (attackTargetIds.size() == 1 && playerTower.level() > 1) {
            proposedActions.add(CombatActionResponse.attack(targetId, totalResources));
            return sanitizeActions(proposedActions, totalResources, upgradeCost);
        }

        armorSpend = Math.min((request.turn() - 5), 0) * 5;
        totalResources = totalResources - armorSpend - 1;
        boolean doUpgrade = totalResources > upgradeCost;

        proposedActions.add(CombatActionResponse.armor(armorSpend));
        proposedActions.add(CombatActionResponse.attack(targetId, 1));
        if (doUpgrade) {
            proposedActions.add(CombatActionResponse.upgrade());
        }

        return sanitizeActions(proposedActions, totalResources, upgradeCost);
    }

    private List<CombatActionResponse> sanitizeActions(
            List<CombatActionResponse> proposedActions,
            int totalResources,
            int upgradeCost
    ) {
        int remainingResources = totalResources;
        boolean armorUsed = false;
        boolean upgradeUsed = false;
        Set<Integer> attackedTargets = new HashSet<>();
        List<CombatActionResponse> sanitized = new ArrayList<>();

        for (CombatActionResponse action : proposedActions) {
            if ("armor".equals(action.type())) {
                if (armorUsed || action.amount() == null || action.amount() <= 0) {
                    continue;
                }
                if (action.amount() > remainingResources) {
                    continue;
                }
                sanitized.add(CombatActionResponse.armor(action.amount()));
                remainingResources -= action.amount();
                armorUsed = true;
                continue;
            }

            if ("attack".equals(action.type())) {
                if (action.targetId() == null || action.troopCount() == null || action.troopCount() <= 0) {
                    continue;
                }
                if (attackedTargets.contains(action.targetId())) {
                    continue;
                }
                if (action.troopCount() > remainingResources) {
                    continue;
                }
                sanitized.add(CombatActionResponse.attack(action.targetId(), action.troopCount()));
                remainingResources -= action.troopCount();
                attackedTargets.add(action.targetId());
                continue;
            }

            if ("upgrade".equals(action.type())) {
                if (upgradeUsed || upgradeCost > remainingResources) {
                    continue;
                }
                sanitized.add(CombatActionResponse.upgrade());
                remainingResources -= upgradeCost;
                upgradeUsed = true;
            }
        }

        return List.copyOf(sanitized);
    }
}
