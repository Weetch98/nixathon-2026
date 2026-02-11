package me.beratta.nixathon.game.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BuildInfoService {

    private static final Logger log = LoggerFactory.getLogger(BuildInfoService.class);

    private final String buildNumber;

    public BuildInfoService(@Value("${build.number:unknown}") String buildNumber) {
        this.buildNumber = buildNumber;
        log.info("Build information loaded build={}", buildNumber);
    }

    public String getBuildNumber() {
        return buildNumber;
    }
}
