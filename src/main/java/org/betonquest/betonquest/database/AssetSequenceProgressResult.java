package org.betonquest.betonquest.database;

import org.jetbrains.annotations.Nullable;

/**
 * Compatibility result for the former PlayerAsset-specific API.
 *
 * @param status       whether the event changed progress, was already applied, or had no active objective
 * @param instructions committed objective instructions, or {@code null} when the objective is not active
 */
@Deprecated(forRemoval = false)
public record AssetSequenceProgressResult(Status status, @Nullable String instructions) {
    /** Durable processing outcome used by outbox consumers. */
    public enum Status {
        APPLIED,
        REPLAYED,
        NOT_ACTIVE
    }
}
