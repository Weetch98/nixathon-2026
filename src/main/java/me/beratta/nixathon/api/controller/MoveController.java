package me.beratta.nixathon.api.controller;

import jakarta.validation.Valid;
import me.beratta.nixathon.game.dto.MoveRequest;
import me.beratta.nixathon.game.dto.MoveResponse;
import me.beratta.nixathon.game.service.MoveStrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/game")
public class MoveController {

    private static final Logger log = LoggerFactory.getLogger(MoveController.class);

    private final MoveStrategyService moveStrategyService;

    public MoveController(MoveStrategyService moveStrategyService) {
        this.moveStrategyService = moveStrategyService;
    }

    @PostMapping("/move")
    public MoveResponse pickMove(@Valid @RequestBody MoveRequest request) {
        log.info("Received move request");

        return moveStrategyService.chooseMove();
    }
}
