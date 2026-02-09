package me.beratta.nixathon.api.controller;

import me.beratta.nixathon.game.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/healthz")
public class HealthController {

    @GetMapping
    public HealthResponse getHealth() {
        return new HealthResponse("OK");
    }
}
