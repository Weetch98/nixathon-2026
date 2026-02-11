package me.beratta.nixathon.game.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FatigueServiceTest {

    private final FatigueService fatigueService = new FatigueService();

    @Test
    void shouldApplyDuelFatigueProgression() {
        FatigueService.FatigueForecast turnFive = fatigueService.forecast(5, 1, 1);
        assertFalse(turnFive.active());
        assertEquals(1, turnFive.turnsUntilStart());

        FatigueService.FatigueForecast turnSix = fatigueService.forecast(6, 1, 1);
        assertTrue(turnSix.active());
        assertEquals(10, turnSix.currentDamage());
        assertEquals(20, turnSix.nextTurnDamage());

        FatigueService.FatigueForecast turnEight = fatigueService.forecast(8, 1, 1);
        assertTrue(turnEight.active());
        assertEquals(40, turnEight.currentDamage());

        FatigueService.FatigueForecast turnNine = fatigueService.forecast(9, 1, 1);
        assertTrue(turnNine.active());
        assertEquals(80, turnNine.currentDamage());
    }

    @Test
    void shouldApplyGlobalFatigueAfterTurnTwentyFive() {
        FatigueService.FatigueForecast beforeFatigue = fatigueService.forecast(25, 3, null);
        assertFalse(beforeFatigue.active());
        assertEquals(1, beforeFatigue.turnsUntilStart());

        FatigueService.FatigueForecast fatigueStart = fatigueService.forecast(26, 3, null);
        assertTrue(fatigueStart.active());
        assertEquals(10, fatigueStart.currentDamage());
    }
}
