package me.beratta.nixathon.api.logging;

import me.beratta.nixathon.game.service.BuildInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class ApplicationStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(ApplicationStartupLogger.class);

    private final Environment environment;
    private final BuildInfoService buildInfoService;

    public ApplicationStartupLogger(Environment environment, BuildInfoService buildInfoService) {
        this.environment = environment;
        this.buildInfoService = buildInfoService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String appName = environment.getProperty("spring.application.name", "nixathon");
        String activeProfiles = Arrays.toString(environment.getActiveProfiles());

        log.info(
                "Application ready app={} build={} javaVersion={} activeProfiles={}",
                appName,
                buildInfoService.getBuildNumber(),
                System.getProperty("java.version"),
                activeProfiles
        );
    }
}
