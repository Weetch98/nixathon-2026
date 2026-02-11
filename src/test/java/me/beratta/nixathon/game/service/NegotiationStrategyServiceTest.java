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
    void shouldReturnUniqueRecipientsAndPersistCommitment() {
        ThreatAssessmentService threatAssessmentService = new ThreatAssessmentService();
        GameMemoryService gameMemoryService = new GameMemoryService();
        NegotiationStrategyService negotiationStrategyService = new NegotiationStrategyService(
                threatAssessmentService,
                gameMemoryService
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

        Set<Integer> recipientIds = new HashSet<>();
        for (NegotiationMessage message : messages) {
            assertTrue(recipientIds.add(message.allyId()));
        }

        Optional<GameMemoryService.NegotiationCommitment> commitment = gameMemoryService.findCommitment(55L, 4);
        assertTrue(commitment.isPresent());
        assertEquals(recipientIds, commitment.get().messagedPlayerIds());
        assertTrue(commitment.get().explicitAlliedPlayerIds().size() <= recipientIds.size());
        assertNotNull(commitment.get().focusTargetId());
    }

    @Test
    void shouldCoordinateAgainstEconomicLeaderInFourPlayerState() {
        ThreatAssessmentService threatAssessmentService = new ThreatAssessmentService();
        GameMemoryService gameMemoryService = new GameMemoryService();
        NegotiationStrategyService negotiationStrategyService = new NegotiationStrategyService(
                threatAssessmentService,
                gameMemoryService
        );

        NegotiationRequest request = new NegotiationRequest(
                66L,
                6,
                new TowerState(100, 100, 6, 52, 1),
                List.of(
                        new EnemyTowerState(200, 90, 10, 1),
                        new EnemyTowerState(201, 92, 8, 1),
                        new EnemyTowerState(300, 96, 14, 3)
                ),
                List.of()
        );

        List<NegotiationMessage> messages = negotiationStrategyService.planNegotiation(request);
        assertFalse(messages.isEmpty());

        List<NegotiationMessage> focusedMessages = messages.stream()
                .filter(message -> message.attackTargetId() != null)
                .toList();
        assertFalse(focusedMessages.isEmpty());
        assertTrue(focusedMessages.stream().allMatch(message -> message.attackTargetId() == 300));
    }
}
