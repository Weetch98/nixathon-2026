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

import java.util.*;

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

        // Get alive enemies.
        List<EnemyTowerState> aliveEnemies = request.enemyTowers().stream()
                .filter(enemy -> enemy.hp() > 0)
                .toList();

        // Our own tower.
        TowerState playerTower = request.playerTower();

        // How many resources do we currently have.
        int totalResources = playerTower.resources();

        // If there are no enemies, or we have no resources we skip this turn... No other choice.
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

        // We update the player profiles using the combat request.
        gameMemoryService.observeCombatTurn(request);

        // Get player profiles.
        Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy = gameMemoryService
                .getPlayerProfiles(request.gameId(), aliveEnemies);

        // Get threat level for each enemy.
        Map<Integer, Integer> threatByEnemy = threatAssessmentService.assessCombatThreat(request, profilesByEnemy);

        // Get some useful information from negotiating.
        DiplomacySignals diplomacySignals = analyzeDiplomacy(request);

        // Estimate how many troops each enemy will send to us.
        Map<Integer, Integer> predictedCounterTroopsByEnemy = estimateCounterTroopsByEnemy(
                request,
                aliveEnemies,
                threatByEnemy,
                profilesByEnemy,
                diplomacySignals
        );

        // Get our own commitment from negotiation phase.
        GameMemoryService.NegotiationCommitment commitment = gameMemoryService
                .findCommitment(request.gameId(), request.turn())
                .orElse(null);

        // Turn when the duel mode started.
        Integer duelStartTurn = gameMemoryService.findDuelStartTurn(request.gameId()).orElse(null);

        // Forecast the state of the fatigue.
        FatigueService.FatigueForecast fatigue = fatigueService
                .forecast(request.turn(), aliveEnemies.size(), duelStartTurn);

        // Determine the current strategy, be aggressive, or play for survival, or try to close the game...
        CombatPosture posture = determinePosture(request, aliveEnemies.size(), threatByEnemy, fatigue);

        // Estimate defensive needs first, then decide whether economy upgrade is safe.
        int expectedIncomingDamage = estimateIncomingDamage(predictedCounterTroopsByEnemy, profilesByEnemy, posture);

        // How much resource do we want to spend on armor.
        int baselineArmorSpend = baselineArmorSpend(playerTower, expectedIncomingDamage, totalResources, posture);

        int upgradeCost = economyService.upgradeCost(playerTower.level());

        // Check if we should upgrade in the first place.
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

        // Allocate resources to attack planning based on posture/fatigue urgency.
        int resourcesAfterUpgrade = shouldUpgrade ? totalResources - upgradeCost : totalResources;

        // Calculate a targeting score for each enemy, and sort the list from high to low.
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

        // Our priority target is the first in the list.
        EnemyTowerState primaryTarget = rankedTargets.isEmpty() ? null : rankedTargets.getFirst();

        // Plan how much we want to spend on armor now that we know our primary target.
        int armorSpend = planArmorSpend(
                playerTower,
                primaryTarget,
                baselineArmorSpend,
                resourcesAfterUpgrade,
                predictedCounterTroopsByEnemy,
                posture,
                fatigue
        );

        // How much resource we are left with after upgrading (maybe) and buying armor (maybe).
        int resourcesAfterDefense = Math.max(resourcesAfterUpgrade - armorSpend, 0);

        // Build candidate actions and sanitize so API contract can never be violated.
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

        // We calculate how much armor we need if we account for troop cancellation.
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

        // If it seems like we will need to spend less on armor we send the freed up troops to attack or main focus.
        if (revisedArmorSpend < armorSpend) {
            int freedTroops = armorSpend - revisedArmorSpend;
            armorSpend = revisedArmorSpend;
            EnemyTowerState reinforcementTarget = chooseFocusTarget(rankedTargets, Set.of(), commitment);
            if (reinforcementTarget != null && freedTroops > 0) {
                attacks.merge(reinforcementTarget.playerId(), freedTroops, Integer::sum);
            }
        }

        // Create the proposed actions.
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

        // Get how many troops he sent to us last time.
        Map<Integer, Integer> lastTurnTroopsToUsByEnemy = new HashMap<>();
        for (PlayerAttack attack : request.previousAttacks()) {
            if (attack.action().targetId() == selfId) {
                lastTurnTroopsToUsByEnemy.merge(attack.playerId(), attack.action().troopCount(), Integer::sum);
            }
        }

        // This tries to estimate how many troops the enemy will send to us.
        // Mostly it just use some heuristics and historical average.
        Map<Integer, Integer> counterTroopsByEnemy = new HashMap<>();
        for (EnemyTowerState enemy : aliveEnemies) {
            int enemyId = enemy.playerId();
            GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(enemyId);

            // Get how many troops he sent last turn.
            int lastTurnTroops = lastTurnTroopsToUsByEnemy.getOrDefault(enemyId, 0);
            int historicalAverage = 0;

            // Get historical average.
            if (profile != null && profile.attacksAgainstUsCount() > 0) {
                historicalAverage = (int) Math.round((double) profile.attacksAgainstUsTroops() / profile.attacksAgainstUsCount());
            }
            // Come up with an estimation based on threat score.
            // I'm expecting this to be pretty inaccurate, but who knows...
            int threatProjection = (int) Math.round(threatByEnemy.getOrDefault(enemyId, 0) * 0.35);

            // Take the worst case scenario. Whichever method produce the highest method.
            int predictedTroops = Math.max(lastTurnTroops, Math.max(historicalAverage, threatProjection));

            // If he attacked last two turns we increase troop number with a random amount.
            if (profile != null && profile.consecutiveAttackTurns() >= 2) {
                predictedTroops += 4;
            }

            // If he is an AFK collector we also increate troop number.
            if (profile != null && profile.likelyAfkCollector()) {
                predictedTroops += 5 + (enemy.level() * 2);
            }
            // If he is 2 levels higher than us, we also increase it.
            if (profile != null
                    && profile.consecutiveNoAttackTurns() >= 2
                    && enemy.level() >= (selfLevel + 2)) {
                predictedTroops += 6;
            }

            // If he told us he will attack us, we believe it and also increase predicted troop count.
            if (diplomacySignals.explicitThreatEnemies().contains(enemyId)) {
                predictedTroops += 6;
            }

            // If he offered peace, and he is reliable (no betrayals and nasty stuff), we kinda believe him.
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
        // Add all predicted troops for this turn.
        int expectedIncoming = predictedCounterTroopsByEnemy.values().stream()
                .mapToInt(Integer::intValue)
                .sum();

        // Add some more damage if the other players betrayed us or attack a lot.
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

        // The cap on how many resource we want to spend max on defense.
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
        // If it is late game we might want to force upgrade so we match level with our 1v1 enemy.
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
        // When we are behind the level curve but still safe, prioritize catching up economically
        // This basically means we are behind level, and we don't have to spend too much for armor, we can upgrade.
        // This function only applies for early game, 3 enemies living, no fatigue...
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

        // Check if we will still have enough resource to buy armor after upgrading.
        // Armor spend if of course completely calculated using heuristics.
        int resourcesAfterUpgrade = totalResources - upgradeCost;
        if (resourcesAfterUpgrade < baselineArmorSpend + 25) {
            return false;
        }

        int turnsRemaining = Math.max(0, MAX_TURNS - request.turn());
        int projectedUpgradeReturn = economyService.estimatedUpgradeReturn(request.playerTower().level(), turnsRemaining);

        // Check if upgrading is still a good investment.
        return projectedUpgradeReturn >= (int) Math.round(upgradeCost * 0.60);
    }

    /**
     * 4-player anti-snowball upgrade policy.
     * <p>
     * When we are behind the level curve but still safe, prioritize catching up economically
     * so one upgrader cannot dominate the mid-game with one large troop spike.
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

        // The enemy which we are playing 1v1 currently.
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
        // resourceAfterUpgrade can be 0 if we don't want to upgrade.
        int armorSpend = Math.min(baselineArmorSpend, resourcesAfterUpgrade);

        // If we have no target we can spend every resource to armor.
        if (primaryTarget == null) {
            return armorSpend;
        }

        // Let's check how much it costs to kill the primary target.
        int killCost = primaryTarget.effectiveDurability() + predictedCounterTroopsByEnemy
                .getOrDefault(primaryTarget.playerId(), 0);

        // If we have immediate kill window, and we are in aggro or closer, prioritize the kill.
        boolean immediateKillWindow = killCost <= resourcesAfterUpgrade;
        if (immediateKillWindow
                && (posture == CombatPosture.CLOSER
                || posture == CombatPosture.AGGRESSIVE
                || primaryTarget.hp() <= 40)) {
            int maxArmorWithKill = Math.max(0, resourcesAfterUpgrade - killCost);
            armorSpend = Math.min(armorSpend, maxArmorWithKill);
        }

        // Spend less on armor during fatigue.
        if (fatigue.active() && fatigue.currentDamage() >= 40) {
            armorSpend = (int) Math.floor(armorSpend * 0.70);
        }

        // While we have very low HP, and we are in survival mode spend a lot on armor.
        if (playerTower.hp() <= 20 && posture == CombatPosture.SURVIVAL) {
            armorSpend = Math.max(armorSpend, Math.min(resourcesAfterUpgrade, baselineArmorSpend + 8));
        }

        // Move armor spend in the closed interval [0, resourcesAfterUpgrade]
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
        // Get max enemy level.
        int maxEnemyLevel = enemies.stream()
                .mapToInt(EnemyTowerState::level)
                .max()
                .orElse(playerLevel);

        // We calculate a priority targeting list based on historical data, our negotiation commitment, and other stuff.
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
        // Get player profile.
        GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(enemy.playerId());

        int durability = enemy.effectiveDurability(); // effective durability is basically armor + hp
        int threat = threatByEnemy.getOrDefault(enemy.playerId(), 0); // threat score is based on aggression towards us

        // This is some random heuristics.
        double score = (threat * 2.0) + (enemy.level() * 7.0) + (170.0 - durability);

        int levelLeadOverUs = enemy.level() - playerLevel;

        // If other player wants us to attack this guy, we kinda do it and raise target score.
        score += diplomacySignals.focusBoostByTarget().getOrDefault(enemy.playerId(), 0) * 1.2;

        // If player offered peace we lower the score just a little-bit.
        if (diplomacySignals.peaceOfferingEnemies().contains(enemy.playerId())) {
            score -= 7;
        }

        // On explicit threat we increate target score.
        if (diplomacySignals.explicitThreatEnemies().contains(enemy.playerId())) {
            score += 14;
        }

        // We increate or decrease targeting score based on some historical data.
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
            // If we said we will attack this guy during negotiation, we are trustworthy and raise targeting score.
            if (commitment.focusTargetId() != null && Objects.equals(enemy.playerId(), commitment.focusTargetId())) {
                score += 16;
            }

            // We said we will be allies, so we lower the score, but only if he is also a good guy (and we are in balanced mode)
            if (commitment.explicitAlliedPlayerIds().contains(enemy.playerId())) {
                if (profile != null && profile.trustScore() >= 22 && profile.betrayalsAgainstUs() == 0 && posture == CombatPosture.BALANCED) {
                    score -= 8;
                } else {
                    score += 6;
                }
            }
        }

        // If we can kill him easily because we have budget, increase attack score.
        if (durability <= availableAttackBudget) {
            score += 10;
        }

        // End-game: focus players with high levels.
        if (fatigue.active() || fatigue.turnsUntilStart() <= 2) {
            score += (enemy.level() * 2.0);
            if (durability <= availableAttackBudget) {
                score += 10;
            }
        }

        // In closer mode we are more likely to attack.
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

        // If we have no budget or enemy list is empty we don't attack.
        if (attackBudget <= 0 || rankedTargets.isEmpty()) {
            return attacks;
        }

        int remainingBudget = attackBudget;
        int securedEliminations = 0;
        int eliminationLimit = posture == CombatPosture.SURVIVAL ? 1 : 2;

        // First we are focusing on eliminations and kill securing.
        for (EnemyTowerState target : rankedTargets) {
            if (remainingBudget <= 0) {
                break;
            }

            // KillCost is basically HP + armor + estimated troops towards us from the enemy.
            int killCost = target.effectiveDurability() + predictedCounterTroopsByEnemy
                    .getOrDefault(target.playerId(), 0);

            // Get player profile.
            GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(target.playerId());

            // If KillCost is greater than our budget leave this guy alone. It means we can't secure the kill anyways.
            if (killCost > remainingBudget) {
                continue;
            }

            // Check if we should secure the kill based on different heuristics...
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
                // Check if we can split pressure among players.
                boolean canSplitPressure = rankedTargets.size() >= FOUR_PLAYER_ENEMY_COUNT
                        && posture != CombatPosture.SURVIVAL
                        && remainingBudget >= 16;

                // If we can split pressure then select a secondary focus target as well.
                // This sometimes return null if we already have an attack against everyone.
                if (canSplitPressure) {
                    EnemyTowerState secondaryTarget = chooseSecondaryFocusTarget(
                            rankedTargets,
                            attacks.keySet(),
                            focusTarget.playerId()
                    );

                    // Split the attack between first and secondary target
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
            int adjustedKillCost, // HP + armor + estimated troop count towards us
            GameMemoryService.PlayerProfileSnapshot profile,
            int threat,
            int rankedTargetCount,
            int attackBudget,
            CombatPosture posture,
            FatigueService.FatigueForecast fatigue
    ) {
        // He is the only enemy, of course kill him!
        if (rankedTargetCount == 1) {
            return true;
        }

        // Its late-game, there is fatigue, kill him if we are sure we can.
        if (fatigue.active() || fatigue.turnsUntilStart() <= 2) {
            return adjustedKillCost <= attackBudget;
        }

        // Low-HP enemy, or very dangerous.
        if (target.hp() <= 45 || threat >= 30) {
            return true;
        }

        // He betrayed us, or he is probably afk collecting for late-game.
        if (profile != null && (profile.betrayalsAgainstUs() > 0 || profile.likelyAfkCollector())) {
            return true;
        }

        // Lvl3+ enemy which could be killed without really investing too much resource. Kill him.
        if (rankedTargetCount >= FOUR_PLAYER_ENEMY_COUNT && target.level() >= 3) {
            return adjustedKillCost <= Math.max(26, (attackBudget * 2) / 3);
        }

        // If we are aggro or closer and kill doesn't take too much resource.
        if (posture == CombatPosture.CLOSER || posture == CombatPosture.AGGRESSIVE) {
            return adjustedKillCost <= Math.max(22, attackBudget / 2);
        }

        // Easy to kill, or not...
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
        // If we said we will attack someone, and we have spare resource after eliminations, attack him!
        if (commitment != null && commitment.focusTargetId() != null) {
            for (EnemyTowerState target : rankedTargets) {
                if (Objects.equals(target.playerId(), commitment.focusTargetId())) {
                    return target;
                }
            }
        }

        // Select target based on the targeting score. rankedTargets is sorted from high to low.
        for (EnemyTowerState target : rankedTargets) {

            // If we have an attack against him already skip him.
            if (!alreadyAttackedTargets.contains(target.playerId())) {
                return target;
            }
        }


        // If we have an attack against everyone already: still return the highest threat as a fallback.
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
            // He wants us to be his ally.
            if (diplomacy.action().allyId() == selfId && diplomacy.action().attackTargetId() == null) {
                peaceOfferingEnemies.add(enemyId);
            }
            // He wants to attack us.
            if (diplomacy.action().attackTargetId() != null && diplomacy.action().attackTargetId() == selfId) {
                explicitThreatEnemies.add(enemyId);
            }
            // He wants us to attack someone else. We raise the focus a little bit for the target.
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
            // If they say to attack someone, we kinda listen and increment focus a little bit.
            Map<Integer, Integer> focusBoostByTarget,

            // Players who wants to keep peace and ally with us.
            Set<Integer> peaceOfferingEnemies,

            // Players who wants to attack us.
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
