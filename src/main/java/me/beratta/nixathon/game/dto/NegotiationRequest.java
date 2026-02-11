package me.beratta.nixathon.game.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NegotiationRequest(
        @NotNull @Positive Long gameId,
        @NotNull @Positive Integer turn,
        @NotNull @Valid TowerState playerTower,
        @NotNull List<@Valid EnemyTowerState> enemyTowers,
        @NotNull List<@Valid PlayerAttack> combatActions
) {
    public NegotiationRequest {
        enemyTowers = enemyTowers == null ? List.of() : List.copyOf(enemyTowers);
        combatActions = combatActions == null ? List.of() : List.copyOf(combatActions);
    }
}
