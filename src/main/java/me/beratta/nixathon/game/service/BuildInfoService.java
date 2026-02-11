package me.beratta.nixathon.game.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BuildInfoService {

    private final String buildNumber;

    public BuildInfoService(@Value("${build.number:unknown}") String buildNumber) {
        this.buildNumber = buildNumber;
    }

    public String getBuildNumber() {
        return buildNumber;
    }
}
