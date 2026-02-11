package me.beratta.nixathon.game.dto;

public record TowerDto(
    int playerId,
    int hp,
    int armor,
    Integer resources,
    int level
) {}
