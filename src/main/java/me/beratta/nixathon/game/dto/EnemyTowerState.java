package me.beratta.nixathon.game.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnemyTowerState(
        Integer playerId,
        Integer hp,
        Integer armor,
        Integer level
) {
    public int effectiveDurability() {
        return hp + armor;
    }
}
