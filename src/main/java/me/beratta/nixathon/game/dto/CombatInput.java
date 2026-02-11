package me.beratta.nixathon.game.dto;

import java.util.List;

public record CombatInput(
    int gameId,
    int turn,
    TowerDto playerTower,
    List<TowerDto> enemyTowers,
    List<CombatActionDto> combatActions,
    List<DiplomacyActionDto> diplomacy,
    List<CombatActionDto> previousAttacks
) {
}
