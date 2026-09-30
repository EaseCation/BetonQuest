package org.betonquest.betonquest.quest.registry.processor;

import org.betonquest.betonquest.api.QuestEvent;
import org.betonquest.betonquest.api.config.quest.QuestPackage;
import org.betonquest.betonquest.api.logger.BetonQuestLogger;
import org.betonquest.betonquest.api.profiles.Profile;
import org.betonquest.betonquest.exceptions.ObjectNotFoundException;
import org.betonquest.betonquest.exceptions.QuestRuntimeException;
import org.betonquest.betonquest.id.EventID;
import org.betonquest.betonquest.integration.observer.QuestObserver;
import org.betonquest.betonquest.quest.registry.type.EventTypeRegistry;
import org.jetbrains.annotations.Nullable;

/**
 * Stores Events and execute them.
 */
public class EventProcessor extends TypedQuestProcessor<EventID, QuestEvent> {
    private final java.util.Map<java.util.UUID, java.util.concurrent.CompletableFuture<Void>> continuations = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.UUID STATIC_CONTEXT = new java.util.UUID(0L, 0L);
    /**
     * Create a new Event Processor to store events and execute them.
     *
     * @param log        the custom logger for this class
     * @param eventTypes the available event types
     */
    public EventProcessor(final BetonQuestLogger log, final EventTypeRegistry eventTypes) {
        super(log, eventTypes, "Event", "events");
    }

    @Override
    protected EventID getIdentifier(final QuestPackage pack, final String identifier) throws ObjectNotFoundException {
        return new EventID(pack, identifier);
    }

    /**
     * Fires an event for the {@link Profile} if it meets the event's conditions.
     * If the profile is null, the event will be fired as a static event.
     *
     * @param profile the {@link Profile} for which the event must be executed or null
     * @param eventID ID of the event to fire
     * @return true if the event was run even if there was an exception during execution
     */
    public boolean execute(@Nullable final Profile profile, final EventID eventID) {
        final java.util.UUID key = profile == null ? STATIC_CONTEXT : profile.getProfileUUID();
        final java.util.concurrent.CompletableFuture<Void> pending = continuations.get(key);
        if (pending != null) {
            final java.util.concurrent.CompletableFuture<Void> queued = pending.thenCompose(ignored -> onMain(() -> executeConfirmed(profile, eventID)));
            continuations.put(key, queued);
            EventContinuation.observe(queued);
            observeContinuation(key, queued, eventID);
            return true;
        }
        try (EventContinuation scope = new EventContinuation()) {
            final boolean handled = executeImmediate(profile, eventID);
            final java.util.concurrent.CompletableFuture<Void> completion = scope.completion();
            if (!completion.isDone() || completion.isCompletedExceptionally()) {
                continuations.put(key, completion); observeContinuation(key, completion, eventID);
            }
            return handled;
        }
    }

    private void observeContinuation(final java.util.UUID key, final java.util.concurrent.CompletableFuture<Void> completion,
                                     final EventID eventID) {
        completion.whenComplete((result, failure) -> {
            if (failure == null) continuations.remove(key, completion);
            else org.bukkit.Bukkit.getScheduler().runTask(org.betonquest.betonquest.BetonQuest.getInstance(),
                    () -> continuations.remove(key, completion));
            if (failure != null) log.warn(eventID.getPackage(), "Confirmed event chain stopped at " + eventID
                    + "; its subsequent events were not executed: " + failure.getMessage(), failure);
        });
    }

    private java.util.concurrent.CompletableFuture<Void> onMain(final java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> action) {
        final java.util.concurrent.CompletableFuture<Void> completion = new java.util.concurrent.CompletableFuture<>();
        final Runnable continuation = () -> {
            try { action.get().whenComplete((result, failure) -> {
                if (failure == null) completion.complete(null); else completion.completeExceptionally(failure);
            }); } catch (final RuntimeException failure) { completion.completeExceptionally(failure); }
        };
        if (org.bukkit.Bukkit.isPrimaryThread()) continuation.run();
        else org.bukkit.Bukkit.getScheduler().runTask(org.betonquest.betonquest.BetonQuest.getInstance(), continuation);
        return completion;
    }

    private java.util.concurrent.CompletableFuture<Void> executeConfirmed(@Nullable final Profile profile, final EventID eventID) {
        try (EventContinuation scope = new EventContinuation()) {
            executeImmediate(profile, eventID);
            return scope.completion();
        }
    }

    private boolean executeImmediate(@Nullable final Profile profile, final EventID eventID) {
        final QuestEvent event = values.get(eventID);
        if (event == null) {
            log.warn(eventID.getPackage(), "Event " + eventID + " is not defined");
            return false;
        }
        if (profile == null) {
            log.debug(eventID.getPackage(), "Firing event " + eventID + " player independent");
        } else {
            log.debug(eventID.getPackage(),
                    "Firing event " + eventID + " for " + profile);
        }
        try {
            final boolean handled = event.fire(profile);
            if (handled && profile != null) {
                QuestObserver.task(profile, eventID.toString(), "QUEST_EVENT", 1);
            }
            return handled;
        } catch (final QuestRuntimeException e) {
            log.warn(eventID.getPackage(), "Error while firing '" + eventID + "' event: " + e.getMessage(), e);
            return true;
        }
    }

    /**
     * Executes an event for a reliable external fact and propagates every failure to the caller.
     *
     * <p>The legacy path deliberately logs and swallows {@link QuestRuntimeException}. An outbox consumer must not ACK
     * after such a failure, otherwise an item or money reward could be lost permanently.</p>
     */
    public void executeDurable(@Nullable final Profile profile, final EventID eventID) throws QuestRuntimeException {
        try (EventContinuation scope = new EventContinuation(true)) {
        final QuestEvent event = values.get(eventID);
        if (event == null) throw new QuestRuntimeException("Event " + eventID + " is not defined");
        if (!event.fire(profile)) {
            throw new QuestRuntimeException("Event " + eventID + " was not handled in durable completion");
        }
        }
    }

    /** Completion-aware callers ACK their own outbox only after this stage succeeds. */
    public java.util.concurrent.CompletableFuture<Void> executeDurableAsync(@Nullable final Profile profile, final EventID eventID) {
        return onMain(() -> {
            try (EventContinuation scope = new EventContinuation()) {
                final QuestEvent event = values.get(eventID);
                if (event == null || !event.fire(profile)) return java.util.concurrent.CompletableFuture.failedFuture(
                        new QuestRuntimeException("Event " + eventID + " is undefined or was not handled"));
                return scope.completion();
            } catch (final QuestRuntimeException failure) { return java.util.concurrent.CompletableFuture.failedFuture(failure); }
        });
    }
}
