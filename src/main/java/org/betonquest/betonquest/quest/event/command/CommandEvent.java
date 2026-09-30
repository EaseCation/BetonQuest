package org.betonquest.betonquest.quest.event.command;

import org.betonquest.betonquest.api.bukkit.command.SilentConsoleCommandSender;
import org.betonquest.betonquest.api.profiles.Profile;
import org.betonquest.betonquest.api.quest.event.nullable.NullableEvent;
import org.betonquest.betonquest.exceptions.QuestRuntimeException;
import org.betonquest.betonquest.instruction.variable.VariableString;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Event that runs given commands in the server console.
 */
public class CommandEvent implements NullableEvent {

    /**
     * Command sender to run the commands as.
     * <p>
     * {@link SilentConsoleCommandSender} is used to keep console and log clean.
     */
    private final CommandSender silentSender;

    /**
     * Server to run the commands on.
     */
    private final Server server;

    /**
     * The commands to run.
     */
    private final List<VariableString> commands;

    /**
     * Creates a new CommandEvent.
     *
     * @param commands     the commands to run
     * @param silentSender the command sender to run the commands as
     * @param server       the server to run the commands on
     */
    public CommandEvent(final List<VariableString> commands, final CommandSender silentSender, final Server server) {
        this.silentSender = silentSender;
        this.server = server;
        this.commands = commands;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Override
    public void execute(@Nullable final Profile profile) throws QuestRuntimeException {
        final java.util.concurrent.CompletionStage<Void> completion = dispatchNext(profile, 0);
        final java.util.concurrent.atomic.AtomicReference<Throwable> immediateFailure = new java.util.concurrent.atomic.AtomicReference<>();
        completion.whenComplete((ignored, failure) -> immediateFailure.set(failure));
        if (immediateFailure.get() != null) throw new QuestRuntimeException("Confirmed command failed", immediateFailure.get());
        org.betonquest.betonquest.quest.registry.processor.EventContinuation.observe(completion);
    }

    private java.util.concurrent.CompletionStage<Void> dispatchNext(@Nullable final Profile profile, final int index) {
        if (index >= commands.size()) return java.util.concurrent.CompletableFuture.completedFuture(null);
        try {
            final String command = commands.get(index).getValue(profile);
            final Runnable dispatch = () -> {
                if (!server.dispatchCommand(silentSender, command)) throw new IllegalStateException("Command was not handled: " + command);
            };
            java.util.concurrent.CompletionStage<Void> confirmation;
            final org.bukkit.plugin.Plugin home = server.getPluginManager().getPlugin("SuperiorSkyblock2");
            if (home != null && home.isEnabled()) {
                final Class<?> contract = home.getClass().getClassLoader().loadClass(
                        "com.bgsoftware.superiorskyblock.api.service.home.HomeCommandConfirmation");
                @SuppressWarnings("unchecked") final java.util.concurrent.CompletionStage<Void> captured =
                        (java.util.concurrent.CompletionStage<Void>) contract.getMethod("capture", Runnable.class, boolean.class)
                                .invoke(null, dispatch, org.betonquest.betonquest.quest.registry.processor.EventContinuation.requiresSynchronousCompletion());
                confirmation = captured;
            } else {
                if (command.toLowerCase(java.util.Locale.ROOT).matches("/?(?:is|island|islands|ss2)\\s+admin\\s+home\\s+.*"))
                    throw new IllegalStateException("Home command authority is unavailable");
                dispatch.run();
                confirmation = java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return confirmation.thenCompose(ignored -> {
                final java.util.concurrent.CompletableFuture<Void> next = new java.util.concurrent.CompletableFuture<>();
                final Runnable continuation = () -> dispatchNext(profile, index + 1).whenComplete((result, failure) -> {
                    if (failure == null) next.complete(null); else next.completeExceptionally(failure);
                });
                if (server.isPrimaryThread()) continuation.run();
                else server.getScheduler().runTask(org.betonquest.betonquest.BetonQuest.getInstance(), continuation);
                return next;
            });
        } catch (final Exception exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(new QuestRuntimeException(
                    "Unhandled exception executing confirmed command: " + exception.getMessage(), exception));
        }
    }
}
