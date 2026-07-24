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
        final QuestEvent event = values.get(eventID);
        if (event == null) throw new QuestRuntimeException("Event " + eventID + " is not defined");
        if (!event.fire(profile)) {
            throw new QuestRuntimeException("Event " + eventID + " was not handled in durable completion");
        }
    }
}
