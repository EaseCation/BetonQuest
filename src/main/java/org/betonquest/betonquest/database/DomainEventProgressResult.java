package org.betonquest.betonquest.database;

import org.jetbrains.annotations.Nullable;

/** Result of atomically advancing one objective together with a source-domain event cursor. */
public record DomainEventProgressResult(Status status, @Nullable String instructions) {
    /** Durable processing outcome used by source-domain outbox consumers. */
    public enum Status {
        APPLIED,
        REPLAYED,
        NOT_ACTIVE
    }
}
