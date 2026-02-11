package me.beratta.nixathon.api.controller;

import me.beratta.nixathon.game.dto.HealthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/healthz")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    @GetMapping
    public HealthResponse getHealth() {
        log.debug("Health endpoint requested");
        return new HealthResponse("OK");
    }
}
