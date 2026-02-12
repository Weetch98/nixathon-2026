package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.EnemyTowerState;
import me.beratta.nixathon.game.dto.NegotiationMessage;
import me.beratta.nixathon.game.dto.NegotiationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds negotiation messages for the current turn.
 * <p>
 * Negotiation is treated as soft influence, not a binding treaty:
 * we prioritize information warfare and coordinated pressure while avoiding
 * over-trusting players with hostile/betrayal history.
 */
@Service
public class NegotiationStrategyService {

    private static final Logger log = LoggerFactory.getLogger(NegotiationStrategyService.class);

    // The total number of enemies at the start of the game.
    private static final int FOUR_PLAYER_ENEMY_COUNT = 3;

    private final ThreatAssessmentService threatAssessmentService;
    private final GameMemoryService gameMemoryService;

    public NegotiationStrategyService(
            ThreatAssessmentService threatAssessmentService,
            GameMemoryService gameMemoryService
    ) {
        this.threatAssessmentService = threatAssessmentService;
        this.gameMemoryService = gameMemoryService;
    }

    /**
     * Creates the outgoing diplomacy message list for a negotiation phase.
     */
    public List<NegotiationMessage> planNegotiation(NegotiationRequest request) {
        log.debug(
                "Planning negotiation game={} turn={} self={} resources={} enemies={} observedActions={}",
                request.gameId(),
                request.turn(),
                request.playerTower().playerId(),
                request.playerTower().resources(),
                request.enemyTowers().size(),
                request.combatActions().size()
        );

        // Get all enemy towers with HP greater than 0.
        List<EnemyTowerState> enemies = request.enemyTowers().stream()
                .filter(enemy -> enemy.hp() > 0)
                .toList();

        // Keep long-term behavioral memory updated even if we end up sending no message.
        // This means we are updating the player profiles (level, durability).
        // We also update which turn duel mode starts by the remaining enemy count.
        gameMemoryService.observeNegotiationTurn(request.gameId(), request.turn(), enemies);

        // If enemy size is one (or less) we skip negotiation.
        if (enemies.size() <= 1) {
            gameMemoryService.storeNegotiationPlan(request.gameId(), request.turn(), List.of());
            log.info(
                    "Negotiation skipped game={} turn={} reason=insufficient_alive_enemies aliveEnemies={}",
                    request.gameId(),
                    request.turn(),
                    enemies.size()
            );
            return List.of();
        }

        // We get the player profile for each enemy.
        Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy = gameMemoryService
                .getPlayerProfiles(request.gameId(), enemies);

        // We are calculating threat value for each enemy.
        Map<Integer, Integer> threatByEnemy = threatAssessmentService.assessNegotiationThreat(request, profilesByEnemy);

        // The maximum enemy level.
        int maxEnemyLevel = enemies.stream()
                .mapToInt(EnemyTowerState::level)
                .max()
                .orElse(request.playerTower().level());

        // We are selecting the most dangerous enemy based on several factors, but similar to threat.
        EnemyTowerState primaryTarget = pickPrimaryTarget(
                enemies,
                threatByEnemy,
                profilesByEnemy,
                request.turn(),
                request.playerTower().level(),
                maxEnemyLevel
        );


        // Calculate alliance score for every enemy, except for our primary target.
        List<EnemyTowerState> allianceCandidates = rankAllianceCandidates(
                enemies,
                threatByEnemy,
                profilesByEnemy,
                primaryTarget.playerId(),
                maxEnemyLevel
        );

        // Calculate how many negotiation messages we want to send.
        int messageLimit = chooseMessageLimit(
                threatByEnemy,
                enemies,
                profilesByEnemy,
                primaryTarget,
                request.turn(),
                maxEnemyLevel
        );

        // Our negotiation messages
        List<NegotiationMessage> messages = new ArrayList<>();

        // Players to whom we already send a negotiation message.
        Set<Integer> usedRecipients = new HashSet<>();

        // Start with one explicit peace candidate if we have a credible partner.
        EnemyTowerState explicitAllianceCandidate = chooseExplicitAllianceCandidate(
                allianceCandidates,
                threatByEnemy,
                profilesByEnemy
        );

        // If we found a guy who could be our ally, give him priority.
        if (messageLimit > 0 && explicitAllianceCandidate != null) {
            messages.add(new NegotiationMessage(explicitAllianceCandidate.playerId(), null));
            usedRecipients.add(explicitAllianceCandidate.playerId());
        }

        for (EnemyTowerState allyCandidate : allianceCandidates) {
            // We can't / don't want to send more messages
            if (messages.size() >= messageLimit) {
                break;
            }

            // We already sent a message to this guy.
            if (usedRecipients.contains(allyCandidate.playerId())) {
                continue;
            }

            // Unreliable means we were betrayed or trust score is too low, or too much threat.
            if (isUnreliableRecipient(allyCandidate, threatByEnemy, profilesByEnemy)) {
                continue;
            }

            // Non-empty attack target communicates a likely focus fire plan.
            messages.add(new NegotiationMessage(allyCandidate.playerId(), primaryTarget.playerId()));
            usedRecipients.add(allyCandidate.playerId());
        }

        // Fallback: if every candidate looked unreliable, still send one directional signal to attack primary target.
        if (messages.isEmpty() && messageLimit > 0 && !allianceCandidates.isEmpty()) {
            EnemyTowerState fallbackRecipient = allianceCandidates.getFirst();
            messages.add(new NegotiationMessage(fallbackRecipient.playerId(), primaryTarget.playerId()));
        }

        gameMemoryService.storeNegotiationPlan(request.gameId(), request.turn(), messages);
        log.info(
                "Negotiation planned game={} turn={} primaryTarget={} allyMessages={} hostilityMap={} trustedRecipients={}",
                request.gameId(),
                request.turn(),
                primaryTarget.playerId(),
                messages.size(),
                threatByEnemy,
                messages.stream().map(NegotiationMessage::allyId).toList()
        );
        return List.copyOf(messages);
    }

    /**
     * Picks the best enemy to frame as the attack focus in negotiation messages.
     */
    private EnemyTowerState pickPrimaryTarget(
            List<EnemyTowerState> enemies,
            Map<Integer, Integer> threatByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            int turn,
            int selfLevel,
            int maxEnemyLevel
    ) {
        // Return the enemy with the highest targetPriority.
        return enemies.stream()
                .max(Comparator.comparingDouble(
                        enemy -> targetPriority(
                                enemy,
                                threatByEnemy,
                                profilesByEnemy,
                                turn,
                                selfLevel,
                                maxEnemyLevel,
                                enemies.size()
                        )
                ))
                .orElseThrow();
    }

    /**
     * Orders possible message recipients by expected reliability/utility.
     */
    private List<EnemyTowerState> rankAllianceCandidates(
            List<EnemyTowerState> enemies,
            Map<Integer, Integer> threatByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            int excludedEnemyId,
            int maxEnemyLevel
    ) {
        // Calculate alliance score for each enemy (except primary target) and sort them from highest to lowest.
        return enemies.stream()
                .filter(enemy -> enemy.playerId() != excludedEnemyId)
                .sorted(Comparator.comparingDouble(
                                (EnemyTowerState enemy) -> allianceScore(
                                        enemy,
                                        threatByEnemy,
                                        profilesByEnemy,
                                        maxEnemyLevel
                                )
                        )
                        .reversed())
                .toList();
    }

    /**
     * Limits diplomacy fanout to reduce noise in high-risk or late-game states.
     */
    private int chooseMessageLimit(
            Map<Integer, Integer> threatByEnemy,
            List<EnemyTowerState> enemies,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            EnemyTowerState primaryTarget,
            int turn,
            int maxEnemyLevel
    ) {
        int enemyCount = enemies.size();

        // If there is only two enemy left, we want to send only one message.
        if (enemyCount == 2) {
            return 1;
        }

        int maxThreat = threatByEnemy.values().stream()
                .max(Integer::compareTo)
                .orElse(0);

        // Default desired message count.
        int desiredMessageCount = 2;

        // In early game we want to send more messages.
        if (turn <= 6 && enemyCount >= 4) {
            desiredMessageCount = 3;
        }

        // If everyone leaves, and our primary target is economic leader we want to send message to everyone.
        if (enemyCount >= FOUR_PLAYER_ENEMY_COUNT
                && isEconomicLeader(primaryTarget, profilesByEnemy, maxEnemyLevel)
                && turn <= 14) {
            desiredMessageCount = 3;
        }

        // Late game, and there is a lot of threat -> send less messages.
        if (maxThreat >= 55 || turn >= 16) {
            desiredMessageCount = 2;
        }

        return Math.min(desiredMessageCount, Math.max(enemyCount - 1, 0));
    }

    /**
     * Finds one candidate worthy of an explicit peace message (attackTargetId is empty in message).
     * This function can return null, which means no one is good enough to be our ally.
     */
    private EnemyTowerState chooseExplicitAllianceCandidate(
            List<EnemyTowerState> allianceCandidates,
            Map<Integer, Integer> threatByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy
    ) {
        // Alliance candidates is sorted, so we go from high alliance score to low alliance score.
        for (EnemyTowerState candidate : allianceCandidates) {
            // Get the player profile.
            GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(candidate.playerId());
            int threat = threatByEnemy.getOrDefault(candidate.playerId(), 0);

            // If we have no historical data about this player skip him.
            if (profile == null) {
                continue;
            }

            // Completely heuristic:
            // Wwe choose the player as our ally if he is not planning late game attack and has low threat.
            if (profile.trustScore() >= 10
                    && profile.betrayalsAgainstUs() == 0
                    && threat < 45
                    && !profile.likelyAfkCollector()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Rejects recipients with strong signs of unreliability.
     */
    private boolean isUnreliableRecipient(
            EnemyTowerState candidate,
            Map<Integer, Integer> threatByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy
    ) {
        GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(candidate.playerId());

        // If we have no historical data then give him the right to assume he is a good guy.
        if (profile == null) {
            return false;
        }

        // If he betrayed us or threat is too high he is unreliable for sure.
        int threat = threatByEnemy.getOrDefault(candidate.playerId(), 0);
        if (profile.betrayalsAgainstUs() > 0 && threat > 20) {
            return true;
        }


        // Trust score is too low, it also means unreliable.
        if (profile.trustScore() < -8) {
            return true;
        }

        return profile.threatDeclarationsToUs() > 0 && threat > 35;
    }

    /**
     * Scores enemies as strategic attack focus candidates.
     */
    private double targetPriority(
            EnemyTowerState enemy,
            Map<Integer, Integer> threatByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            int turn,
            int selfLevel,
            int maxEnemyLevel,
            int enemyCount
    ) {
        // Get the profile snapshots for the enemies.
        GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(enemy.playerId());

        int threat = threatByEnemy.getOrDefault(enemy.playerId(), 0);
        int durability = enemy.effectiveDurability(); // Effective durability is HP + shield
        int levelLeadOverUs = enemy.level() - selfLevel;

        // Completely heuristic score.
        double score = (threat * 2.1) + (enemy.level() * 8.0) + (170.0 - durability);

        // Apply data from profile.
        if (profile != null) {
            // These values already influenced the threat score, but we count them again anyway.
            score += profile.hostilityScore() * 0.6;
            score += profile.afkHoardingRisk() * 1.2;
            score += profile.betrayalsAgainstUs() * 12.0;

            // This could mean the enemy is saving resources for later.
            if (profile.consecutiveNoAttackTurns() >= 2 && enemy.level() >= 2) {
                score += 6;
            }
        }


        // If everyone is still alive focus the player with probably the most resources.
        if (enemyCount >= FOUR_PLAYER_ENEMY_COUNT
                && isEconomicLeader(enemy, profilesByEnemy, maxEnemyLevel)) {
            score += 20;
        }

        // If every is still alive focus players with high level lead over us.
        if (enemyCount >= FOUR_PLAYER_ENEMY_COUNT && levelLeadOverUs >= 2) {
            score += 16;
        }

        // Late game: emphasize high-level survivors to avoid resource snowball losses.
        if (turn >= 18) {
            score += enemy.level() * 2.5;
        }
        return score;
    }

    /**
     * Scores enemy as a negotiation recipient candidate.
     */
    private double allianceScore(
            EnemyTowerState enemy,
            Map<Integer, Integer> threatByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            int maxEnemyLevel
    ) {
        // Get the player profile.
        GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(enemy.playerId());
        int threat = threatByEnemy.getOrDefault(enemy.playerId(), 0);

        // We don't really want to be allied with a strong player, we want to focus it.
        double economicLeaderPenalty = isEconomicLeader(enemy, profilesByEnemy, maxEnemyLevel) ? 12.0 : 0.0;

        // Profile data unavailable, use some heuristics.
        if (profile == null) {
            return (enemy.level() * 2.0) - threat - economicLeaderPenalty;
        }

        double trust = profile.trustScore();
        double betrayalPenalty = profile.betrayalsAgainstUs() * 15.0;

        // If our player is an AFK collector we don't want to ally with him even more.
        if (profile.likelyAfkCollector()) {
            economicLeaderPenalty += 12.0;
        }

        // Again, completely heuristic scores...
        return (trust * 1.4)
                - (threat * 1.2)
                - profile.afkHoardingRisk()
                - betrayalPenalty
                - economicLeaderPenalty
                + (enemy.level() * 2.0);
    }

    /**
     * Identifies level leaders that look like snowball threats in multi-player states.
     */
    private boolean isEconomicLeader(
            EnemyTowerState enemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            int maxEnemyLevel
    ) {
        // Player's level has to be the highest among all the players, but at least 2.
        if (enemy.level() < maxEnemyLevel || maxEnemyLevel < 2) {
            return false;
        }

        GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(enemy.playerId());
        if (profile == null) {
            return true;
        }

        return profile.likelyAfkCollector();
    }
}
