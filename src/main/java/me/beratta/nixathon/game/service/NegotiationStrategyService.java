package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.EnemyTowerState;
import me.beratta.nixathon.game.dto.NegotiationMessage;
import me.beratta.nixathon.game.dto.NegotiationRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class NegotiationStrategyService {

    public NegotiationStrategyService(
    ) {
    }

    public List<NegotiationMessage> planNegotiation(NegotiationRequest request) {
        List<Integer> enemies = request.enemyTowers().stream()
                .filter(enemy -> enemy.hp() > 0)
                .map(EnemyTowerState::playerId)
                .toList();
        Integer target = enemies.stream().reduce(0, Math::min);
        List<NegotiationMessage> responses = new ArrayList<>();
        for (Integer enemy : enemies) {
            NegotiationMessage response;
            if (enemy.equals(target)) {
                response = new NegotiationMessage(enemy, null);
            } else {
                response = new NegotiationMessage(enemy, target);
            }
            responses.add(response);
        }

        return responses;
    }
}
