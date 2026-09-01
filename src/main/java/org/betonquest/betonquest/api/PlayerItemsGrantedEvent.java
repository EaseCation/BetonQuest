package org.betonquest.betonquest.api;

import org.betonquest.betonquest.api.profiles.OnlineProfile;
import org.betonquest.betonquest.api.profiles.ProfileEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Fired after a BetonQuest event has granted item ownership to an online player.
 *
 * <p>The snapshots contain only quantities inserted into the Bukkit inventory or the BetonQuest backpack.
 * Quantities left as ground entities are intentionally excluded and can be observed when they are picked up.</p>
 */
public final class PlayerItemsGrantedEvent extends ProfileEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID grantId;
    private final Player player;
    private final List<ItemStack> items;

    public PlayerItemsGrantedEvent(final OnlineProfile profile, final UUID grantId, final List<ItemStack> items) {
        super(profile);
        this.grantId = Objects.requireNonNull(grantId, "grantId");
        this.player = profile.getPlayer();
        this.items = immutableClones(items);
    }

    public UUID getGrantId() {
        return grantId;
    }

    public Player getPlayer() {
        return player;
    }

    public List<ItemStack> getItems() {
        return immutableClones(items);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    private static List<ItemStack> immutableClones(final List<ItemStack> source) {
        Objects.requireNonNull(source, "items");
        return source.stream()
                .map(item -> Objects.requireNonNull(item, "item").clone())
                .toList();
    }
}
