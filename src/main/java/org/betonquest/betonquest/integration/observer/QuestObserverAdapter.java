package org.betonquest.betonquest.integration.observer;

import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.api.profiles.Profile;
import org.bukkit.Bukkit;
import net.easecation.playerobserver.api.*;

/** Loaded only by the enabled optional facade; all observation failures remain fail-open. */
final class QuestObserverAdapter {
    private QuestObserverAdapter() { }
    static void task(Profile profile, String taskId, String action, long amount) {
        emit(profile, "task", action, new ObservationDelta.Task(taskId, "", action, amount));
    }
    static void balance(Profile profile, String currency, String before, String after, String reason) {
        emit(profile, "economy", "balance_change", new ObservationDelta.Balance(currency, before, after, reason));
    }
    private static void emit(Profile profile, String domain, String action, ObservationDelta delta) {
        Observers.emit(() -> Bukkit.getServicesManager().load(PlayerObserver.class),
            Observation.builder(profile.getProfileUUID(), domain, action).actor(ActorType.PLAYER).outcome("SUCCESS")
                .source("BetonQuest", BetonQuest.getInstance().getDescription().getVersion()).confidence(Confidence.EXACT)
                .delta(delta).build());
    }
}
