package me.beratta.nixathon.api.controller;

import me.beratta.nixathon.game.dto.BuildResponse;
import me.beratta.nixathon.game.service.BuildInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BuildController {

    private final BuildInfoService buildInfoService;

    public BuildController(BuildInfoService buildInfoService) {
        this.buildInfoService = buildInfoService;
    }

    @GetMapping("/build")
    public BuildResponse getBuild() {
        return new BuildResponse(buildInfoService.getBuildNumber());
    }
}
