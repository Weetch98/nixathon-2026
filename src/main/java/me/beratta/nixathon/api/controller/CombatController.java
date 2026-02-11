package me.beratta.nixathon.api.controller;

import jakarta.validation.Valid;
import me.beratta.nixathon.game.dto.CombatActionResponse;
import me.beratta.nixathon.game.dto.CombatRequest;
import me.beratta.nixathon.game.service.CombatStrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
public class CombatController {

    private static final Logger log = LoggerFactory.getLogger(CombatController.class);

    private final CombatStrategyService combatStrategyService;

    public CombatController(CombatStrategyService combatStrategyService) {
        this.combatStrategyService = combatStrategyService;
    }

    @PostMapping("/combat")
    public List<CombatActionResponse> combat(@Valid @RequestBody CombatRequest request) {
        long startNanos = System.nanoTime();
        log.info(
                "Combat request received game={} turn={} player={} hp={} armor={} resources={} level={} enemyCount={} diplomacyCount={} previousAttackCount={}",
                request.gameId(),
                request.turn(),
                request.playerTower().playerId(),
                request.playerTower().hp(),
                request.playerTower().armor(),
                request.playerTower().resources(),
                request.playerTower().level(),
                request.enemyTowers().size(),
                request.diplomacy().size(),
                request.previousAttacks().size()
        );

        List<CombatActionResponse> response = combatStrategyService.planCombat(request);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        log.info(
                "Combat response prepared game={} turn={} actions={} durationMs={}",
                request.gameId(),
                request.turn(),
                response.size(),
                durationMs
        );
        log.debug("Combat response details game={} turn={} actions={}", request.gameId(), request.turn(), response);

        return response;
    }
}
