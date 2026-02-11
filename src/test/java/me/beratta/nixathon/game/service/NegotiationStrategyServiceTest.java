package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.AttackAction;
import me.beratta.nixathon.game.dto.EnemyTowerState;
import me.beratta.nixathon.game.dto.NegotiationMessage;
import me.beratta.nixathon.game.dto.NegotiationRequest;
import me.beratta.nixathon.game.dto.PlayerAttack;
import me.beratta.nixathon.game.dto.TowerState;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NegotiationStrategyServiceTest {

    @Test
    void shouldReturnUniqueAlliesAndPersistCommitment() {
        GameMemoryService gameMemoryService = new GameMemoryService();
        NegotiationStrategyService negotiationStrategyService = new NegotiationStrategyService(
        );

        NegotiationRequest request = new NegotiationRequest(
                55L,
                4,
                new TowerState(100, 92, 3, 48, 2),
                List.of(
                        new EnemyTowerState(200, 80, 10, 2),
                        new EnemyTowerState(201, 60, 6, 1),
                        new EnemyTowerState(300, 100, 12, 3),
                        new EnemyTowerState(301, 45, 0, 1)
                ),
                List.of(
                        new PlayerAttack(300, new AttackAction(100, 15)),
                        new PlayerAttack(201, new AttackAction(100, 8))
                )
        );

        List<NegotiationMessage> messages = negotiationStrategyService.planNegotiation(request);

        assertNotNull(messages);
        assertFalse(messages.isEmpty());
        assertTrue(messages.size() <= 3);

        Set<Integer> allyIds = new HashSet<>();
        for (NegotiationMessage message : messages) {
            assertTrue(allyIds.add(message.allyId()));
            assertNotNull(message.attackTargetId());
        }

        Optional<GameMemoryService.NegotiationCommitment> commitment = gameMemoryService.findCommitment(55L, 4);
        assertTrue(commitment.isPresent());
        assertEquals(allyIds, commitment.get().alliedPlayerIds());
        assertNotNull(commitment.get().focusTargetId());
    }
}
