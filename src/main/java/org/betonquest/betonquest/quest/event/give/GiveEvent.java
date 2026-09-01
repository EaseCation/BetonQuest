package org.betonquest.betonquest.quest.event.give;

import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.Instruction.Item;
import org.betonquest.betonquest.api.PlayerItemsGrantedEvent;
import org.betonquest.betonquest.api.profiles.OnlineProfile;
import org.betonquest.betonquest.api.quest.event.online.OnlineEvent;
import org.betonquest.betonquest.exceptions.QuestRuntimeException;
import org.betonquest.betonquest.item.QuestItem;
import org.betonquest.betonquest.integration.observer.QuestObserver;
import org.betonquest.betonquest.quest.event.NotificationSender;
import org.betonquest.betonquest.utils.Utils;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Gives the player items.
 */
public class GiveEvent implements OnlineEvent {

    /**
     * The items to give.
     */
    private final Item[] questItems;

    /**
     * The notification sender to use when putting items into the player's inventory.
     */
    private final NotificationSender itemsGivenSender;

    /**
     * The notification sender to use when putting items into the player's backpack.
     */
    private final NotificationSender itemsInBackpackSender;

    /**
     * The notification sender to use when dropping items at the player's location.
     */
    private final NotificationSender itemsDroppedSender;

    /**
     * Whether to put the items to the player's backpack.
     */
    private final boolean backpack;

    /**
     * Create the give event.
     *
     * @param questItems            the items to give
     * @param itemsGivenSender      the notification sender when giving items
     * @param itemsInBackpackSender the notification sender when putting items into the backpack
     * @param itemsDroppedSender    the notification sender when dropping items
     * @param backpack              whether to put the items to the player's backpack
     */
    public GiveEvent(final Item[] questItems, final NotificationSender itemsGivenSender, final NotificationSender itemsInBackpackSender, final NotificationSender itemsDroppedSender, final boolean backpack) {
        this.questItems = Arrays.copyOf(questItems, questItems.length);
        this.itemsGivenSender = itemsGivenSender;
        this.itemsInBackpackSender = itemsInBackpackSender;
        this.itemsDroppedSender = itemsDroppedSender;
        this.backpack = backpack;
    }

    @Override
    public void execute(final OnlineProfile profile) throws QuestRuntimeException {
        final Player player = profile.getPlayer();
        int totalGiven = 0;
        final List<ItemStack> grantedItems = new ArrayList<>();
        for (final Item item : questItems) {
            final QuestItem questItem = item.getItem();
            final int amount = item.getAmount().getValue(profile).intValue();
            grantedItems.addAll(giveItems(profile, player, questItem, amount));
            final String questItemName = questItem.getName() == null
                    ? questItem.getMaterial().toString().toLowerCase(Locale.ROOT).replace("_", " ")
                    : questItem.getName();
            itemsGivenSender.sendNotification(profile, questItemName, String.valueOf(amount));
            totalGiven += Math.max(0, amount);
        }
        if (!grantedItems.isEmpty()) {
            BetonQuest.getInstance().callSyncBukkitEvent(
                    new PlayerItemsGrantedEvent(profile, UUID.randomUUID(), grantedItems));
        }
        QuestObserver.task(profile, "give", "GIVE_ITEMS", totalGiven);
    }

    @SuppressWarnings("PMD.CognitiveComplexity")
    private List<ItemStack> giveItems(final OnlineProfile profile, final Player player, final QuestItem questItem, final int totalAmount)
            throws QuestRuntimeException {
        int amount = totalAmount;
        final List<ItemStack> grantedItems = new ArrayList<>();
        while (amount > 0) {
            final ItemStack itemStackTemplate = questItem.generate(1, profile);
            final int stackSize = Math.min(amount, itemStackTemplate.getMaxStackSize());
            if (stackSize <= 0) {
                throw new QuestRuntimeException("Item stack size is 0 or less!");
            }
            boolean fullInventory = false;
            ItemStack itemStack = itemStackTemplate.clone();
            itemStack.setAmount(stackSize);
            if (!backpack) {
                final ItemStack leftItems = giveToInventory(player, itemStack);
                final int insertedAmount = stackSize - (leftItems == null ? 0 : leftItems.getAmount());
                if (insertedAmount > 0) {
                    grantedItems.add(withAmount(itemStackTemplate, insertedAmount));
                }
                if (leftItems == null) {
                    amount -= stackSize;
                    continue;
                } else {
                    itemStack = leftItems;
                    fullInventory = true;
                }
            }
            if (Utils.isQuestItem(itemStack)) {
                giveToBackpack(profile, itemStack);
                grantedItems.add(itemStack.clone());
                if (fullInventory) {
                    itemsInBackpackSender.sendNotification(profile);
                }
            } else {
                dropItems(player, itemStack);
                itemsDroppedSender.sendNotification(profile);
            }
            amount -= stackSize;
        }
        return List.copyOf(grantedItems);
    }

    private ItemStack withAmount(final ItemStack source, final int amount) {
        final ItemStack snapshot = source.clone();
        snapshot.setAmount(amount);
        return snapshot;
    }

    /**
     * Gives the item stack to the player. Returns null if all items of the stack were given successfully,
     * otherwise returns the items that were not given.
     *
     * @param player    the player to give the item to
     * @param itemStack the items to give
     * @return the items that could not be given
     */
    @Nullable
    private ItemStack giveToInventory(final Player player, final ItemStack itemStack) {
        return player.getInventory().addItem(itemStack).values().stream().findAny().orElse(null);
    }

    /**
     * Gives the item to the player's backpack.
     *
     * @param profile   the player to give the item to
     * @param itemStack the item to give
     */
    private void giveToBackpack(final OnlineProfile profile, final ItemStack itemStack) {
        BetonQuest.getInstance().getPlayerData(profile).addItem(itemStack, itemStack.getAmount());
    }

    /**
     * Drops the item on the ground.
     *
     * @param player    the player to drop the item for
     * @param itemStack the item to drop
     */
    private void dropItems(final Player player, final ItemStack itemStack) {
        player.getWorld().dropItem(player.getLocation(), itemStack);
    }
}
