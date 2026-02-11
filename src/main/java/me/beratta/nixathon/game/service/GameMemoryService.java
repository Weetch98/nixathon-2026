package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.CombatRequest;
import me.beratta.nixathon.game.dto.DiplomacyAction;
import me.beratta.nixathon.game.dto.EnemyTowerState;
import me.beratta.nixathon.game.dto.NegotiationMessage;
import me.beratta.nixathon.game.dto.PlayerAttack;
import me.beratta.nixathon.game.dto.PlayerDiplomacy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-game memory store used by strategy services.
 * <p>
 * Keeps two categories of data:
 * <ul>
 *     <li>Short-lived negotiation commitment for the active turn.</li>
 *     <li>Long-lived behavioral profiles for each enemy player.</li>
 * </ul>
 */
@Service
public class GameMemoryService {

    private static final Logger log = LoggerFactory.getLogger(GameMemoryService.class);

    private final Map<Long, NegotiationCommitment> commitmentsByGameId = new ConcurrentHashMap<>();
    private final Map<Long, GameState> gameStateByGameId = new ConcurrentHashMap<>();

    /**
     * Persists the outgoing negotiation plan so combat phase can reuse intent.
     */
    public void storeNegotiationPlan(long gameId, int turn, List<NegotiationMessage> messages) {
        Set<Integer> messageRecipients = messages.stream()
                .map(NegotiationMessage::allyId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Set<Integer> explicitAllies = messages.stream()
                .filter(message -> message.attackTargetId() == null)
                .map(NegotiationMessage::allyId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Integer focusTarget = messages.stream()
                .map(NegotiationMessage::attackTargetId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.groupingBy(target -> target, java.util.stream.Collectors.counting()))
                .entrySet()
                .stream()
                .max(Map.Entry.<Integer, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey)
                .orElse(null);

        // Commitment is scoped to the same game+turn and replaced when recomputed.
        commitmentsByGameId.put(gameId, new NegotiationCommitment(turn, messageRecipients, explicitAllies, focusTarget));
        log.debug(
                "Stored negotiation commitment game={} turn={} recipients={} explicitAllies={} focusTarget={}",
                gameId,
                turn,
                messageRecipients,
                explicitAllies,
                focusTarget
        );
    }

    /**
     * Returns negotiation commitment if it exists for the same game turn.
     */
    public Optional<NegotiationCommitment> findCommitment(long gameId, int turn) {
        NegotiationCommitment commitment = commitmentsByGameId.get(gameId);
        if (commitment == null || commitment.turn() != turn) {
            log.debug("No negotiation commitment found for game={} turn={}", gameId, turn);
            return Optional.empty();
        }
        log.debug(
                "Negotiation commitment found for game={} turn={} recipients={} explicitAllies={} focusTarget={}",
                gameId,
                turn,
                commitment.messagedPlayerIds(),
                commitment.explicitAlliedPlayerIds(),
                commitment.focusTargetId()
        );
        return Optional.of(commitment);
    }

    /**
     * Updates lightweight visibility info from negotiation payload.
     */
    public void observeNegotiationTurn(long gameId, int turn, List<EnemyTowerState> enemyTowers) {
        GameState gameState = stateForGame(gameId);

        synchronized (gameState) {
            if (gameState.lastObservedNegotiationTurn != null && gameState.lastObservedNegotiationTurn == turn) {
                return;
            }
            gameState.lastObservedNegotiationTurn = turn;

            List<EnemyTowerState> aliveEnemies = enemyTowers.stream()
                    .filter(enemy -> enemy.hp() > 0)
                    .toList();
            updateDuelState(gameState, turn, aliveEnemies.size());

            for (EnemyTowerState enemy : aliveEnemies) {
                // Negotiation payload still gives us level/durability snapshots.
                gameState.profileFor(enemy.playerId())
                        .observeVisibility(turn, enemy.level(), enemy.effectiveDurability());
            }
        }
    }

    /**
     * Updates enemy behavior profiles from combat payload.
     */
    public void observeCombatTurn(CombatRequest request) {
        long gameId = request.gameId();
        int turn = request.turn();
        int selfId = request.playerTower().playerId();

        GameState gameState = stateForGame(gameId);

        synchronized (gameState) {
            if (gameState.lastObservedCombatTurn != null && gameState.lastObservedCombatTurn == turn) {
                return;
            }
            gameState.lastObservedCombatTurn = turn;

            List<EnemyTowerState> aliveEnemies = request.enemyTowers().stream()
                    .filter(enemy -> enemy.hp() > 0)
                    .toList();
            updateDuelState(gameState, turn, aliveEnemies.size());

            Map<Integer, List<PlayerAttack>> attacksByPlayer = new HashMap<>();
            for (PlayerAttack attack : request.previousAttacks()) {
                attacksByPlayer.computeIfAbsent(attack.playerId(), ignored -> new ArrayList<>()).add(attack);
            }

            Map<Integer, List<DiplomacyAction>> diplomacyByPlayer = new HashMap<>();
            for (PlayerDiplomacy diplomacy : request.diplomacy()) {
                diplomacyByPlayer.computeIfAbsent(diplomacy.playerId(), ignored -> new ArrayList<>())
                        .add(diplomacy.action());
            }

            for (EnemyTowerState enemy : aliveEnemies) {
                MutablePlayerProfile profile = gameState.profileFor(enemy.playerId());
                profile.observeVisibility(turn, enemy.level(), enemy.effectiveDurability());

                boolean offeredPeaceThisTurn = false;
                for (DiplomacyAction diplomacyAction : diplomacyByPlayer.getOrDefault(enemy.playerId(), List.of())) {
                    if (diplomacyAction.allyId() == selfId && diplomacyAction.attackTargetId() == null) {
                        profile.recordPeaceOffer(turn);
                        offeredPeaceThisTurn = true;
                    }
                    if (diplomacyAction.allyId() == selfId && diplomacyAction.attackTargetId() != null) {
                        profile.recordCoordinationOffer();
                    }
                    if (diplomacyAction.attackTargetId() != null && diplomacyAction.attackTargetId() == selfId) {
                        profile.recordThreatDeclaration(turn);
                    }
                }

                boolean attackedAnyoneThisTurn = false;
                boolean attackedUsThisTurn = false;
                for (PlayerAttack attack : attacksByPlayer.getOrDefault(enemy.playerId(), List.of())) {
                    attackedAnyoneThisTurn = true;
                    boolean attackedUs = attack.action().targetId() == selfId;
                    profile.recordAttack(turn, attackedUs, attack.action().troopCount());
                    if (attackedUs) {
                        attackedUsThisTurn = true;
                    }
                }

                // Track AFK-like inactivity streaks to detect passive hoarders.
                profile.recordActivityPattern(attackedAnyoneThisTurn);
                boolean recentlyOfferedPeace = profile.lastPeaceOfferTurn != null
                        && (turn - profile.lastPeaceOfferTurn) <= 2;
                // Betrayal means attacking us shortly after (or during) peace signaling.
                if (attackedUsThisTurn && (offeredPeaceThisTurn || recentlyOfferedPeace)) {
                    profile.recordBetrayal(turn);
                }
            }
        }
    }

    /**
     * Exposes immutable profile snapshots for currently relevant enemies.
     */
    public Map<Integer, PlayerProfileSnapshot> getPlayerProfiles(long gameId, List<EnemyTowerState> enemyTowers) {
        GameState gameState = gameStateByGameId.get(gameId);
        if (gameState == null) {
            return snapshotFromUnknownProfiles(enemyTowers);
        }

        synchronized (gameState) {
            Map<Integer, PlayerProfileSnapshot> snapshotsByPlayer = new HashMap<>();
            for (EnemyTowerState enemy : enemyTowers) {
                MutablePlayerProfile profile = gameState.profilesByPlayerId.get(enemy.playerId());
                PlayerProfileSnapshot snapshot = profile == null
                        ? PlayerProfileSnapshot.unknown(enemy.playerId(), enemy.level(), enemy.effectiveDurability())
                        : profile.toSnapshot(enemy.level(), enemy.effectiveDurability());
                snapshotsByPlayer.put(enemy.playerId(), snapshot);
            }
            return Map.copyOf(snapshotsByPlayer);
        }
    }

    /**
     * Returns remembered duel start turn if known.
     */
    public Optional<Integer> findDuelStartTurn(long gameId) {
        GameState gameState = gameStateByGameId.get(gameId);
        if (gameState == null) {
            return Optional.empty();
        }
        synchronized (gameState) {
            return Optional.ofNullable(gameState.duelStartTurn);
        }
    }

    /**
     * Gets or creates per-game mutable state container.
     */
    private GameState stateForGame(long gameId) {
        return gameStateByGameId.computeIfAbsent(gameId, ignored -> new GameState());
    }

    /**
     * Builds unknown profile placeholders when a game has no stored history yet.
     */
    private Map<Integer, PlayerProfileSnapshot> snapshotFromUnknownProfiles(List<EnemyTowerState> enemyTowers) {
        Map<Integer, PlayerProfileSnapshot> snapshotsByPlayer = new HashMap<>();
        for (EnemyTowerState enemy : enemyTowers) {
            snapshotsByPlayer.put(
                    enemy.playerId(),
                    PlayerProfileSnapshot.unknown(enemy.playerId(), enemy.level(), enemy.effectiveDurability())
            );
        }
        return Map.copyOf(snapshotsByPlayer);
    }

    /**
     * Tracks when duel starts so fatigue calculations can apply duel fatigue rule.
     */
    private void updateDuelState(GameState gameState, int turn, int aliveEnemyCount) {
        if (aliveEnemyCount == 1) {
            if (gameState.lastAliveEnemyCount > 1 || gameState.duelStartTurn == null) {
                gameState.duelStartTurn = turn;
            }
        } else if (aliveEnemyCount > 1) {
            gameState.duelStartTurn = null;
        }
        gameState.lastAliveEnemyCount = aliveEnemyCount;
    }

    /**
     * Turn-scoped intent captured from our own negotiation response.
     */
    public record NegotiationCommitment(
            int turn,
            Set<Integer> messagedPlayerIds,
            Set<Integer> explicitAlliedPlayerIds,
            Integer focusTargetId
    ) {
    }

    /**
     * Immutable view of an enemy's observed behavior profile.
     */
    public record PlayerProfileSnapshot(
            int playerId,
            int turnsObserved,
            int attacksAgainstUsCount,
            int attacksAgainstUsTroops,
            int attacksAgainstOthersCount,
            int attacksAgainstOthersTroops,
            int totalAttacks,
            int totalTroopsSent,
            int peaceOffersToUs,
            int coordinationOffersToUs,
            int threatDeclarationsToUs,
            int betrayalsAgainstUs,
            int consecutiveNoAttackTurns,
            int consecutiveAttackTurns,
            int knownLevel,
            int knownDurability,
            int lastSeenTurn,
            Integer lastAttackTurn
    ) {
        /**
         * Creates a minimal snapshot when we have no historical data yet.
         */
        public static PlayerProfileSnapshot unknown(int playerId, int level, int durability) {
            return new PlayerProfileSnapshot(
                    playerId,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    level,
                    durability,
                    0,
                    null
            );
        }

        /**
         * Composite hostility signal used by threat/target ranking.
         */
        public int hostilityScore() {
            return (attacksAgainstUsTroops / 2)
                    + (attacksAgainstUsCount * 6)
                    + (threatDeclarationsToUs * 12)
                    + (betrayalsAgainstUs * 18);
        }

        /**
         * Composite trust signal. Positive values imply better reliability.
         */
        public int trustScore() {
            return (peaceOffersToUs * 6)
                    + (coordinationOffersToUs * 2)
                    - (threatDeclarationsToUs * 10)
                    - (betrayalsAgainstUs * 20)
                    - (attacksAgainstUsCount * 6);
        }

        /**
         * Heuristic for players that stay passive while likely accumulating resources.
         */
        public boolean likelyAfkCollector() {
            boolean rarelyAttacks = totalAttacks <= Math.max(1, turnsObserved / 3);
            return consecutiveNoAttackTurns >= 3 && rarelyAttacks && knownLevel >= 2;
        }

        /**
         * Risk value for passive hoarders that can explode later.
         */
        public int afkHoardingRisk() {
            if (!likelyAfkCollector()) {
                return 0;
            }
            return (consecutiveNoAttackTurns * 5) + (knownLevel * 6) + (knownDurability / 12);
        }
    }

    /**
     * Mutable state bucket for one game instance.
     */
    private static final class GameState {

        private Integer lastObservedNegotiationTurn;
        private Integer lastObservedCombatTurn;
        private Integer duelStartTurn;
        private int lastAliveEnemyCount = Integer.MAX_VALUE;
        private final Map<Integer, MutablePlayerProfile> profilesByPlayerId = new HashMap<>();

        private MutablePlayerProfile profileFor(int playerId) {
            return profilesByPlayerId.computeIfAbsent(playerId, MutablePlayerProfile::new);
        }
    }

    /**
     * Mutable profile accumulator; converted to immutable snapshots for strategy consumption.
     */
    private static final class MutablePlayerProfile {

        private final int playerId;
        private int turnsObserved;
        private int attacksAgainstUsCount;
        private int attacksAgainstUsTroops;
        private int attacksAgainstOthersCount;
        private int attacksAgainstOthersTroops;
        private int totalAttacks;
        private int totalTroopsSent;
        private int peaceOffersToUs;
        private int coordinationOffersToUs;
        private int threatDeclarationsToUs;
        private int betrayalsAgainstUs;
        private int consecutiveNoAttackTurns;
        private int consecutiveAttackTurns;
        private int knownLevel;
        private int knownDurability;
        private int lastSeenTurn;
        private Integer lastAttackTurn;
        private Integer lastPeaceOfferTurn;
        private Integer lastBetrayalTurn;

        private MutablePlayerProfile(int playerId) {
            this.playerId = playerId;
        }

        /**
         * Stores latest tower strength observations from request payload.
         */
        private void observeVisibility(int turn, int level, int durability) {
            if (lastSeenTurn != turn) {
                turnsObserved++;
                lastSeenTurn = turn;
            }
            knownLevel = Math.max(knownLevel, level);
            knownDurability = Math.max(knownDurability, durability);
        }

        /**
         * Records a plain peace/alliance signal sent to us.
         */
        private void recordPeaceOffer(int turn) {
            peaceOffersToUs++;
            lastPeaceOfferTurn = turn;
        }

        /**
         * Records a coordination message (ally + attack target).
         */
        private void recordCoordinationOffer() {
            coordinationOffersToUs++;
        }

        /**
         * Records direct threat declaration against us.
         */
        private void recordThreatDeclaration(int turn) {
            threatDeclarationsToUs++;
        }

        /**
         * Records combat activity and separates direct attacks on us from others.
         */
        private void recordAttack(int turn, boolean attackedUs, int troops) {
            int safeTroops = Math.max(troops, 0);
            totalAttacks++;
            totalTroopsSent += safeTroops;
            lastAttackTurn = turn;

            if (attackedUs) {
                attacksAgainstUsCount++;
                attacksAgainstUsTroops += safeTroops;
                return;
            }
            attacksAgainstOthersCount++;
            attacksAgainstOthersTroops += safeTroops;
        }

        /**
         * Maintains activity streak counters used for AFK hoarder detection.
         */
        private void recordActivityPattern(boolean attackedAnyoneThisTurn) {
            if (attackedAnyoneThisTurn) {
                consecutiveAttackTurns++;
                consecutiveNoAttackTurns = 0;
                return;
            }
            consecutiveNoAttackTurns++;
            consecutiveAttackTurns = 0;
        }

        /**
         * Marks one betrayal event per turn.
         */
        private void recordBetrayal(int turn) {
            if (lastBetrayalTurn != null && lastBetrayalTurn == turn) {
                return;
            }
            betrayalsAgainstUs++;
            lastBetrayalTurn = turn;
        }

        /**
         * Converts mutable state to immutable strategy-facing snapshot.
         */
        private PlayerProfileSnapshot toSnapshot(int latestLevel, int latestDurability) {
            return new PlayerProfileSnapshot(
                    playerId,
                    turnsObserved,
                    attacksAgainstUsCount,
                    attacksAgainstUsTroops,
                    attacksAgainstOthersCount,
                    attacksAgainstOthersTroops,
                    totalAttacks,
                    totalTroopsSent,
                    peaceOffersToUs,
                    coordinationOffersToUs,
                    threatDeclarationsToUs,
                    betrayalsAgainstUs,
                    consecutiveNoAttackTurns,
                    consecutiveAttackTurns,
                    Math.max(knownLevel, latestLevel),
                    Math.max(knownDurability, latestDurability),
                    lastSeenTurn,
                    lastAttackTurn
            );
        }
    }
}
