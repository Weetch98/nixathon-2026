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

@RestController
public class NegotiationController {

    private static final Logger log = LoggerFactory.getLogger(NegotiationController.class);

    private final NegotiationStrategyService negotiationStrategyService;

    public NegotiationController(NegotiationStrategyService negotiationStrategyService) {
        this.negotiationStrategyService = negotiationStrategyService;
    }

    @PostMapping("/negotiate")
    public List<NegotiationMessage> negotiate(@Valid @RequestBody NegotiationRequest request) {
        log.debug("Negotiation request received for game={}, turn={}", request.gameId(), request.turn());
        return negotiationStrategyService.planNegotiation(request);
    }
}
