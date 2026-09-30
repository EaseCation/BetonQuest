package org.betonquest.betonquest.quest.registry.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Captures durable asynchronous effects executed during a quest event without blocking its server thread. */
public final class EventContinuation implements AutoCloseable {
    private static final ThreadLocal<EventContinuation> CURRENT = new ThreadLocal<>();
    private final EventContinuation parent;
    private final List<CompletionStage<?>> pending = new ArrayList<>();
    private final boolean synchronousOnly;

    public EventContinuation() { this(false); }
    public EventContinuation(boolean synchronousOnly) {
        parent = CURRENT.get(); this.synchronousOnly = synchronousOnly || parent != null && parent.synchronousOnly;
        CURRENT.set(this);
    }

    public static boolean requiresSynchronousCompletion() {
        EventContinuation scope = CURRENT.get();
        return scope != null && scope.synchronousOnly;
    }

    public static void observe(final CompletionStage<?> completion) {
        final EventContinuation scope = CURRENT.get();
        if (scope != null) scope.pending.add(completion);
    }

    public CompletableFuture<Void> completion() {
        return CompletableFuture.allOf(pending.stream().map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new));
    }

    @Override public void close() {
        if (parent == null) CURRENT.remove();
        else { CURRENT.set(parent); parent.pending.add(completion()); }
    }
}
