package me.beratta.nixathon.api.controller;

import me.beratta.nixathon.game.dto.BuildResponse;
import me.beratta.nixathon.game.service.BuildInfoService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildControllerTest {

    @Test
    void shouldReturnConfiguredBuildNumber() {
        BuildController controller = new BuildController(new BuildInfoService("test-build-123"));
        BuildResponse response = controller.getBuild();

        assertEquals("test-build-123", response.build());
    }
}
