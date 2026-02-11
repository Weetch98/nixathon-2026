package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.CombatRequest;
import me.beratta.nixathon.game.dto.EnemyTowerState;
import me.beratta.nixathon.game.dto.NegotiationRequest;
import me.beratta.nixathon.game.dto.PlayerAttack;
import me.beratta.nixathon.game.dto.PlayerDiplomacy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ThreatAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(ThreatAssessmentService.class);

    public Map<Integer, Integer> assessNegotiationThreat(NegotiationRequest request) {
        return assessNegotiationThreat(request, Map.of());
    }

    public Map<Integer, Integer> assessNegotiationThreat(
            NegotiationRequest request,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy
    ) {
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

        applyHistoricalAdjustments(request.enemyTowers(), threatByEnemy, profilesByEnemy, false);
        Map<Integer, Integer> clampedThreats = clampThreats(threatByEnemy);
        log.debug(
                "Negotiation threat assessed game={} turn={} self={} enemyCount={} threats={}",
                request.gameId(),
                request.turn(),
                selfId,
                request.enemyTowers().size(),
                clampedThreats
        );
        return clampedThreats;
    }

    public Map<Integer, Integer> assessCombatThreat(CombatRequest request) {
        return assessCombatThreat(request, Map.of());
    }

    public Map<Integer, Integer> assessCombatThreat(
            CombatRequest request,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy
    ) {
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
            if (diplomacy.action().allyId() == selfId && diplomacy.action().attackTargetId() == null) {
                threatByEnemy.merge(enemyId, -6, Integer::sum);
            }
        }

        applyHistoricalAdjustments(request.enemyTowers(), threatByEnemy, profilesByEnemy, true);
        Map<Integer, Integer> clampedThreats = clampThreats(threatByEnemy);
        log.debug(
                "Combat threat assessed game={} turn={} self={} enemyCount={} threats={}",
                request.gameId(),
                request.turn(),
                selfId,
                request.enemyTowers().size(),
                clampedThreats
        );
        return clampedThreats;
    }

    private Map<Integer, Integer> initializeEnemyThreatMap(Iterable<EnemyTowerState> enemies) {
        Map<Integer, Integer> threatByEnemy = new HashMap<>();
        for (EnemyTowerState enemy : enemies) {
            threatByEnemy.put(enemy.playerId(), enemy.level() * 4);
        }
        return threatByEnemy;
    }

    private void applyHistoricalAdjustments(
            List<EnemyTowerState> enemies,
            Map<Integer, Integer> threatByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            boolean combatPhase
    ) {
        for (EnemyTowerState enemy : enemies) {
            GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(enemy.playerId());
            if (profile == null) {
                continue;
            }

            int threatDelta = (profile.hostilityScore() / 2)
                    + (profile.afkHoardingRisk() / 2)
                    + (profile.consecutiveAttackTurns() * 2);

            if (combatPhase && profile.trustScore() >= 20 && profile.betrayalsAgainstUs() == 0) {
                threatDelta -= 6;
            }
            if (profile.betrayalsAgainstUs() > 0) {
                threatDelta += profile.betrayalsAgainstUs() * 10;
            }

            threatByEnemy.merge(enemy.playerId(), threatDelta, Integer::sum);
        }
    }

    private Map<Integer, Integer> clampThreats(Map<Integer, Integer> threatByEnemy) {
        Map<Integer, Integer> clamped = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : threatByEnemy.entrySet()) {
            clamped.put(entry.getKey(), Math.max(0, entry.getValue()));
        }
        return clamped;
    }
}
