package me.beratta.nixathon.game.service;

import org.springframework.stereotype.Service;

/**
 * Calculates fatigue timing and expected fatigue damage.
 * <p>
 * The game can trigger fatigue in two different ways:
 * <ul>
 *     <li>Globally after 25 combats.</li>
 *     <li>In duel mode after 5 duel turns.</li>
 * </ul>
 * This service normalizes both rules into a single forecast object that the combat
 * planner can consume.
 */
@Service
public class FatigueService {

    /**
     * Builds a fatigue forecast for the current turn.
     *
     * @param currentTurn current game turn (1-based)
     * @param aliveEnemyCount number of currently alive enemies
     * @param duelStartTurn turn index where duel mode started, if known
     * @return fatigue state for current and next turn
     */
    public FatigueForecast forecast(int currentTurn, int aliveEnemyCount, Integer duelStartTurn) {
        int fatigueStartTurn = fatigueStartTurn(aliveEnemyCount, duelStartTurn);

        // Fatigue haven't started yet.
        if (currentTurn < fatigueStartTurn) {
            int turnsUntilStart = fatigueStartTurn - currentTurn;
            int nextTurnDamage = currentTurn + 1 >= fatigueStartTurn ? fatigueDamageForStage(1) : 0;
            return new FatigueForecast(false, 0, nextTurnDamage, turnsUntilStart, fatigueStartTurn);
        }

        // Fatigue turn index to calculate current and next fatigue damage.
        int fatigueTurnIndex = (currentTurn - fatigueStartTurn) + 1;
        int currentDamage = fatigueDamageForStage(fatigueTurnIndex);
        int nextDamage = fatigueDamageForStage(fatigueTurnIndex + 1);

        return new FatigueForecast(true, currentDamage, nextDamage, 0, fatigueStartTurn);
    }

    /**
     * Resolves the earliest fatigue start turn across all fatigue rules.
     */
    private int fatigueStartTurn(int aliveEnemyCount, Integer duelStartTurn) {
        int startTurn;

        // Global fatigue rule: after 25 combats.
        startTurn = 26;

        // Duel fatigue rule: after 5 turns in duel mode.
        if (aliveEnemyCount == 1 && duelStartTurn != null) {
            startTurn = Math.min(startTurn, duelStartTurn + 5);
        }

        return startTurn;
    }

    /**
     * Fatigue damage curve as described in the rules.
     */
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

    /**
     * Immutable fatigue projection used by combat planning.
     *
     * @param active whether fatigue is already active on current turn
     * @param currentDamage current turn fatigue damage
     * @param nextTurnDamage next turn fatigue damage
     * @param turnsUntilStart turns remaining until fatigue starts (0 if active)
     * @param startTurn resolved turn when fatigue starts
     */
    public record FatigueForecast(
            boolean active,
            int currentDamage,
            int nextTurnDamage,
            int turnsUntilStart,
            Integer startTurn
    ) {
    }
}
