package me.beratta.nixathon.game.service;

import me.beratta.nixathon.game.dto.NegotiationMessage;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameMemoryService {

    private final Map<Long, NegotiationCommitment> commitmentsByGameId = new ConcurrentHashMap<>();

    public void storeNegotiationPlan(long gameId, int turn, List<NegotiationMessage> messages) {
        Set<Integer> allies = messages.stream()
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

        commitmentsByGameId.put(gameId, new NegotiationCommitment(turn, allies, focusTarget));
    }

    public Optional<NegotiationCommitment> findCommitment(long gameId, int turn) {
        NegotiationCommitment commitment = commitmentsByGameId.get(gameId);
        if (commitment == null || commitment.turn() != turn) {
            return Optional.empty();
        }
        return Optional.of(commitment);
    }

    public record NegotiationCommitment(
            int turn,
            Set<Integer> alliedPlayerIds,
            Integer focusTargetId
    ) {
    }
}
