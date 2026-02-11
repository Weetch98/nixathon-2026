package me.beratta.nixathon.game.service;

import org.springframework.stereotype.Service;

@Service
public class FatigueService {

    public FatigueForecast forecast(int currentTurn, int aliveEnemyCount, Integer duelStartTurn) {
        int fatigueStartTurn = fatigueStartTurn(aliveEnemyCount, duelStartTurn);
        if (fatigueStartTurn == Integer.MAX_VALUE) {
            return new FatigueForecast(false, 0, 0, Integer.MAX_VALUE, null);
        }

        if (currentTurn < fatigueStartTurn) {
            int turnsUntilStart = fatigueStartTurn - currentTurn;
            int nextTurnDamage = currentTurn + 1 >= fatigueStartTurn ? fatigueDamageForStage(1) : 0;
            return new FatigueForecast(false, 0, nextTurnDamage, turnsUntilStart, fatigueStartTurn);
        }

        int fatigueTurnIndex = (currentTurn - fatigueStartTurn) + 1;
        int currentDamage = fatigueDamageForStage(fatigueTurnIndex);
        int nextDamage = fatigueDamageForStage(fatigueTurnIndex + 1);
        return new FatigueForecast(true, currentDamage, nextDamage, 0, fatigueStartTurn);
    }

    private int fatigueStartTurn(int aliveEnemyCount, Integer duelStartTurn) {
        int startTurn = Integer.MAX_VALUE;

        // Global fatigue rule: after 25 combats.
        startTurn = Math.min(startTurn, 26);

        // Duel fatigue rule: after 5 turns in duel mode.
        if (aliveEnemyCount == 1 && duelStartTurn != null) {
            startTurn = Math.min(startTurn, duelStartTurn + 5);
        }

        return startTurn;
    }

    private int fatigueDamageForStage(int fatigueTurnIndex) {
        if (fatigueTurnIndex <= 0) {
            return 0;
        }
        return switch (fatigueTurnIndex) {
            case 1 -> 10;
            case 2 -> 20;
            case 3 -> 40;
            default -> 80;
        };
    }

    public record FatigueForecast(
            boolean active,
            int currentDamage,
            int nextTurnDamage,
            int turnsUntilStart,
            Integer startTurn
    ) {
    }
}
