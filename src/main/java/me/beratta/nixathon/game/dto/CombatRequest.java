package me.beratta.nixathon.game.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CombatRequest(
        @NotNull @Positive Long gameId,
        @NotNull @Positive Integer turn,
        @NotNull @Valid TowerState playerTower,
        @NotNull List<@Valid EnemyTowerState> enemyTowers,
        @NotNull List<@Valid PlayerDiplomacy> diplomacy,
        @NotNull List<@Valid PlayerAttack> previousAttacks
) {
    public CombatRequest {
        enemyTowers = enemyTowers == null ? List.of() : List.copyOf(enemyTowers);
        diplomacy = diplomacy == null ? List.of() : List.copyOf(diplomacy);
        previousAttacks = previousAttacks == null ? List.of() : List.copyOf(previousAttacks);
    }
}
