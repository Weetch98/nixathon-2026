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

@RestController
public class CombatController {

    private static final Logger log = LoggerFactory.getLogger(CombatController.class);

    private final CombatStrategyService combatStrategyService;

    public CombatController(CombatStrategyService combatStrategyService) {
        this.combatStrategyService = combatStrategyService;
    }

    @PostMapping("/combat")
    public List<CombatActionResponse> combat(@Valid @RequestBody CombatRequest request) {
        log.debug("Combat request received for game={}, turn={}", request.gameId(), request.turn());
        return combatStrategyService.planCombat(request);
    }
}
