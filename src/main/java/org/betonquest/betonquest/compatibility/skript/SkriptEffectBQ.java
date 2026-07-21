package org.betonquest.betonquest.compatibility.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.api.logger.BetonQuestLogger;
import org.betonquest.betonquest.exceptions.ObjectNotFoundException;
import org.betonquest.betonquest.id.EventID;
import org.betonquest.betonquest.utils.PlayerConverter;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.Nullable;

/**
 * Skript effect, which fires specified BetonQuest's event
 */
@SuppressWarnings({"PMD.CommentRequired", "NullAway.Init"})
public class SkriptEffectBQ extends Effect {
    /**
     * Custom {@link BetonQuestLogger} instance for this class.
     */
    private final BetonQuestLogger log;

    private Expression<String> event;

    private Expression<Player> player;

    public SkriptEffectBQ() {
        super();
        this.log = BetonQuest.getInstance().getLoggerFactory().create(getClass());
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean init(final Expression<?>[] exprs, final int matchedPattern, final Kleenean isDelayed, final ParseResult parseResult) {
        this.event = (Expression<String>) exprs[0];
        this.player = (Expression<Player>) exprs[1];
        return true;
    }

    @Override
    @SuppressFBWarnings("NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE")
    public String toString(@Nullable final Event event, final boolean debug) {
        return "fire " + this.event + " for " + player.getSingle(event).getName();
    }

    @Override
    protected void execute(final Event event) {
        // Resolve Skript expressions on the current tick. Local variables may become
        // <none> after the effect returns; the next-tick task must use these snapshots.
        final String eventID = this.event.getSingle(event);
        final Player target = this.player.getSingle(event);
        if (eventID == null || target == null) {
            log.warn("Cannot fire BetonQuest event from Skript: event id or player is <none>");
            return;
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    BetonQuest.event(PlayerConverter.getID(target), new EventID(null, eventID));
                } catch (final ObjectNotFoundException e) {
                    log.warn("Error when running Skript event - could not load '" + eventID + "' event: " + e.getMessage(), e);
                }
            }
        }.runTask(BetonQuest.getInstance());
    }
}
