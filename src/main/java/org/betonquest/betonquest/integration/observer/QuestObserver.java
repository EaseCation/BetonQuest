package org.betonquest.betonquest.integration.observer;

import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.api.profiles.Profile;
import org.bukkit.Bukkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Reflection-only optional Observer adapter; every failure is deliberately ignored. */
public final class QuestObserver {
    private QuestObserver() {
    }

    public static void task(final Profile profile, final String taskId, final String action, final long amount) {
        emit(profile, "task", action, "Task",
                new Class<?>[]{String.class, String.class, String.class, long.class},
                new Object[]{stable(taskId), "", stable(action), amount});
    }

    public static void balance(final Profile profile, final String currency, final String before,
                               final String after, final String reasonId) {
        emit(profile, "economy", "balance_change", "Balance",
                new Class<?>[]{String.class, String.class, String.class, String.class},
                new Object[]{stable(currency), before, after, stable(reasonId)});
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void emit(final Profile profile, final String domain, final String action,
                             final String deltaType, final Class<?>[] deltaSignature, final Object[] deltaValues) {
        if (profile == null) return;
        try {
            final ClassLoader loader = QuestObserver.class.getClassLoader();
            final Class<?> observerType = Class.forName(
                    "net.easecation.playerobserver.api.PlayerObserver", false, loader);
            final Object observer = Bukkit.getServicesManager().load((Class) observerType);
            if (observer == null) return;

            final Class<?> observationType = Class.forName(
                    "net.easecation.playerobserver.api.Observation", false, loader);
            final Object builder = observationType
                    .getMethod("builder", java.util.UUID.class, String.class, String.class)
                    .invoke(null, profile.getProfileUUID(), domain, action);
            final Class<?> actorType = Class.forName(
                    "net.easecation.playerobserver.api.ActorType", false, loader);
            final Class<?> confidenceType = Class.forName(
                    "net.easecation.playerobserver.api.Confidence", false, loader);
            final Class<?> deltaInterface = Class.forName(
                    "net.easecation.playerobserver.api.ObservationDelta", false, loader);
            final Class<?> deltaClass = Class.forName(
                    "net.easecation.playerobserver.api.ObservationDelta$" + deltaType, false, loader);
            final Constructor<?> deltaConstructor = deltaClass.getConstructor(deltaSignature);
            final Object delta = deltaConstructor.newInstance(deltaValues);

            invoke(builder, "actor", actorType, Enum.valueOf((Class<Enum>) actorType, "PLAYER"));
            invoke(builder, "outcome", String.class, "SUCCESS");
            invoke(builder, "source", String.class, "BetonQuest", String.class,
                    BetonQuest.getInstance().getDescription().getVersion());
            invoke(builder, "confidence", confidenceType, Enum.valueOf((Class<Enum>) confidenceType, "EXACT"));
            invoke(builder, "delta", deltaInterface, delta);
            final Object observation = builder.getClass().getMethod("build").invoke(builder);
            observerType.getMethod("emit", observationType).invoke(observer, observation);
        } catch (Throwable ignored) {
            // Observer is an audit copy. Missing APIs, provider failures and reflection drift never affect quests.
        }
    }

    private static void invoke(final Object target, final String method, final Class<?> type, final Object value)
            throws ReflectiveOperationException {
        target.getClass().getMethod(method, type).invoke(target, value);
    }

    private static void invoke(final Object target, final String method,
                               final Class<?> firstType, final Object first,
                               final Class<?> secondType, final Object second)
            throws ReflectiveOperationException {
        target.getClass().getMethod(method, firstType, secondType).invoke(target, first, second);
    }

    private static String stable(final String value) {
        if (value == null || value.isBlank()) return "unknown";
        final String normalized = value.replaceAll("[^A-Za-z0-9_.:+-]", "_");
        return normalized.substring(0, Math.min(128, normalized.length()));
    }
}
