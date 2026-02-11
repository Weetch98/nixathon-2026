package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.EnemyTowerState;
import me.beratta.nixathon.game.dto.NegotiationMessage;
import me.beratta.nixathon.game.dto.NegotiationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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

        Map<Integer, Integer> hostilityByEnemy = threatAssessmentService.assessNegotiationThreat(request);
        EnemyTowerState primaryTarget = pickPrimaryTarget(enemies, hostilityByEnemy);
        List<EnemyTowerState> allianceCandidates = rankAllianceCandidates(enemies, hostilityByEnemy, primaryTarget.playerId());

        int messageLimit = chooseMessageLimit(hostilityByEnemy, enemies.size());
        List<NegotiationMessage> messages = new ArrayList<>();

        for (EnemyTowerState allyCandidate : allianceCandidates) {
            if (messages.size() >= messageLimit) {
                break;
            }
            messages.add(new NegotiationMessage(allyCandidate.playerId(), primaryTarget.playerId()));
        }

        gameMemoryService.storeNegotiationPlan(request.gameId(), request.turn(), messages);
        log.info(
                "Negotiation planned game={} turn={} primaryTarget={} allyMessages={} hostilityMap={}",
                request.gameId(),
                request.turn(),
                primaryTarget.playerId(),
                messages.size(),
                hostilityByEnemy
        );
        return List.copyOf(messages);
    }

    private EnemyTowerState pickPrimaryTarget(List<EnemyTowerState> enemies, Map<Integer, Integer> hostilityByEnemy) {
        return enemies.stream()
                .max(Comparator.comparingDouble(enemy -> targetPriority(enemy, hostilityByEnemy)))
                .orElseThrow();
    }

    private List<EnemyTowerState> rankAllianceCandidates(
            List<EnemyTowerState> enemies,
            Map<Integer, Integer> hostilityByEnemy,
            int excludedEnemyId
    ) {
        return enemies.stream()
                .filter(enemy -> enemy.playerId() != excludedEnemyId)
                .sorted(Comparator.comparingDouble((EnemyTowerState enemy) -> allianceScore(enemy, hostilityByEnemy))
                        .reversed())
                .toList();
    }

    private int chooseMessageLimit(Map<Integer, Integer> hostilityByEnemy, int enemyCount) {
        int maxThreat = hostilityByEnemy.values().stream()
                .max(Integer::compareTo)
                .orElse(0);

        int desiredMessageCount = maxThreat >= 40 ? 3 : 2;
        return Math.min(desiredMessageCount, Math.max(enemyCount - 1, 0));
    }

    private double targetPriority(EnemyTowerState enemy, Map<Integer, Integer> hostilityByEnemy) {
        int hostility = hostilityByEnemy.getOrDefault(enemy.playerId(), 0);
        int durability = enemy.effectiveDurability();
        return (hostility * 2.4) + (enemy.level() * 6.0) + (140.0 - durability);
    }

    private double allianceScore(EnemyTowerState enemy, Map<Integer, Integer> hostilityByEnemy) {
        int hostility = hostilityByEnemy.getOrDefault(enemy.playerId(), 0);
        return (enemy.level() * 5.0) + enemy.hp() + (enemy.armor() * 0.4) - (hostility * 1.8);
    }
}
