package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.CombatActionResponse;
import me.beratta.nixathon.game.dto.CombatRequest;
import me.beratta.nixathon.game.dto.EnemyTowerState;
import me.beratta.nixathon.game.dto.PlayerAttack;
import me.beratta.nixathon.game.dto.PlayerDiplomacy;
import me.beratta.nixathon.game.dto.TowerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main combat decision engine.
 * <p>
 * Produces one turn of combat actions (`armor`, `attack`, `upgrade`) from current
 * state + historical behavior profiles + fatigue forecast.
 */
@Service
public class CombatStrategyService {

    private static final Logger log = LoggerFactory.getLogger(CombatStrategyService.class);

    private static final int MAX_TURNS = 25;
    private static final int FOUR_PLAYER_ENEMY_COUNT = 3;

    private final ThreatAssessmentService threatAssessmentService;
    private final EconomyService economyService;
    private final GameMemoryService gameMemoryService;
    private final FatigueService fatigueService;

    public CombatStrategyService(
            ThreatAssessmentService threatAssessmentService,
            EconomyService economyService,
            GameMemoryService gameMemoryService,
            FatigueService fatigueService
    ) {
        this.threatAssessmentService = threatAssessmentService;
        this.economyService = economyService;
        this.gameMemoryService = gameMemoryService;
        this.fatigueService = fatigueService;
    }

    /**
     * Plans a complete combat response and guarantees response safety through sanitization.
     */
    public List<CombatActionResponse> planCombat(CombatRequest request) {
        long startNanos = System.nanoTime();
        List<EnemyTowerState> aliveEnemies = request.enemyTowers().stream()
                .filter(enemy -> enemy.hp() > 0)
                .toList();

        TowerState playerTower = request.playerTower();
        int totalResources = playerTower.resources();
        if (aliveEnemies.isEmpty() || totalResources <= 0) {
            log.info(
                    "Combat planning skipped game={} turn={} reason={} aliveEnemies={} resources={}",
                    request.gameId(),
                    request.turn(),
                    aliveEnemies.isEmpty() ? "no_alive_enemies" : "no_resources",
                    aliveEnemies.size(),
                    totalResources
            );
            return List.of();
        }

        // 1) Refresh long-term profiles and derive strategic signals.
        gameMemoryService.observeCombatTurn(request);
        Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy = gameMemoryService
                .getPlayerProfiles(request.gameId(), aliveEnemies);
        Map<Integer, Integer> threatByEnemy = threatAssessmentService.assessCombatThreat(request, profilesByEnemy);
        DiplomacySignals diplomacySignals = analyzeDiplomacy(request);
        Map<Integer, Integer> predictedCounterTroopsByEnemy = estimateCounterTroopsByEnemy(
                request,
                aliveEnemies,
                threatByEnemy,
                profilesByEnemy,
                diplomacySignals
        );
        GameMemoryService.NegotiationCommitment commitment = gameMemoryService
                .findCommitment(request.gameId(), request.turn())
                .orElse(null);
        Integer duelStartTurn = gameMemoryService.findDuelStartTurn(request.gameId()).orElse(null);
        FatigueService.FatigueForecast fatigue = fatigueService
                .forecast(request.turn(), aliveEnemies.size(), duelStartTurn);
        CombatPosture posture = determinePosture(request, aliveEnemies.size(), threatByEnemy, fatigue);

        // 2) Estimate defensive needs first, then decide whether economy upgrade is safe.
        int expectedIncomingDamage = estimateIncomingDamage(predictedCounterTroopsByEnemy, profilesByEnemy, posture);
        int baselineArmorSpend = baselineArmorSpend(playerTower, expectedIncomingDamage, totalResources, posture);
        int upgradeCost = economyService.upgradeCost(playerTower.level());

        boolean shouldUpgrade = shouldUpgrade(
                request,
                aliveEnemies,
                posture,
                fatigue,
                totalResources,
                baselineArmorSpend,
                expectedIncomingDamage,
                upgradeCost
        );

        // 3) Allocate resources to attack planning based on posture/fatigue urgency.
        int resourcesAfterUpgrade = shouldUpgrade ? totalResources - upgradeCost : totalResources;
        List<EnemyTowerState> rankedTargets = rankTargets(
                request.turn(),
                request.playerTower().level(),
                aliveEnemies,
                threatByEnemy,
                profilesByEnemy,
                diplomacySignals,
                commitment,
                posture,
                fatigue,
                resourcesAfterUpgrade
        );
        EnemyTowerState primaryTarget = rankedTargets.isEmpty() ? null : rankedTargets.getFirst();

        int armorSpend = planArmorSpend(
                playerTower,
                primaryTarget,
                baselineArmorSpend,
                resourcesAfterUpgrade,
                predictedCounterTroopsByEnemy,
                posture,
                fatigue
        );
        int resourcesAfterDefense = Math.max(resourcesAfterUpgrade - armorSpend, 0);

        // 4) Build candidate actions and sanitize so API contract can never be violated.
        Map<Integer, Integer> attacks = planAttacks(
                rankedTargets,
                profilesByEnemy,
                threatByEnemy,
                commitment,
                resourcesAfterDefense,
                predictedCounterTroopsByEnemy,
                posture,
                fatigue
        );
        int cancellationMitigation = estimateCancellationMitigation(attacks, predictedCounterTroopsByEnemy);
        int expectedIncomingAfterCancellation = Math.max(0, expectedIncomingDamage - cancellationMitigation);
        int revisedBaselineArmorSpend = baselineArmorSpend(playerTower, expectedIncomingAfterCancellation, resourcesAfterUpgrade, posture);
        int revisedArmorSpend = planArmorSpend(
                playerTower,
                primaryTarget,
                revisedBaselineArmorSpend,
                resourcesAfterUpgrade,
                predictedCounterTroopsByEnemy,
                posture,
                fatigue
        );
        if (revisedArmorSpend < armorSpend) {
            int freedTroops = armorSpend - revisedArmorSpend;
            armorSpend = revisedArmorSpend;
            EnemyTowerState reinforcementTarget = chooseFocusTarget(rankedTargets, Set.of(), commitment);
            if (reinforcementTarget != null && freedTroops > 0) {
                attacks.merge(reinforcementTarget.playerId(), freedTroops, Integer::sum);
            }
        }

        List<CombatActionResponse> proposedActions = new ArrayList<>();
        if (armorSpend > 0) {
            proposedActions.add(CombatActionResponse.armor(armorSpend));
        }
        for (Map.Entry<Integer, Integer> attack : attacks.entrySet()) {
            proposedActions.add(CombatActionResponse.attack(attack.getKey(), attack.getValue()));
        }
        if (shouldUpgrade) {
            proposedActions.add(CombatActionResponse.upgrade());
        }

        List<CombatActionResponse> sanitizedActions = sanitizeActions(proposedActions, totalResources, upgradeCost);
        long durationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        log.info(
                "Combat planned game={} turn={} posture={} fatigueActive={} fatigueDamage={} expectedIncomingDamage={} expectedIncomingAfterCancellation={} armorSpend={} shouldUpgrade={} proposedActions={} finalActions={} durationMs={}",
                request.gameId(),
                request.turn(),
                posture,
                fatigue.active(),
                fatigue.currentDamage(),
                expectedIncomingDamage,
                expectedIncomingAfterCancellation,
                armorSpend,
                shouldUpgrade,
                proposedActions.size(),
                sanitizedActions.size(),
                durationMs
        );
        log.debug(
                "Combat planning details game={} turn={} threatByEnemy={} predictedCounterTroopsByEnemy={} profiles={} commitment={} rankedTargets={} attacks={} actions={}",
                request.gameId(),
                request.turn(),
                threatByEnemy,
                predictedCounterTroopsByEnemy,
                profilesByEnemy,
                commitment,
                rankedTargets.stream().map(EnemyTowerState::playerId).toList(),
                attacks,
                sanitizedActions
        );

        return sanitizedActions;
    }

    /**
     * Chooses a high-level tactical posture for the turn.
     */
    private CombatPosture determinePosture(
            CombatRequest request,
            int aliveEnemyCount,
            Map<Integer, Integer> threatByEnemy,
            FatigueService.FatigueForecast fatigue
    ) {
        int totalThreat = threatByEnemy.values().stream().mapToInt(Integer::intValue).sum();
        int hp = request.playerTower().hp();

        if (hp <= 25 || totalThreat >= (hp * 2)) {
            return CombatPosture.SURVIVAL;
        }
        if (fatigue.active() && fatigue.currentDamage() >= 20) {
            return CombatPosture.CLOSER;
        }
        if (fatigue.turnsUntilStart() <= 2 || request.turn() >= 18 || aliveEnemyCount <= 2) {
            return CombatPosture.AGGRESSIVE;
        }
        return CombatPosture.BALANCED;
    }

    /**
     * Estimates troops likely aimed at us this turn for each enemy.
     * <p>
     * This drives both armor planning and cancellation-aware attack sizing.
     */
    private Map<Integer, Integer> estimateCounterTroopsByEnemy(
            CombatRequest request,
            List<EnemyTowerState> aliveEnemies,
            Map<Integer, Integer> threatByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            DiplomacySignals diplomacySignals
    ) {
        int selfId = request.playerTower().playerId();
        int selfLevel = request.playerTower().level();
        Map<Integer, Integer> lastTurnTroopsToUsByEnemy = new HashMap<>();
        for (PlayerAttack attack : request.previousAttacks()) {
            if (attack.action().targetId() == selfId) {
                lastTurnTroopsToUsByEnemy.merge(attack.playerId(), attack.action().troopCount(), Integer::sum);
            }
        }

        Map<Integer, Integer> counterTroopsByEnemy = new HashMap<>();
        for (EnemyTowerState enemy : aliveEnemies) {
            int enemyId = enemy.playerId();
            GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(enemyId);

            int lastTurnTroops = lastTurnTroopsToUsByEnemy.getOrDefault(enemyId, 0);
            int historicalAverage = 0;
            if (profile != null && profile.attacksAgainstUsCount() > 0) {
                historicalAverage = (int) Math.round((double) profile.attacksAgainstUsTroops() / profile.attacksAgainstUsCount());
            }
            int threatProjection = (int) Math.round(threatByEnemy.getOrDefault(enemyId, 0) * 0.35);
            int predictedTroops = Math.max(lastTurnTroops, Math.max(historicalAverage, threatProjection));

            if (profile != null && profile.consecutiveAttackTurns() >= 2) {
                predictedTroops += 4;
            }
            if (profile != null && profile.likelyAfkCollector()) {
                predictedTroops += 5 + (enemy.level() * 2);
            }
            if (profile != null
                    && profile.consecutiveNoAttackTurns() >= 2
                    && enemy.level() >= (selfLevel + 2)) {
                predictedTroops += 6;
            }
            if (diplomacySignals.explicitThreatEnemies().contains(enemyId)) {
                predictedTroops += 6;
            }
            if (diplomacySignals.peaceOfferingEnemies().contains(enemyId)
                    && profile != null
                    && profile.betrayalsAgainstUs() == 0
                    && profile.trustScore() >= 10) {
                predictedTroops = (int) Math.floor(predictedTroops * 0.65);
            }

            counterTroopsByEnemy.put(enemyId, Math.max(0, Math.min(predictedTroops, 160)));
        }

        return Map.copyOf(counterTroopsByEnemy);
    }

    /**
     * Predicts likely incoming damage from per-enemy incoming troop estimates.
     */
    private int estimateIncomingDamage(
            Map<Integer, Integer> predictedCounterTroopsByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            CombatPosture posture
    ) {
        int expectedIncoming = predictedCounterTroopsByEnemy.values().stream()
                .mapToInt(Integer::intValue)
                .sum();

        int volatilityPenalty = profilesByEnemy.values().stream()
                .mapToInt(profile -> (profile.betrayalsAgainstUs() * 3) + (profile.consecutiveAttackTurns() * 2))
                .sum();
        expectedIncoming += volatilityPenalty;

        if (posture == CombatPosture.SURVIVAL) {
            expectedIncoming += 6;
        }
        if (posture == CombatPosture.CLOSER) {
            expectedIncoming = Math.max(0, expectedIncoming - 5);
        }

        return Math.max(expectedIncoming, 0);
    }

    /**
     * Computes baseline armor investment for this turn before offensive allocation.
     */
    private int baselineArmorSpend(
            TowerState playerTower,
            int expectedIncomingDamage,
            int resourceCap,
            CombatPosture posture
    ) {
        int desiredArmor = expectedIncomingDamage + safetyBuffer(playerTower.hp(), posture);
        int additionalArmorNeeded = Math.max(desiredArmor - playerTower.armor(), 0);
        int defenseCap = (int) Math.round(resourceCap * defenseShare(posture));
        return Math.min(additionalArmorNeeded, defenseCap);
    }

    /**
     * Adds HP/posture-sensitive armor buffer on top of incoming damage estimate.
     */
    private int safetyBuffer(int hp, CombatPosture posture) {
        int baseline;
        if (hp <= 25) {
            baseline = 14;
        } else if (hp <= 50) {
            baseline = 10;
        } else {
            baseline = 6;
        }

        if (posture == CombatPosture.SURVIVAL) {
            return baseline + 8;
        }
        if (posture == CombatPosture.CLOSER) {
            return Math.max(4, baseline - 2);
        }
        return baseline;
    }

    /**
     * Upper-bounds defense allocation by tactical posture.
     */
    private double defenseShare(CombatPosture posture) {
        return switch (posture) {
            case SURVIVAL -> 0.70;
            case BALANCED -> 0.55;
            case AGGRESSIVE -> 0.40;
            case CLOSER -> 0.35;
        };
    }

    /**
     * Determines whether spending on upgrade has acceptable survival and ROI risk this turn.
     */
    private boolean shouldUpgrade(
            CombatRequest request,
            List<EnemyTowerState> aliveEnemies,
            CombatPosture posture,
            FatigueService.FatigueForecast fatigue,
            int totalResources,
            int baselineArmorSpend,
            int expectedIncomingDamage,
            int upgradeCost
    ) {
        if (shouldForceLevelTieBreakUpgrade(
                request,
                aliveEnemies,
                fatigue,
                totalResources,
                baselineArmorSpend,
                expectedIncomingDamage,
                upgradeCost
        )) {
            return true;
        }
        if (shouldTakeEconomicUpgrade(
                request,
                aliveEnemies,
                posture,
                fatigue,
                totalResources,
                baselineArmorSpend,
                expectedIncomingDamage,
                upgradeCost
        )) {
            return true;
        }

        if (upgradeCost > totalResources) {
            return false;
        }
        if (posture != CombatPosture.BALANCED) {
            return false;
        }
        if (fatigue.active() || fatigue.turnsUntilStart() <= 3) {
            return false;
        }
        if (request.turn() > 12 || aliveEnemies.size() < 3) {
            return false;
        }
        if (expectedIncomingDamage >= (request.playerTower().hp() / 2)) {
            return false;
        }

        int resourcesAfterUpgrade = totalResources - upgradeCost;
        if (resourcesAfterUpgrade < baselineArmorSpend + 25) {
            return false;
        }

        int turnsRemaining = Math.max(0, MAX_TURNS - request.turn());
        int projectedUpgradeReturn = economyService.estimatedUpgradeReturn(request.playerTower().level(), turnsRemaining);
        return projectedUpgradeReturn >= (int) Math.round(upgradeCost * 0.60);
    }

    /**
     * 4-player anti-snowball upgrade policy.
     * <p>
     * When we are behind the level curve but still safe, prioritize catching up economically
     * so one upgrader cannot dominate the mid game with one large troop spike.
     */
    private boolean shouldTakeEconomicUpgrade(
            CombatRequest request,
            List<EnemyTowerState> aliveEnemies,
            CombatPosture posture,
            FatigueService.FatigueForecast fatigue,
            int totalResources,
            int baselineArmorSpend,
            int expectedIncomingDamage,
            int upgradeCost
    ) {
        if (aliveEnemies.size() < FOUR_PLAYER_ENEMY_COUNT) {
            return false;
        }
        if (upgradeCost > totalResources) {
            return false;
        }
        if (posture == CombatPosture.SURVIVAL || posture == CombatPosture.CLOSER) {
            return false;
        }
        if (fatigue.active() || fatigue.turnsUntilStart() <= 4) {
            return false;
        }
        if (request.turn() > 14) {
            return false;
        }
        if (expectedIncomingDamage >= (int) Math.round(request.playerTower().hp() * 0.45)) {
            return false;
        }

        int currentLevel = request.playerTower().level();
        int maxEnemyLevel = aliveEnemies.stream()
                .mapToInt(EnemyTowerState::level)
                .max()
                .orElse(currentLevel);
        boolean behindCurve = maxEnemyLevel >= (currentLevel + 1);
        boolean farBehindCurve = maxEnemyLevel >= (currentLevel + 2);
        if (!behindCurve) {
            return false;
        }

        int resourcesAfterUpgrade = totalResources - upgradeCost;
        int reserveFloor = baselineArmorSpend + (farBehindCurve ? 16 : 20);
        if (resourcesAfterUpgrade < reserveFloor) {
            return false;
        }

        int turnsRemaining = Math.max(0, MAX_TURNS - request.turn());
        int projectedUpgradeReturn = economyService.estimatedUpgradeReturn(currentLevel, turnsRemaining);
        double roiFloor = farBehindCurve ? 0.35 : 0.45;
        return projectedUpgradeReturn >= (int) Math.round(upgradeCost * roiFloor);
    }

    /**
     * Late duel override: if fatigue can force a simultaneous death, buy one level to win tie-breaks.
     */
    private boolean shouldForceLevelTieBreakUpgrade(
            CombatRequest request,
            List<EnemyTowerState> aliveEnemies,
            FatigueService.FatigueForecast fatigue,
            int totalResources,
            int baselineArmorSpend,
            int expectedIncomingDamage,
            int upgradeCost
    ) {
        if (aliveEnemies.size() != 1) {
            return false;
        }

        EnemyTowerState duelEnemy = aliveEnemies.getFirst();
        int currentLevel = request.playerTower().level();
        int upgradedLevel = currentLevel + 1;
        if (upgradedLevel <= duelEnemy.level()) {
            return false;
        }
        if (upgradeCost > totalResources) {
            return false;
        }

        boolean lateOrFatiguePressure = fatigue.active() || fatigue.turnsUntilStart() <= 1 || request.turn() >= 22;
        if (!lateOrFatiguePressure) {
            return false;
        }

        int resourcesAfterUpgrade = totalResources - upgradeCost;
        int minimumDefensiveReserve = Math.max(8, baselineArmorSpend / 2);
        if (resourcesAfterUpgrade < minimumDefensiveReserve) {
            return false;
        }
        return expectedIncomingDamage < request.playerTower().hp();
    }

    /**
     * Refines armor spend after considering immediate kill windows and fatigue pressure.
     */
    private int planArmorSpend(
            TowerState playerTower,
            EnemyTowerState primaryTarget,
            int baselineArmorSpend,
            int resourcesAfterUpgrade,
            Map<Integer, Integer> predictedCounterTroopsByEnemy,
            CombatPosture posture,
            FatigueService.FatigueForecast fatigue
    ) {
        int armorSpend = Math.min(baselineArmorSpend, resourcesAfterUpgrade);

        if (primaryTarget == null) {
            return armorSpend;
        }

        int killCost = primaryTarget.effectiveDurability() + predictedCounterTroopsByEnemy
                .getOrDefault(primaryTarget.playerId(), 0);
        boolean immediateKillWindow = killCost <= resourcesAfterUpgrade;
        if (immediateKillWindow
                && (posture == CombatPosture.CLOSER
                || posture == CombatPosture.AGGRESSIVE
                || primaryTarget.hp() <= 40)) {
            int maxArmorWithKill = Math.max(0, resourcesAfterUpgrade - killCost);
            armorSpend = Math.min(armorSpend, maxArmorWithKill);
        }

        if (fatigue.active() && fatigue.currentDamage() >= 40) {
            armorSpend = (int) Math.floor(armorSpend * 0.70);
        }

        if (playerTower.hp() <= 20 && posture == CombatPosture.SURVIVAL) {
            armorSpend = Math.max(armorSpend, Math.min(resourcesAfterUpgrade, baselineArmorSpend + 8));
        }

        return Math.max(0, Math.min(armorSpend, resourcesAfterUpgrade));
    }

    /**
     * Orders enemies for attack allocation.
     */
    private List<EnemyTowerState> rankTargets(
            int turn,
            int playerLevel,
            List<EnemyTowerState> enemies,
            Map<Integer, Integer> threatByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            DiplomacySignals diplomacySignals,
            GameMemoryService.NegotiationCommitment commitment,
            CombatPosture posture,
            FatigueService.FatigueForecast fatigue,
            int availableAttackBudget
    ) {
        int maxEnemyLevel = enemies.stream()
                .mapToInt(EnemyTowerState::level)
                .max()
                .orElse(playerLevel);
        return enemies.stream()
                .sorted(Comparator.comparingDouble(
                        (EnemyTowerState enemy) -> targetPriority(
                                turn,
                                playerLevel,
                                enemies.size(),
                                maxEnemyLevel,
                                enemy,
                                threatByEnemy,
                                profilesByEnemy,
                                diplomacySignals,
                                commitment,
                                posture,
                                fatigue,
                                availableAttackBudget
                        )
                ).reversed())
                .toList();
    }

    /**
     * Composite target score that blends immediate threat, profile risk, diplomacy signals,
     * and fatigue urgency.
     */
    private double targetPriority(
            int turn,
            int playerLevel,
            int aliveEnemyCount,
            int maxEnemyLevel,
            EnemyTowerState enemy,
            Map<Integer, Integer> threatByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            DiplomacySignals diplomacySignals,
            GameMemoryService.NegotiationCommitment commitment,
            CombatPosture posture,
            FatigueService.FatigueForecast fatigue,
            int availableAttackBudget
    ) {
        GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(enemy.playerId());
        int durability = enemy.effectiveDurability();
        int threat = threatByEnemy.getOrDefault(enemy.playerId(), 0);
        double score = (threat * 2.0) + (enemy.level() * 7.0) + (170.0 - durability);
        int levelLeadOverUs = enemy.level() - playerLevel;

        score += diplomacySignals.focusBoostByTarget().getOrDefault(enemy.playerId(), 0) * 1.2;
        if (diplomacySignals.peaceOfferingEnemies().contains(enemy.playerId())) {
            score -= 7;
        }
        if (diplomacySignals.explicitThreatEnemies().contains(enemy.playerId())) {
            score += 14;
        }

        if (profile != null) {
            score += profile.hostilityScore() * 0.55;
            score += profile.afkHoardingRisk() * 1.25;
            score += profile.betrayalsAgainstUs() * 14.0;
            if (profile.likelyAfkCollector()) {
                score += turn >= 8 ? 12 : 6;
            }
            if (profile.consecutiveNoAttackTurns() >= 3 && enemy.level() >= 2) {
                score += 8;
            }
            if (profile.trustScore() > 20 && profile.betrayalsAgainstUs() == 0 && posture == CombatPosture.BALANCED) {
                score -= 10;
            }
            if (profile.trustScore() < 0) {
                score += 4;
            }
        }

        if (aliveEnemyCount >= FOUR_PLAYER_ENEMY_COUNT) {
            if (enemy.level() == maxEnemyLevel && enemy.level() >= (playerLevel + 1)) {
                score += 14;
            }
            if (levelLeadOverUs >= 2) {
                score += 18;
            }
        }

        if (commitment != null) {
            if (commitment.focusTargetId() != null && enemy.playerId() == commitment.focusTargetId()) {
                score += 16;
            }
            if (commitment.explicitAlliedPlayerIds().contains(enemy.playerId())) {
                if (profile != null && profile.trustScore() >= 22 && profile.betrayalsAgainstUs() == 0 && posture == CombatPosture.BALANCED) {
                    score -= 8;
                } else {
                    score += 6;
                }
            }
        }

        if (durability <= availableAttackBudget) {
            score += 10;
        }
        if (fatigue.active() || fatigue.turnsUntilStart() <= 2) {
            score += (enemy.level() * 2.0);
            if (durability <= availableAttackBudget) {
                score += 10;
            }
        }
        if (posture == CombatPosture.CLOSER) {
            score += 12;
        }

        return score;
    }

    /**
     * Allocates troops across secure eliminations first, then pressure damage.
     */
    private Map<Integer, Integer> planAttacks(
            List<EnemyTowerState> rankedTargets,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            Map<Integer, Integer> threatByEnemy,
            GameMemoryService.NegotiationCommitment commitment,
            int attackBudget,
            Map<Integer, Integer> predictedCounterTroopsByEnemy,
            CombatPosture posture,
            FatigueService.FatigueForecast fatigue
    ) {
        LinkedHashMap<Integer, Integer> attacks = new LinkedHashMap<>();
        if (attackBudget <= 0 || rankedTargets.isEmpty()) {
            return attacks;
        }

        int remainingBudget = attackBudget;
        int securedEliminations = 0;
        int eliminationLimit = posture == CombatPosture.SURVIVAL ? 1 : 2;

        for (EnemyTowerState target : rankedTargets) {
            if (remainingBudget <= 0) {
                break;
            }

            int killCost = target.effectiveDurability() + predictedCounterTroopsByEnemy
                    .getOrDefault(target.playerId(), 0);
            GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(target.playerId());
            if (killCost > remainingBudget) {
                continue;
            }
            if (!shouldSecureKill(
                    target,
                    killCost,
                    profile,
                    threatByEnemy.getOrDefault(target.playerId(), 0),
                    rankedTargets.size(),
                    attackBudget,
                    posture,
                    fatigue
            )) {
                continue;
            }

            attacks.put(target.playerId(), killCost);
            remainingBudget -= killCost;
            securedEliminations++;

            // In pure survival posture avoid overextending once one elimination is secured.
            if (securedEliminations >= eliminationLimit && posture == CombatPosture.SURVIVAL) {
                break;
            }
        }

        // Spend leftover troops as pressure on the best remaining focus target.
        if (remainingBudget > 0) {
            EnemyTowerState focusTarget = chooseFocusTarget(rankedTargets, attacks.keySet(), commitment);
            if (focusTarget != null) {
                boolean canSplitPressure = rankedTargets.size() >= FOUR_PLAYER_ENEMY_COUNT
                        && posture != CombatPosture.SURVIVAL
                        && remainingBudget >= 16;
                if (canSplitPressure) {
                    EnemyTowerState secondaryTarget = chooseSecondaryFocusTarget(
                            rankedTargets,
                            attacks.keySet(),
                            focusTarget.playerId()
                    );
                    if (secondaryTarget != null) {
                        int primaryPressure = (int) Math.floor(remainingBudget * 0.62);
                        primaryPressure = Math.max(8, primaryPressure);
                        primaryPressure = Math.min(primaryPressure, remainingBudget - 6);

                        attacks.merge(focusTarget.playerId(), primaryPressure, Integer::sum);
                        remainingBudget -= primaryPressure;

                        attacks.merge(secondaryTarget.playerId(), remainingBudget, Integer::sum);
                        remainingBudget = 0;
                    }
                }

                if (remainingBudget > 0) {
                    int pressureTroops = remainingBudget;
                    if (posture == CombatPosture.SURVIVAL && securedEliminations == 0) {
                        // Keep some resources uncommitted in emergency posture unless necessary.
                        pressureTroops = Math.max(8, remainingBudget / 2);
                        pressureTroops = Math.min(pressureTroops, remainingBudget);
                    }
                    attacks.merge(focusTarget.playerId(), pressureTroops, Integer::sum);
                    remainingBudget -= pressureTroops;
                }
            }
        }

        if (remainingBudget > 0 && !rankedTargets.isEmpty()) {
            attacks.merge(rankedTargets.getFirst().playerId(), remainingBudget, Integer::sum);
        }

        return attacks;
    }

    /**
     * Estimates how many incoming troops are neutralized by mutual attack cancellation.
     */
    private int estimateCancellationMitigation(
            Map<Integer, Integer> attacks,
            Map<Integer, Integer> predictedCounterTroopsByEnemy
    ) {
        int mitigation = 0;
        for (Map.Entry<Integer, Integer> attack : attacks.entrySet()) {
            int predictedCounter = predictedCounterTroopsByEnemy.getOrDefault(attack.getKey(), 0);
            mitigation += Math.min(attack.getValue(), predictedCounter);
        }
        return mitigation;
    }

    /**
     * Decides whether we should commit full troops for a kill on a target.
     */
    private boolean shouldSecureKill(
            EnemyTowerState target,
            int adjustedKillCost,
            GameMemoryService.PlayerProfileSnapshot profile,
            int threat,
            int rankedTargetCount,
            int attackBudget,
            CombatPosture posture,
            FatigueService.FatigueForecast fatigue
    ) {
        if (rankedTargetCount == 1) {
            return true;
        }
        if (fatigue.active() || fatigue.turnsUntilStart() <= 2) {
            return adjustedKillCost <= attackBudget;
        }
        if (target.hp() <= 45 || threat >= 30) {
            return true;
        }
        if (profile != null && (profile.betrayalsAgainstUs() > 0 || profile.likelyAfkCollector())) {
            return true;
        }
        if (rankedTargetCount >= FOUR_PLAYER_ENEMY_COUNT && target.level() >= 3) {
            return adjustedKillCost <= Math.max(26, (attackBudget * 2) / 3);
        }
        if (posture == CombatPosture.CLOSER || posture == CombatPosture.AGGRESSIVE) {
            return adjustedKillCost <= Math.max(22, attackBudget / 2);
        }
        return adjustedKillCost <= Math.max(18, attackBudget / 3);
    }

    /**
     * Picks pressure target after eliminations, preferring negotiation focus when available.
     */
    private EnemyTowerState chooseFocusTarget(
            List<EnemyTowerState> rankedTargets,
            Set<Integer> alreadyAttackedTargets,
            GameMemoryService.NegotiationCommitment commitment
    ) {
        if (commitment != null && commitment.focusTargetId() != null) {
            for (EnemyTowerState target : rankedTargets) {
                if (target.playerId() == commitment.focusTargetId()) {
                    return target;
                }
            }
        }

        for (EnemyTowerState target : rankedTargets) {
            if (!alreadyAttackedTargets.contains(target.playerId())) {
                return target;
            }
        }
        return rankedTargets.isEmpty() ? null : rankedTargets.getFirst();
    }

    /**
     * Picks a secondary pressure target in multi-opponent states to avoid tunnel vision.
     */
    private EnemyTowerState chooseSecondaryFocusTarget(
            List<EnemyTowerState> rankedTargets,
            Set<Integer> alreadyAttackedTargets,
            int primaryTargetId
    ) {
        for (EnemyTowerState target : rankedTargets) {
            if (target.playerId() == primaryTargetId) {
                continue;
            }
            if (!alreadyAttackedTargets.contains(target.playerId())) {
                return target;
            }
        }
        return null;
    }

    /**
     * Extracts useful diplomacy signals from incoming combat payload.
     */
    private DiplomacySignals analyzeDiplomacy(CombatRequest request) {
        int selfId = request.playerTower().playerId();

        Map<Integer, Integer> focusBoostByTarget = new HashMap<>();
        Set<Integer> peaceOfferingEnemies = new HashSet<>();
        Set<Integer> explicitThreatEnemies = new HashSet<>();

        for (PlayerDiplomacy diplomacy : request.diplomacy()) {
            int enemyId = diplomacy.playerId();
            if (diplomacy.action().allyId() == selfId && diplomacy.action().attackTargetId() == null) {
                peaceOfferingEnemies.add(enemyId);
            }
            if (diplomacy.action().attackTargetId() != null && diplomacy.action().attackTargetId() == selfId) {
                explicitThreatEnemies.add(enemyId);
            }
            if (diplomacy.action().allyId() == selfId && diplomacy.action().attackTargetId() != null) {
                focusBoostByTarget.merge(diplomacy.action().attackTargetId(), 10, Integer::sum);
            }
        }

        return new DiplomacySignals(focusBoostByTarget, peaceOfferingEnemies, explicitThreatEnemies);
    }

    /**
     * Final defensive layer: drops any invalid/duplicate/overspending action before returning.
     */
    private List<CombatActionResponse> sanitizeActions(
            List<CombatActionResponse> proposedActions,
            int totalResources,
            int upgradeCost
    ) {
        int remainingResources = totalResources;
        boolean armorUsed = false;
        boolean upgradeUsed = false;
        Set<Integer> attackedTargets = new HashSet<>();
        List<CombatActionResponse> sanitized = new ArrayList<>();

        for (CombatActionResponse action : proposedActions) {
            if ("armor".equals(action.type())) {
                if (armorUsed || action.amount() == null || action.amount() <= 0) {
                    log.warn("Dropping invalid armor action action={}", action);
                    continue;
                }
                if (action.amount() > remainingResources) {
                    log.warn(
                            "Dropping armor action because of insufficient resources amount={} remainingResources={}",
                            action.amount(),
                            remainingResources
                    );
                    continue;
                }
                sanitized.add(CombatActionResponse.armor(action.amount()));
                remainingResources -= action.amount();
                armorUsed = true;
                continue;
            }

            if ("attack".equals(action.type())) {
                if (action.targetId() == null || action.troopCount() == null || action.troopCount() <= 0) {
                    log.warn("Dropping invalid attack action action={}", action);
                    continue;
                }
                if (attackedTargets.contains(action.targetId())) {
                    log.warn("Dropping duplicate attack target action={}", action);
                    continue;
                }
                if (action.troopCount() > remainingResources) {
                    log.warn(
                            "Dropping attack action because of insufficient resources target={} troops={} remainingResources={}",
                            action.targetId(),
                            action.troopCount(),
                            remainingResources
                    );
                    continue;
                }
                sanitized.add(CombatActionResponse.attack(action.targetId(), action.troopCount()));
                remainingResources -= action.troopCount();
                attackedTargets.add(action.targetId());
                continue;
            }

            if ("upgrade".equals(action.type())) {
                if (upgradeUsed || upgradeCost > remainingResources) {
                    log.warn(
                            "Dropping upgrade action because alreadyUsed={} or insufficientResources={} upgradeCost={} remainingResources={}",
                            upgradeUsed,
                            upgradeCost > remainingResources,
                            upgradeCost,
                            remainingResources
                    );
                    continue;
                }
                sanitized.add(CombatActionResponse.upgrade());
                remainingResources -= upgradeCost;
                upgradeUsed = true;
                continue;
            }

            // Any unknown action shape is dropped defensively.
            log.warn("Dropping unknown combat action type action={}", action);
        }

        if (sanitized.size() != proposedActions.size()) {
            log.warn(
                    "Combat action sanitization removed actions proposed={} sanitized={} remainingResources={}",
                    proposedActions.size(),
                    sanitized.size(),
                    remainingResources
            );
        }

        return List.copyOf(sanitized);
    }

    /**
     * Parsed diplomacy signals used to influence target scoring.
     */
    private record DiplomacySignals(
            Map<Integer, Integer> focusBoostByTarget,
            Set<Integer> peaceOfferingEnemies,
            Set<Integer> explicitThreatEnemies
    ) {
    }

    /**
     * Tactical mode for the current turn.
     */
    private enum CombatPosture {
        SURVIVAL,
        BALANCED,
        AGGRESSIVE,
        CLOSER
    }
}
