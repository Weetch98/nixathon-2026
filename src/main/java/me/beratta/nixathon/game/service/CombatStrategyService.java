package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.CombatActionResponse;
import me.beratta.nixathon.game.dto.CombatRequest;
import me.beratta.nixathon.game.dto.EnemyTowerState;
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

@Service
public class CombatStrategyService {

    private static final Logger log = LoggerFactory.getLogger(CombatStrategyService.class);

    private static final int MAX_TURNS = 25;

    private final ThreatAssessmentService threatAssessmentService;
    private final EconomyService economyService;
    private final GameMemoryService gameMemoryService;

    public CombatStrategyService(
            ThreatAssessmentService threatAssessmentService,
            EconomyService economyService,
            GameMemoryService gameMemoryService
    ) {
        this.threatAssessmentService = threatAssessmentService;
        this.economyService = economyService;
        this.gameMemoryService = gameMemoryService;
    }

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

        Map<Integer, Integer> threatByEnemy = threatAssessmentService.assessCombatThreat(request);
        DiplomacySignals diplomacySignals = analyzeDiplomacy(request);
        GameMemoryService.NegotiationCommitment commitment = gameMemoryService
                .findCommitment(request.gameId(), request.turn())
                .orElse(null);

        int expectedIncomingDamage = estimateIncomingDamage(request, aliveEnemies, threatByEnemy);
        int baselineArmorSpend = baselineArmorSpend(playerTower, expectedIncomingDamage, totalResources);
        int upgradeCost = economyService.upgradeCost(playerTower.level());

        boolean shouldUpgrade = shouldUpgrade(
                request,
                aliveEnemies.size(),
                totalResources,
                baselineArmorSpend,
                expectedIncomingDamage,
                upgradeCost
        );

        int resourcesAfterUpgrade = shouldUpgrade ? totalResources - upgradeCost : totalResources;
        List<EnemyTowerState> rankedTargets = rankTargets(
                aliveEnemies,
                threatByEnemy,
                diplomacySignals,
                commitment,
                resourcesAfterUpgrade
        );
        EnemyTowerState primaryTarget = rankedTargets.isEmpty() ? null : rankedTargets.getFirst();

        int armorSpend = planArmorSpend(
                playerTower,
                aliveEnemies.size(),
                primaryTarget,
                baselineArmorSpend,
                resourcesAfterUpgrade
        );
        int resourcesAfterDefense = Math.max(resourcesAfterUpgrade - armorSpend, 0);

        Map<Integer, Integer> attacks = planAttacks(
                rankedTargets,
                threatByEnemy,
                commitment,
                resourcesAfterDefense
        );

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
                "Combat planned game={} turn={} expectedIncomingDamage={} armorSpend={} shouldUpgrade={} proposedActions={} finalActions={} durationMs={}",
                request.gameId(),
                request.turn(),
                expectedIncomingDamage,
                armorSpend,
                shouldUpgrade,
                proposedActions.size(),
                sanitizedActions.size(),
                durationMs
        );
        log.debug(
                "Combat planning details game={} turn={} threatByEnemy={} commitment={} rankedTargets={} attacks={} actions={}",
                request.gameId(),
                request.turn(),
                threatByEnemy,
                commitment,
                rankedTargets.stream().map(EnemyTowerState::playerId).toList(),
                attacks,
                sanitizedActions
        );

        return sanitizedActions;
    }

    private int estimateIncomingDamage(
            CombatRequest request,
            List<EnemyTowerState> aliveEnemies,
            Map<Integer, Integer> threatByEnemy
    ) {
        int aggregateThreat = threatByEnemy.values().stream().mapToInt(Integer::intValue).sum();
        double threatMultiplier = aliveEnemies.size() <= 2 ? 0.62 : 0.50;
        int expectedIncoming = (int) Math.round(aggregateThreat * threatMultiplier);

        if (request.turn() >= 22) {
            expectedIncoming += 8;
        }
        if (aliveEnemies.size() <= 2 && request.turn() >= 6) {
            expectedIncoming += 6;
        }
        return Math.max(expectedIncoming, 0);
    }

    private int baselineArmorSpend(TowerState playerTower, int expectedIncomingDamage, int resourceCap) {
        int desiredArmor = expectedIncomingDamage + safetyBuffer(playerTower.hp());
        int additionalArmorNeeded = Math.max(desiredArmor - playerTower.armor(), 0);
        return Math.min(additionalArmorNeeded, resourceCap);
    }

    private int safetyBuffer(int hp) {
        if (hp <= 25) {
            return 16;
        }
        if (hp <= 50) {
            return 10;
        }
        return 6;
    }

    private boolean shouldUpgrade(
            CombatRequest request,
            int aliveEnemyCount,
            int totalResources,
            int baselineArmorSpend,
            int expectedIncomingDamage,
            int upgradeCost
    ) {
        if (upgradeCost > totalResources) {
            return false;
        }

        int resourcesAfterUpgrade = totalResources - upgradeCost;
        int defenseFloorAfterUpgrade = Math.max(0, baselineArmorSpend - 4);
        if (resourcesAfterUpgrade < defenseFloorAfterUpgrade) {
            return false;
        }

        boolean duelPressureWindow = aliveEnemyCount <= 2 && request.turn() >= 8;
        if (duelPressureWindow) {
            return false;
        }

        int turnsRemaining = Math.max(0, MAX_TURNS - request.turn());
        int projectedUpgradeReturn = economyService.estimatedUpgradeReturn(request.playerTower().level(), turnsRemaining);

        boolean earlyEconomyWindow = request.turn() <= 10
                && request.playerTower().level() <= 3
                && aliveEnemyCount >= 3
                && expectedIncomingDamage < request.playerTower().hp();

        boolean resourceOverflowWindow = totalResources >= upgradeCost + baselineArmorSpend + 55;
        boolean returnWindow = projectedUpgradeReturn >= (upgradeCost / 2);

        return (earlyEconomyWindow && returnWindow) || resourceOverflowWindow;
    }

    private int planArmorSpend(
            TowerState playerTower,
            int aliveEnemyCount,
            EnemyTowerState primaryTarget,
            int baselineArmorSpend,
            int resourcesAfterUpgrade
    ) {
        int armorSpend = Math.min(baselineArmorSpend, resourcesAfterUpgrade);

        if (primaryTarget == null) {
            return armorSpend;
        }

        int killCost = primaryTarget.effectiveDurability();
        boolean hasKillWindow = killCost <= resourcesAfterUpgrade
                && (aliveEnemyCount == 1 || primaryTarget.hp() <= 30);

        if (!hasKillWindow || armorSpend <= 0) {
            return armorSpend;
        }

        int maxArmorWhileStillKilling = Math.max(0, resourcesAfterUpgrade - killCost);
        return Math.min(armorSpend, maxArmorWhileStillKilling);
    }

    private List<EnemyTowerState> rankTargets(
            List<EnemyTowerState> enemies,
            Map<Integer, Integer> threatByEnemy,
            DiplomacySignals diplomacySignals,
            GameMemoryService.NegotiationCommitment commitment,
            int availableAttackBudget
    ) {
        return enemies.stream()
                .sorted(Comparator.comparingDouble(
                        (EnemyTowerState enemy) -> targetPriority(
                                enemy,
                                threatByEnemy,
                                diplomacySignals,
                                commitment,
                                availableAttackBudget
                        )
                ).reversed())
                .toList();
    }

    private double targetPriority(
            EnemyTowerState enemy,
            Map<Integer, Integer> threatByEnemy,
            DiplomacySignals diplomacySignals,
            GameMemoryService.NegotiationCommitment commitment,
            int availableAttackBudget
    ) {
        int durability = enemy.effectiveDurability();
        int threat = threatByEnemy.getOrDefault(enemy.playerId(), 0);
        double score = (threat * 2.2) + (enemy.level() * 6.0) + (145.0 - durability);

        score += diplomacySignals.focusBoostByTarget().getOrDefault(enemy.playerId(), 0) * 1.5;
        if (diplomacySignals.peaceOfferingEnemies().contains(enemy.playerId())) {
            score -= 18;
        }
        if (diplomacySignals.explicitThreatEnemies().contains(enemy.playerId())) {
            score += 16;
        }

        if (commitment != null) {
            if (commitment.focusTargetId() != null && enemy.playerId() == commitment.focusTargetId()) {
                score += 20;
            }
            if (commitment.alliedPlayerIds().contains(enemy.playerId())) {
                score -= 30;
            }
        }

        if (durability <= availableAttackBudget) {
            score += 14;
        }

        return score;
    }

    private Map<Integer, Integer> planAttacks(
            List<EnemyTowerState> rankedTargets,
            Map<Integer, Integer> threatByEnemy,
            GameMemoryService.NegotiationCommitment commitment,
            int attackBudget
    ) {
        LinkedHashMap<Integer, Integer> attacks = new LinkedHashMap<>();
        if (attackBudget <= 0 || rankedTargets.isEmpty()) {
            return attacks;
        }

        Set<Integer> negotiatedAllies = commitment == null ? Set.of() : commitment.alliedPlayerIds();
        int remainingBudget = attackBudget;

        for (EnemyTowerState target : rankedTargets) {
            if (remainingBudget <= 0) {
                break;
            }

            int threat = threatByEnemy.getOrDefault(target.playerId(), 0);
            boolean protectedByNegotiation = negotiatedAllies.contains(target.playerId()) && threat < 35;
            if (protectedByNegotiation) {
                continue;
            }

            int killCost = target.effectiveDurability();
            if (killCost > remainingBudget) {
                continue;
            }
            if (!shouldSecureKill(target, threat, rankedTargets.size(), attackBudget)) {
                continue;
            }

            attacks.put(target.playerId(), killCost);
            remainingBudget -= killCost;
        }

        if (remainingBudget > 0) {
            EnemyTowerState focusTarget = chooseFocusTarget(rankedTargets, negotiatedAllies);
            if (focusTarget != null) {
                attacks.merge(focusTarget.playerId(), remainingBudget, Integer::sum);
            }
        }

        return attacks;
    }

    private boolean shouldSecureKill(
            EnemyTowerState target,
            int threat,
            int rankedTargetCount,
            int initialAttackBudget
    ) {
        if (rankedTargetCount == 1) {
            return true;
        }
        if (target.hp() <= 35) {
            return true;
        }
        if (threat >= 28) {
            return true;
        }
        return target.effectiveDurability() <= Math.max(20, initialAttackBudget / 2);
    }

    private EnemyTowerState chooseFocusTarget(List<EnemyTowerState> rankedTargets, Set<Integer> negotiatedAllies) {
        for (EnemyTowerState target : rankedTargets) {
            if (!negotiatedAllies.contains(target.playerId())) {
                return target;
            }
        }
        return rankedTargets.isEmpty() ? null : rankedTargets.getFirst();
    }

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

    private record DiplomacySignals(
            Map<Integer, Integer> focusBoostByTarget,
            Set<Integer> peaceOfferingEnemies,
            Set<Integer> explicitThreatEnemies
    ) {
    }
}
