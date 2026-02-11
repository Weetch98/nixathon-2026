package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.CombatRequest;
import me.beratta.nixathon.game.dto.EnemyTowerState;
import me.beratta.nixathon.game.dto.NegotiationRequest;
import me.beratta.nixathon.game.dto.PlayerAttack;
import me.beratta.nixathon.game.dto.PlayerDiplomacy;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class ThreatAssessmentService {

    public Map<Integer, Integer> assessNegotiationThreat(NegotiationRequest request) {
        int selfId = request.playerTower().playerId();
        Map<Integer, Integer> threatByEnemy = initializeEnemyThreatMap(request.enemyTowers());

        for (PlayerAttack combatAction : request.combatActions()) {
            int enemyId = combatAction.playerId();
            int targetId = combatAction.action().targetId();
            int troops = combatAction.action().troopCount();

            if (targetId == selfId) {
                threatByEnemy.merge(enemyId, (troops * 2) + 12, Integer::sum);
            } else {
                threatByEnemy.merge(enemyId, Math.max(1, troops / 3), Integer::sum);
            }
        }

        return clampThreats(threatByEnemy);
    }

    public Map<Integer, Integer> assessCombatThreat(CombatRequest request) {
        int selfId = request.playerTower().playerId();
        Map<Integer, Integer> threatByEnemy = initializeEnemyThreatMap(request.enemyTowers());

        for (PlayerAttack attack : request.previousAttacks()) {
            int enemyId = attack.playerId();
            int targetId = attack.action().targetId();
            int troops = attack.action().troopCount();

            if (targetId == selfId) {
                threatByEnemy.merge(enemyId, (troops * 2) + 10, Integer::sum);
            } else {
                threatByEnemy.merge(enemyId, Math.max(1, troops / 4), Integer::sum);
            }
        }

        for (PlayerDiplomacy diplomacy : request.diplomacy()) {
            int enemyId = diplomacy.playerId();

            if (diplomacy.action().attackTargetId() != null && diplomacy.action().attackTargetId() == selfId) {
                threatByEnemy.merge(enemyId, 20, Integer::sum);
            }
            if (diplomacy.action().allyId() == selfId) {
                threatByEnemy.merge(enemyId, -12, Integer::sum);
            }
        }

        return clampThreats(threatByEnemy);
    }

    private Map<Integer, Integer> initializeEnemyThreatMap(Iterable<EnemyTowerState> enemies) {
        Map<Integer, Integer> threatByEnemy = new HashMap<>();
        for (EnemyTowerState enemy : enemies) {
            threatByEnemy.put(enemy.playerId(), enemy.level() * 3);
        }
        return threatByEnemy;
    }

    private Map<Integer, Integer> clampThreats(Map<Integer, Integer> threatByEnemy) {
        Map<Integer, Integer> clamped = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : threatByEnemy.entrySet()) {
            clamped.put(entry.getKey(), Math.max(0, entry.getValue()));
        }
        return clamped;
    }
}
