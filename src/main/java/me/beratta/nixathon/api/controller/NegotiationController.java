package me.beratta.nixathon.api.controller;

import jakarta.validation.Valid;
import me.beratta.nixathon.game.dto.NegotiationMessage;
import me.beratta.nixathon.game.dto.NegotiationRequest;
import me.beratta.nixathon.game.service.NegotiationStrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
public class NegotiationController {

    private static final Logger log = LoggerFactory.getLogger(NegotiationController.class);

    private final NegotiationStrategyService negotiationStrategyService;

    public NegotiationController(NegotiationStrategyService negotiationStrategyService) {
        this.negotiationStrategyService = negotiationStrategyService;
    }

    @PostMapping("/negotiate")
    public List<NegotiationMessage> negotiate(@Valid @RequestBody NegotiationRequest request) {
        long startNanos = System.nanoTime();
        log.info(
                "Negotiation request received game={} turn={} player={} hp={} armor={} resources={} level={} enemyCount={} observedCombatActions={}",
                request.gameId(),
                request.turn(),
                request.playerTower().playerId(),
                request.playerTower().hp(),
                request.playerTower().armor(),
                request.playerTower().resources(),
                request.playerTower().level(),
                request.enemyTowers().size(),
                request.combatActions().size()
        );

        List<NegotiationMessage> response = negotiationStrategyService.planNegotiation(request);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        log.info(
                "Negotiation response prepared game={} turn={} messagesSent={} durationMs={}",
                request.gameId(),
                request.turn(),
                response.size(),
                durationMs
        );
        log.debug("Negotiation response details game={} turn={} messages={}", request.gameId(), request.turn(), response);

        return response;
    }
}
