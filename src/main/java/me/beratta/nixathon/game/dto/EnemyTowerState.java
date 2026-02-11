package me.beratta.nixathon.game.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnemyTowerState(
        @NotNull @Positive Integer playerId,
        @NotNull @PositiveOrZero Integer hp,
        @NotNull @PositiveOrZero Integer armor,
        @NotNull @Positive Integer level
) {
    public int effectiveDurability() {
        return hp + armor;
    }
}
