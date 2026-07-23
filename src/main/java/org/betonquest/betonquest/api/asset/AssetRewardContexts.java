package org.betonquest.betonquest.api.asset;

import org.betonquest.betonquest.api.profiles.Profile;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Thread-confined scope used only while one objective's completion events are being evaluated. */
public final class AssetRewardContexts {
    private static final ThreadLocal<ActiveContext> CURRENT = new ThreadLocal<>();

    private AssetRewardContexts() {
    }

    public static Scope open(final Profile profile, final AssetRewardContext context) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(context, "context");
        if (CURRENT.get() != null) throw new IllegalStateException("Nested asset reward contexts are not supported");
        final ActiveContext active = new ActiveContext(profile.getProfileUUID(), context);
        CURRENT.set(active);
        return () -> {
            if (CURRENT.get() != active) throw new IllegalStateException("Asset reward context closed out of order");
            CURRENT.remove();
        };
    }

    public static Optional<AssetRewardContext> current(final Profile profile) {
        final ActiveContext active = CURRENT.get();
        return active == null || !active.profileId().equals(profile.getProfileUUID())
                ? Optional.empty() : Optional.of(active.context());
    }

    private record ActiveContext(UUID profileId, AssetRewardContext context) {
    }

    /** Scope must be closed in the same thread before completion events return. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
