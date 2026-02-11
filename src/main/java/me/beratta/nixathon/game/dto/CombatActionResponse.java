package me.beratta.nixathon.game.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CombatActionResponse(
        String type,
        Integer amount,
        Integer targetId,
        Integer troopCount
) {
    public static CombatActionResponse armor(int amount) {
        return new CombatActionResponse("armor", amount, null, null);
    }

    public static CombatActionResponse attack(int targetId, int troopCount) {
        return new CombatActionResponse("attack", null, targetId, troopCount);
    }

    public static CombatActionResponse upgrade() {
        return new CombatActionResponse("upgrade", null, null, null);
    }
}
