package me.beratta.nixathon.game.service;

import java.util.ArrayList;
import java.util.List;
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
        int targetId = attackTargetIds.stream().reduce(0, Math::min);

        if (attackTargetIds.size() == 1) {
            targetId = attackTargetIds.stream().findFirst().get();
        }

        if (attackTargetIds.size() == 1 && playerTower.level() > 1) {
            proposedActions.add(CombatActionResponse.attack(targetId, totalResources));
            return proposedActions;
        }

        armorSpend = Math.min((request.turn() - 5), 0) * 5;
        totalResources = totalResources - armorSpend - 1;
        boolean doUpgrade = totalResources > upgradeCost;

        if (armorSpend > 0) {
            proposedActions.add(CombatActionResponse.armor(armorSpend));
        }
        proposedActions.add(CombatActionResponse.attack(targetId, 1));
        if (doUpgrade) {
            proposedActions.add(CombatActionResponse.upgrade());
        }

        return proposedActions;
    }
}
