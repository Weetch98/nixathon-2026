package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.AttackAction;
import me.beratta.nixathon.game.dto.CombatActionResponse;
import me.beratta.nixathon.game.dto.CombatRequest;
import me.beratta.nixathon.game.dto.DiplomacyAction;
import me.beratta.nixathon.game.dto.EnemyTowerState;
import me.beratta.nixathon.game.dto.NegotiationMessage;
import me.beratta.nixathon.game.dto.PlayerAttack;
import me.beratta.nixathon.game.dto.PlayerDiplomacy;
import me.beratta.nixathon.game.dto.TowerState;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatStrategyServiceTest {

    @Test
    void shouldGenerateOnlyValidAffordableActions() {
        EconomyService economyService = new EconomyService();
        GameMemoryService gameMemoryService = new GameMemoryService();
        CombatStrategyService combatStrategyService = strategyService(gameMemoryService);

        gameMemoryService.storeNegotiationPlan(42L, 7, List.of(
                new NegotiationMessage(200, 300),
                new NegotiationMessage(201, 300)
        ));

        CombatRequest request = new CombatRequest(
                42L,
                7,
                new TowerState(100, 75, 4, 120, 2),
                List.of(
                        new EnemyTowerState(200, 90, 12, 2),
                        new EnemyTowerState(201, 40, 4, 1),
                        new EnemyTowerState(300, 65, 10, 3)
                ),
                List.of(
                        new PlayerDiplomacy(200, new DiplomacyAction(100, null)),
                        new PlayerDiplomacy(201, new DiplomacyAction(100, 300)),
                        new PlayerDiplomacy(300, new DiplomacyAction(301, 100))
                ),
                List.of(
                        new PlayerAttack(300, new AttackAction(100, 18)),
                        new PlayerAttack(201, new AttackAction(100, 10))
                )
        );

        List<CombatActionResponse> actions = combatStrategyService.planCombat(request);
        assertNotNull(actions);
        assertFalse(actions.isEmpty());

        int armorActions = 0;
        int upgradeActions = 0;
        int spentResources = 0;
        Set<Integer> attackedTargets = new HashSet<>();

        for (CombatActionResponse action : actions) {
            switch (action.type()) {
                case "armor" -> {
                    armorActions++;
                    assertNotNull(action.amount());
                    assertTrue(action.amount() > 0);
                    spentResources += action.amount();
                }
                case "attack" -> {
                    assertNotNull(action.targetId());
                    assertNotNull(action.troopCount());
                    assertTrue(action.troopCount() > 0);
                    assertTrue(attackedTargets.add(action.targetId()));
                    spentResources += action.troopCount();
                }
                case "upgrade" -> {
                    upgradeActions++;
                    spentResources += economyService.upgradeCost(request.playerTower().level());
                }
                default -> throw new IllegalStateException("Unexpected action type: " + action.type());
            }
        }

        assertTrue(armorActions <= 1);
        assertTrue(upgradeActions <= 1);
        assertTrue(spentResources <= request.playerTower().resources());
        assertEquals(attackedTargets.size(),
                actions.stream().filter(action -> "attack".equals(action.type())).count());
    }

    @Test
    void shouldUpgradeInLateDuelToWinLevelTieBreak() {
        GameMemoryService gameMemoryService = new GameMemoryService();
        CombatStrategyService combatStrategyService = strategyService(gameMemoryService);

        CombatRequest request = new CombatRequest(
                99L,
                25,
                new TowerState(100, 82, 12, 100, 2),
                List.of(new EnemyTowerState(200, 90, 10, 2)),
                List.of(),
                List.of()
        );

        List<CombatActionResponse> actions = combatStrategyService.planCombat(request);

        assertTrue(actions.stream().anyMatch(action -> "upgrade".equals(action.type())));
    }

    @Test
    void shouldAccountForMutualCancellationWhenSizingKillShots() {
        GameMemoryService gameMemoryService = new GameMemoryService();
        CombatStrategyService combatStrategyService = strategyService(gameMemoryService);

        CombatRequest request = new CombatRequest(
                100L,
                6,
                new TowerState(100, 100, 40, 24, 1),
                List.of(
                        new EnemyTowerState(200, 8, 0, 1),
                        new EnemyTowerState(201, 16, 0, 1)
                ),
                List.of(),
                List.of(new PlayerAttack(200, new AttackAction(100, 20)))
        );

        List<CombatActionResponse> actions = combatStrategyService.planCombat(request);

        CombatActionResponse attackOnSecondEnemy = actions.stream()
                .filter(action -> "attack".equals(action.type()))
                .filter(action -> action.targetId() != null && action.targetId() == 201)
                .findFirst()
                .orElseThrow();

        assertTrue(attackOnSecondEnemy.troopCount() >= 17);
    }

    private CombatStrategyService strategyService(GameMemoryService gameMemoryService) {
        return new CombatStrategyService(
                new ThreatAssessmentService(),
                new EconomyService(),
                gameMemoryService,
                new FatigueService()
        );
    }
}
