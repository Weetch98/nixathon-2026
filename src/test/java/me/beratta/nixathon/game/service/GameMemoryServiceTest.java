package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.AttackAction;
import me.beratta.nixathon.game.dto.CombatRequest;
import me.beratta.nixathon.game.dto.DiplomacyAction;
import me.beratta.nixathon.game.dto.EnemyTowerState;
import me.beratta.nixathon.game.dto.PlayerAttack;
import me.beratta.nixathon.game.dto.PlayerDiplomacy;
import me.beratta.nixathon.game.dto.TowerState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameMemoryServiceTest {

    @Test
    void shouldDetectAfkCollectorFromInactivityPattern() {
        GameMemoryService gameMemoryService = new GameMemoryService();

        for (int turn = 1; turn <= 4; turn++) {
            gameMemoryService.observeCombatTurn(request(turn, List.of(), List.of()));
        }

        Map<Integer, GameMemoryService.PlayerProfileSnapshot> profiles = gameMemoryService.getPlayerProfiles(
                700L,
                List.of(new EnemyTowerState(200, 100, 0, 3))
        );

        GameMemoryService.PlayerProfileSnapshot profile = profiles.get(200);
        assertEquals(4, profile.consecutiveNoAttackTurns());
        assertTrue(profile.likelyAfkCollector());
        assertTrue(profile.afkHoardingRisk() > 0);
    }

    @Test
    void shouldTrackBetrayalAfterPeaceOfferThenAttack() {
        GameMemoryService gameMemoryService = new GameMemoryService();

        gameMemoryService.observeCombatTurn(request(
                1,
                List.of(new PlayerDiplomacy(200, new DiplomacyAction(100, null))),
                List.of()
        ));

        gameMemoryService.observeCombatTurn(request(
                2,
                List.of(),
                List.of(new PlayerAttack(200, new AttackAction(100, 12)))
        ));

        Map<Integer, GameMemoryService.PlayerProfileSnapshot> profiles = gameMemoryService.getPlayerProfiles(
                700L,
                List.of(new EnemyTowerState(200, 100, 0, 3))
        );

        GameMemoryService.PlayerProfileSnapshot profile = profiles.get(200);
        assertEquals(1, profile.betrayalsAgainstUs());
        assertTrue(profile.hostilityScore() > 0);
    }

    private CombatRequest request(
            int turn,
            List<PlayerDiplomacy> diplomacy,
            List<PlayerAttack> attacks
    ) {
        return new CombatRequest(
                700L,
                turn,
                new TowerState(100, 100, 0, 100, 2),
                List.of(new EnemyTowerState(200, 100, 0, 3)),
                diplomacy,
                attacks
        );
    }
}
