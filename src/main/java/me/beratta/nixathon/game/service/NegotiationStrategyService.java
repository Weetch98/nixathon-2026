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

@Service
public class NegotiationStrategyService {

    private static final Logger log = LoggerFactory.getLogger(NegotiationStrategyService.class);

    private final ThreatAssessmentService threatAssessmentService;
    private final GameMemoryService gameMemoryService;

    public NegotiationStrategyService(
            ThreatAssessmentService threatAssessmentService,
            GameMemoryService gameMemoryService
    ) {
        this.threatAssessmentService = threatAssessmentService;
        this.gameMemoryService = gameMemoryService;
    }

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

        List<EnemyTowerState> enemies = request.enemyTowers().stream()
                .filter(enemy -> enemy.hp() > 0)
                .toList();
        gameMemoryService.observeNegotiationTurn(request.gameId(), request.turn(), enemies);

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

        Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy = gameMemoryService
                .getPlayerProfiles(request.gameId(), enemies);
        Map<Integer, Integer> hostilityByEnemy = threatAssessmentService.assessNegotiationThreat(request, profilesByEnemy);
        EnemyTowerState primaryTarget = pickPrimaryTarget(enemies, hostilityByEnemy, profilesByEnemy, request.turn());
        List<EnemyTowerState> allianceCandidates = rankAllianceCandidates(
                enemies,
                hostilityByEnemy,
                profilesByEnemy,
                primaryTarget.playerId()
        );

        int messageLimit = chooseMessageLimit(hostilityByEnemy, enemies.size(), request.turn());
        List<NegotiationMessage> messages = new ArrayList<>();
        Set<Integer> usedRecipients = new HashSet<>();

        EnemyTowerState explicitAllianceCandidate = chooseExplicitAllianceCandidate(
                allianceCandidates,
                hostilityByEnemy,
                profilesByEnemy
        );
        if (messageLimit > 0 && explicitAllianceCandidate != null) {
            messages.add(new NegotiationMessage(explicitAllianceCandidate.playerId(), null));
            usedRecipients.add(explicitAllianceCandidate.playerId());
        }

        for (EnemyTowerState allyCandidate : allianceCandidates) {
            if (messages.size() >= messageLimit) {
                break;
            }
            if (usedRecipients.contains(allyCandidate.playerId())) {
                continue;
            }
            if (isUnreliableRecipient(allyCandidate, hostilityByEnemy, profilesByEnemy)) {
                continue;
            }
            messages.add(new NegotiationMessage(allyCandidate.playerId(), primaryTarget.playerId()));
            usedRecipients.add(allyCandidate.playerId());
        }

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
                hostilityByEnemy,
                messages.stream().map(NegotiationMessage::allyId).toList()
        );
        return List.copyOf(messages);
    }

    private EnemyTowerState pickPrimaryTarget(
            List<EnemyTowerState> enemies,
            Map<Integer, Integer> hostilityByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            int turn
    ) {
        return enemies.stream()
                .max(Comparator.comparingDouble(enemy -> targetPriority(enemy, hostilityByEnemy, profilesByEnemy, turn)))
                .orElseThrow();
    }

    private List<EnemyTowerState> rankAllianceCandidates(
            List<EnemyTowerState> enemies,
            Map<Integer, Integer> hostilityByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            int excludedEnemyId
    ) {
        return enemies.stream()
                .filter(enemy -> enemy.playerId() != excludedEnemyId)
                .sorted(Comparator.comparingDouble(
                                (EnemyTowerState enemy) -> allianceScore(enemy, hostilityByEnemy, profilesByEnemy)
                        )
                        .reversed())
                .toList();
    }

    private int chooseMessageLimit(Map<Integer, Integer> hostilityByEnemy, int enemyCount, int turn) {
        if (enemyCount <= 2) {
            return 1;
        }

        int maxThreat = hostilityByEnemy.values().stream()
                .max(Integer::compareTo)
                .orElse(0);

        int desiredMessageCount = 2;
        if (turn <= 6 && enemyCount >= 4) {
            desiredMessageCount = 3;
        }
        if (maxThreat >= 55 || turn >= 16) {
            desiredMessageCount = Math.min(desiredMessageCount, 2);
        }
        return Math.min(desiredMessageCount, Math.max(enemyCount - 1, 0));
    }

    private EnemyTowerState chooseExplicitAllianceCandidate(
            List<EnemyTowerState> allianceCandidates,
            Map<Integer, Integer> hostilityByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy
    ) {
        for (EnemyTowerState candidate : allianceCandidates) {
            GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(candidate.playerId());
            int hostility = hostilityByEnemy.getOrDefault(candidate.playerId(), 0);
            if (profile == null) {
                continue;
            }
            if (profile.trustScore() >= 10
                    && profile.betrayalsAgainstUs() == 0
                    && hostility < 45
                    && !profile.likelyAfkCollector()) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isUnreliableRecipient(
            EnemyTowerState candidate,
            Map<Integer, Integer> hostilityByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy
    ) {
        GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(candidate.playerId());
        if (profile == null) {
            return false;
        }

        int hostility = hostilityByEnemy.getOrDefault(candidate.playerId(), 0);
        if (profile.betrayalsAgainstUs() > 0 && hostility > 20) {
            return true;
        }
        if (profile.trustScore() < -8) {
            return true;
        }
        return profile.threatDeclarationsToUs() > 0 && hostility > 35;
    }

    private double targetPriority(
            EnemyTowerState enemy,
            Map<Integer, Integer> hostilityByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy,
            int turn
    ) {
        GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(enemy.playerId());
        int hostility = hostilityByEnemy.getOrDefault(enemy.playerId(), 0);
        int durability = enemy.effectiveDurability();

        double score = (hostility * 2.1) + (enemy.level() * 8.0) + (170.0 - durability);
        if (profile != null) {
            score += profile.hostilityScore() * 0.6;
            score += profile.afkHoardingRisk() * 1.2;
            score += profile.betrayalsAgainstUs() * 12.0;
        }
        if (turn >= 18) {
            score += enemy.level() * 2.5;
        }
        return score;
    }

    private double allianceScore(
            EnemyTowerState enemy,
            Map<Integer, Integer> hostilityByEnemy,
            Map<Integer, GameMemoryService.PlayerProfileSnapshot> profilesByEnemy
    ) {
        GameMemoryService.PlayerProfileSnapshot profile = profilesByEnemy.get(enemy.playerId());
        int hostility = hostilityByEnemy.getOrDefault(enemy.playerId(), 0);
        if (profile == null) {
            return (enemy.level() * 2.0) - hostility;
        }

        double trust = profile.trustScore();
        double betrayalPenalty = profile.betrayalsAgainstUs() * 15.0;
        return (trust * 1.4)
                - (hostility * 1.2)
                - profile.afkHoardingRisk()
                - betrayalPenalty
                + (enemy.level() * 2.0);
    }
}
