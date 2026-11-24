package org.betonquest.betonquest;

import io.papermc.lib.PaperLib;
import org.betonquest.betonquest.api.Objective;
import org.betonquest.betonquest.api.logger.BetonQuestLogger;
import org.betonquest.betonquest.api.logger.BetonQuestLoggerFactory;
import org.betonquest.betonquest.api.profiles.OnlineProfile;
import org.betonquest.betonquest.api.profiles.Profile;
import org.betonquest.betonquest.conversation.ConversationResumer;
import org.betonquest.betonquest.database.PlayerData;
import org.betonquest.betonquest.objectives.ResourcePackObjective;
import org.betonquest.betonquest.utils.PlayerConverter;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.concurrent.TimeUnit;

/**
 * Listener which handles data loading/saving when players are joining/quitting.
 */
@SuppressWarnings("PMD.CommentRequired")
public class JoinQuitListener implements Listener {
    /**
     * The {@link BetonQuestLoggerFactory} to use for creating {@link BetonQuestLogger} instances.
     */
    private final BetonQuestLoggerFactory loggerFactory;

    /**
     * Stores the {@link PlayerData}.
     */
    private final BetonQuest betonQuest;
    private final java.util.concurrent.atomic.AtomicLong loginSequence = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> logins = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Creates new listener, which will handle the data loading/saving.
     *
     * @param loggerFactory used for logger creation in ConversationResumer
     * @param betonQuest    the object to store and remove {@link PlayerData}
     */
    public JoinQuitListener(final BetonQuestLoggerFactory loggerFactory, final BetonQuest betonQuest) {
        this.loggerFactory = loggerFactory;
        this.betonQuest = betonQuest;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void playerPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != Result.ALLOWED) {
            return;
        }
        long attempt = loginSequence.incrementAndGet();
        synchronized (logins) { logins.put(event.getUniqueId(),attempt); }
        try {
            final Profile profile = PlayerConverter.getID(Bukkit.getOfflinePlayer(event.getUniqueId()));
            final PlayerData loaded = betonQuest.loadPlayerDataForLogin(profile);
            synchronized (logins) {
                if (!java.util.Objects.equals(logins.get(event.getUniqueId()),attempt)) throw new IllegalStateException("Quest login session changed");
                betonQuest.putPlayerData(profile, loaded);
            }
        } catch (final Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            loggerFactory.create(JoinQuitListener.class).error("Could not load quest data before login for " + event.getUniqueId(), failure);
            event.disallow(Result.KICK_OTHER, "任务数据暂时无法加载，请稍后重新连接。");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final OnlineProfile onlineProfile = PlayerConverter.getID(event.getPlayer());
        if (Bukkit.getPlayer(event.getPlayer().getUniqueId()) != event.getPlayer()) return;
        final PlayerData playerData = betonQuest.getPlayerData(onlineProfile).copyFor(onlineProfile);
        betonQuest.putPlayerData(onlineProfile,playerData);
        logins.remove(event.getPlayer().getUniqueId());
        playerData.startObjectives();
        GlobalObjectives.startAll(onlineProfile);
        checkResourcepack(event, onlineProfile);

        if (Journal.hasJournal(onlineProfile)) {
            playerData.getJournal().update();
        }
        if (playerData.getActiveConversation() != null) {
            new ConversationResumer(loggerFactory, onlineProfile, playerData.getActiveConversation());
        }
    }

    private void checkResourcepack(final PlayerJoinEvent event, final OnlineProfile onlineProfile) {
        if (!PaperLib.isPaper()) {
            return;
        }
        final PlayerResourcePackStatusEvent.Status resourcePackStatus = event.getPlayer().getResourcePackStatus();
        if (resourcePackStatus != null) {
            betonQuest.getPlayerObjectives(onlineProfile).stream()
                    .filter(objective -> objective instanceof ResourcePackObjective)
                    .map(objective -> (ResourcePackObjective) objective)
                    .forEach(objective -> objective.processObjective(onlineProfile, resourcePackStatus));
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        if (Bukkit.getPlayer(event.getPlayer().getUniqueId()) != event.getPlayer()) return;
        final OnlineProfile onlineProfile = PlayerConverter.getID(event.getPlayer());
        for (final Objective objective : betonQuest.getPlayerObjectives(onlineProfile)) {
            objective.pauseObjectiveForPlayer(onlineProfile);
        }
        if (Bukkit.getPlayer(event.getPlayer().getUniqueId()) == event.getPlayer()) {
            betonQuest.retainPlayerDataForReconnect(onlineProfile);
        }
    }
}
