package me.beratta.nixathon.api.controller;

import me.beratta.nixathon.game.dto.BuildResponse;
import me.beratta.nixathon.game.service.BuildInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BuildController {

    private static final Logger log = LoggerFactory.getLogger(BuildController.class);

    private final BuildInfoService buildInfoService;

    public BuildController(BuildInfoService buildInfoService) {
        this.buildInfoService = buildInfoService;
    }

    @GetMapping("/build")
    public BuildResponse getBuild() {
        String buildNumber = buildInfoService.getBuildNumber();
        String commitNumber = buildInfoService.getCommitNumber();
        log.debug("Build endpoint requested build={} commit={}", buildNumber, commitNumber);
        return new BuildResponse(buildNumber, commitNumber);
    }
}
