package org.betonquest.betonquest.compatibility.vault.event;

import net.milkbowl.vault.economy.Economy;
import org.betonquest.betonquest.api.profiles.Profile;
import org.betonquest.betonquest.api.quest.event.Event;
import org.betonquest.betonquest.exceptions.QuestRuntimeException;
import org.betonquest.betonquest.instruction.variable.VariableNumber;
import org.betonquest.betonquest.integration.observer.QuestObserver;
import org.betonquest.betonquest.quest.event.IngameNotificationSender;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;

/**
 * Modifies player's balance.
 */
public class MoneyEvent implements Event {
    /**
     * Economy where the balance will be modified.
     */
    private final Economy economy;

    /**
     * Amount to modify the balance.
     */
    private final VariableNumber amount;

    /**
     * If the current balance should be multiplied with the amount.
     */
    private final boolean multi;

    /**
     * Notification wrapper if the player should get a message when getting money.
     */
    @Nullable
    private final IngameNotificationSender givenSender;

    /**
     * Notification wrapper if the player should get a message when loosing money.
     */
    @Nullable
    private final IngameNotificationSender takenSender;

    /**
     * Create a new vault money event.
     *
     * @param economy     the economy where the balance will be modified
     * @param amount      the amount to modify the balance
     * @param multi       if the current balance should be multiplied with the amount
     * @param givenSender the notification wrapper if the player should get a message when getting money
     * @param takenSender the notification wrapper if the player should get a message when loosing money
     */
    public MoneyEvent(final Economy economy, final VariableNumber amount, final boolean multi,
                      @Nullable final IngameNotificationSender givenSender, @Nullable final IngameNotificationSender takenSender) {
        this.economy = economy;
        this.amount = amount;
        this.multi = multi;
        this.givenSender = givenSender;
        this.takenSender = takenSender;
    }

    @Override
    public void execute(final Profile profile) throws QuestRuntimeException {
        final OfflinePlayer player = profile.getPlayer();
        final double current = economy.getBalance(player);
        final double target;
        if (multi) {
            target = current * amount.getValue(profile).doubleValue();
        } else {
            target = current + amount.getValue(profile).doubleValue();
        }

        final double difference = target - current;
        final net.milkbowl.vault.economy.EconomyResponse response;
        if (difference > 0) {
            response = economy.depositPlayer(player, difference);
        } else if (difference < 0) {
            response = economy.withdrawPlayer(player, -difference);
        } else { return; }
        if (!response.transactionSuccess()) throw new QuestRuntimeException("Economy transaction failed: " + response.errorMessage);
        final double confirmed = economy.getBalance(player);
        QuestObserver.balance(profile, economy.getName(),
                Double.toString(current), Double.toString(confirmed), "betonquest_money_event");
        notify(profile, difference, economy.currencyNamePlural());
    }

    private void notify(final Profile profile, final double difference, final String currencyName) {
        final DecimalFormat decimalFormat = new DecimalFormat("#.00");
        if (difference > 0 && givenSender != null) {
            givenSender.sendNotification(profile, decimalFormat.format(difference), currencyName);
        } else if (difference < 0 && takenSender != null) {
            takenSender.sendNotification(profile, decimalFormat.format(difference), currencyName);
        }
    }
}
