package com.formacraft.server.build;

import com.formacraft.FormacraftMod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured diagnostics for Undo/Redo history desynchronization.
 * <p>
 * The facade drops ghost ledger entries so a player is never permanently blocked by
 * an invalid record. Dropping is safe but hides the earlier root cause, so every
 * drop is recorded here with the transaction id, kind, dimension and both stack tops.
 */
public final class MutationHistoryDiagnostics {
    private MutationHistoryDiagnostics() {}

    private static final int MAX_EVENTS = 100;
    private static final Deque<Map<String, Object>> EVENTS = new ArrayDeque<>();

    /**
     * @param direction "undo" or "redo"
     * @param transactionId the ledger transaction id that was dropped
     * @param kind the ledger entry kind (BUILD / PATCH)
     * @param playerId owning player
     * @param ledgerTop transaction id observed on the unified ledger
     * @param stackTop transaction id observed on the underlying history stack
     * @param reason why the entry was dropped
     */
    public static void recordGhostEntry(
            String direction,
            java.util.UUID transactionId,
            PlayerMutationLedger.Kind kind,
            java.util.UUID playerId,
            java.util.UUID ledgerTop,
            java.util.UUID stackTop,
            String reason
    ) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "mutation_history_ghost_entry");
        event.put("direction", direction);
        event.put("transaction_id", transactionId == null ? null : transactionId.toString());
        event.put("kind", kind == null ? null : kind.name());
        event.put("player", playerId == null ? null : playerId.toString());
        event.put("ledger_top", ledgerTop == null ? null : ledgerTop.toString());
        event.put("stack_top", stackTop == null ? null : stackTop.toString());
        event.put("reason", reason);
        synchronized (EVENTS) {
            EVENTS.addLast(event);
            while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
        }
        FormacraftMod.LOGGER.warn(
                "Mutation history desync: direction={} tx={} kind={} ledgerTop={} stackTop={} reason={}",
                direction, transactionId, kind, ledgerTop, stackTop, reason);
    }

    /** Recent ghost-entry events (diagnostics / tests). */
    public static java.util.List<Map<String, Object>> recentEvents() {
        synchronized (EVENTS) {
            return new java.util.ArrayList<>(EVENTS);
        }
    }

    public static void clear() {
        synchronized (EVENTS) {
            EVENTS.clear();
        }
    }
}
