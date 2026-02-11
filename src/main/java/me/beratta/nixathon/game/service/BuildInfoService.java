package me.beratta.nixathon.game.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BuildInfoService {

    private static final Logger log = LoggerFactory.getLogger(BuildInfoService.class);

    private final String buildNumber;
    private final String commitNumber;

    public BuildInfoService(
            @Value("${build.number:unknown}") String buildNumber,
            @Value("${build.commit:unknown}") String commitNumber
    ) {
        this.buildNumber = buildNumber;
        this.commitNumber = commitNumber;
        log.info("Build information loaded build={} commit={}", buildNumber, commitNumber);
    }

    public String getBuildNumber() {
        return buildNumber;
    }

    public String getCommitNumber() {
        return commitNumber;
    }
}
