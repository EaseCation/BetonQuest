package org.betonquest.betonquest.compatibility.skript;

import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Skript event, which listens to custom event fired by BetonQuest's event
 */
@SuppressWarnings("PMD.CommentRequired")
public class SkriptEventBQ extends SkriptEvent {

    @SuppressWarnings("NullAway.Init")
    private Literal<?> literal;

    public SkriptEventBQ() {
        super();
    }

    @Override
    public String toString(@Nullable final Event event, final boolean debug) {
        return "on betonquest event";
    }

    @Override
    public boolean init(final Literal<?>[] args, final int matchedPattern, final ParseResult parseResult) {
        literal = args[0];
        return true;
    }

    @Override
    public boolean check(final Event event) {
        // Skript 2.15+ replaced Expression.check(Event, Checker) with Predicate overloads.
        // Bind to Predicate so the runtime descriptor matches Skript-2.15.2 on the server.
        return event instanceof final BQEventSkript.CustomEventForSkript scriptEvent
                && literal.check(event, (Predicate<Object>) other ->
                other instanceof final String identifier && scriptEvent.getID().equals(identifier));
    }
}
