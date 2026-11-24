package org.betonquest.betonquest.integration.observer;

import org.betonquest.betonquest.api.profiles.Profile;
import org.bukkit.Bukkit;

/** Optional telemetry facade; no observed result is an approval for quest execution. */
public final class QuestObserver {
    private QuestObserver() { }
    public static void task(Profile profile, String taskId, String action, long amount) {
        if (profile == null || !Bukkit.getPluginManager().isPluginEnabled("PlayerObserverPaper")) return;
        try { QuestObserverAdapter.task(profile, stable(taskId), action, amount); } catch (Throwable ignored) { }
    }
    public static void balance(Profile profile, String currency, String before, String after, String reasonId) {
        if (profile == null || !Bukkit.getPluginManager().isPluginEnabled("PlayerObserverPaper")) return;
        try { QuestObserverAdapter.balance(profile, stable(currency), before, after, stable(reasonId)); } catch (Throwable ignored) { }
    }
    private static String stable(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
