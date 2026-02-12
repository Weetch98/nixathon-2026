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

/**
 * Converts observed enemy actions and historical profiles into numeric threat scores.
 * <p>
 * The strategy engine uses these scores as a common signal for both negotiation and
 * combat prioritization.
 */
@Service
public class ThreatAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(ThreatAssessmentService.class);

    /**
     * Assesses negotiation-phase hostility using only current request data.
     */
    public Map<Integer, Integer> assessNegotiationThreat(NegotiationRequest request) {
        return assessNegotiationThreat(request, Map.of());
    }

    /**
     * Assesses negotiation-phase hostility combining current request and persistent profile data.
     */
    public Map<Integer, Integer> assessNegotiationThreat(
            NegotiationRequest request,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy
    ) {
        // Our player ID.
        int selfId = request.playerTower().playerId();

        // We are giving a base threat score for each enemy.
        // Base threat score is calculated only from the enemy level.
        Map<Integer, Integer> threatByEnemy = initializeEnemyThreatMap(request.enemyTowers());

        // We are getting the current combat actions from the negotiation request.
        for (PlayerAttack combatAction : request.combatActions()) {
            int enemyId = combatAction.playerId();
            int targetId = combatAction.action().targetId();
            int troops = combatAction.action().troopCount();

            // Attacks targeting us are weighted much more than generic battlefield activity.
            // These numbers here are completely heuristic.
            if (targetId == selfId) {
                threatByEnemy.merge(enemyId, (troops * 2) + 12, Integer::sum);
            } else {
                threatByEnemy.merge(enemyId, Math.max(1, troops / 3), Integer::sum);
            }
        }

        // Update the threats with historical data from player profiles.
        // This includes hostility, afk resource gathering threat, betrayals, etc.
        applyHistoricalAdjustments(request.enemyTowers(), threatByEnemy, profilesByEnemy, false);

        // Convert negative threats to 0.
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

    /**
     * Assesses combat-phase hostility using only current request data.
     */
    public Map<Integer, Integer> assessCombatThreat(CombatRequest request) {
        return assessCombatThreat(request, Map.of());
    }

    /**
     * Assesses combat-phase hostility combining current request and persistent profile data.
     */
    public Map<Integer, Integer> assessCombatThreat(
            CombatRequest request,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy
    ) {
        int selfId = request.playerTower().playerId();

        // We initialize threat map using only levels. The more level the more threat.
        Map<Integer, Integer> threatByEnemy = initializeEnemyThreatMap(request.enemyTowers());

        for (PlayerAttack attack : request.previousAttacks()) {
            int enemyId = attack.playerId();
            int targetId = attack.action().targetId();
            int troops = attack.action().troopCount();

            // Last-turn direct attacks are the strongest short-term predictor.
            if (targetId == selfId) {
                threatByEnemy.merge(enemyId, (troops * 2) + 10, Integer::sum);
            } else {
                // Attacked someone else, but he is still a threat because he attacked in the first place.
                threatByEnemy.merge(enemyId, Math.max(1, troops / 4), Integer::sum);
            }
        }

        for (PlayerDiplomacy diplomacy : request.diplomacy()) {
            int enemyId = diplomacy.playerId();

            // He wants to attack us, which is not good.
            if (diplomacy.action().attackTargetId() != null && diplomacy.action().attackTargetId() == selfId) {
                threatByEnemy.merge(enemyId, 20, Integer::sum);
            }

            // Peace offers slightly reduce threat, but only marginally because trust is non-binding.
            if (diplomacy.action().allyId() == selfId && diplomacy.action().attackTargetId() == null) {
                threatByEnemy.merge(enemyId, -6, Integer::sum);
            }
        }

        // Update the threats with historical data from player profiles.
        // This includes hostility, afk resource gathering threat, betrayals, etc.
        applyHistoricalAdjustments(request.enemyTowers(), threatByEnemy, profilesByEnemy, true);

        // Convert negative threats to 0.
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

    /**
     * Initializes baseline threat from durable power proxy (tower level).
     */
    private Map<Integer, Integer> initializeEnemyThreatMap(Iterable<EnemyTowerState> enemies) {
        Map<Integer, Integer> threatByEnemy = new HashMap<>();
        for (EnemyTowerState enemy : enemies) {
            threatByEnemy.put(enemy.playerId(), enemy.level() * 4);
        }
        return threatByEnemy;
    }

    /**
     * Applies profile-based adjustments like betrayal and AFK-hoarding risk.
     */
    private void applyHistoricalAdjustments(
            List<EnemyTowerState> enemies,
            Map<Integer, Integer> threatByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            boolean combatPhase
    ) {
        for (EnemyTowerState enemy : enemies) {
            // We are getting the profile for the given player.
            GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(enemy.playerId());
            if (profile == null) {
                continue;
            }

            // Historical hostility + latent-risk behavior (AFK resource accumulation).
            int threatDelta = getThreatDelta(combatPhase, profile);

            // We are adding the additional threat score.
            threatByEnemy.merge(enemy.playerId(), threatDelta, Integer::sum);
        }
    }

    private static int getThreatDelta(boolean combatPhase, GameMemoryService.PlayerProfileSnapshot profile) {
        // Completely heuristic numbers.
        // Hostility score is aggression towards us.
        // afkHoardingRisk is related to resource accumulation over time, and a big attack in late game.
        // Consecutive attacks is just general battlefield activity.
        int threatDelta = (profile.hostilityScore() / 2)
                + (profile.afkHoardingRisk() / 2)
                + (profile.consecutiveAttackTurns() * 2);

        // We lower the threat if the trust score is high, and we haven't been betrayed.
        if (combatPhase && profile.trustScore() >= 20 && profile.betrayalsAgainstUs() == 0) {
            threatDelta -= 6;
        }

        // Betrayal history dominates trust: once burned, keep this actor dangerous.
        if (profile.betrayalsAgainstUs() > 0) {
            threatDelta += profile.betrayalsAgainstUs() * 10;
        }
        return threatDelta;
    }

    /**
     * Prevents negative threat values after bonuses/discounts.
     */
    private Map<Integer, Integer> clampThreats(Map<Integer, Integer> threatByEnemy) {
        Map<Integer, Integer> clamped = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : threatByEnemy.entrySet()) {
            clamped.put(entry.getKey(), Math.max(0, entry.getValue()));
        }
        return clamped;
    }
}
