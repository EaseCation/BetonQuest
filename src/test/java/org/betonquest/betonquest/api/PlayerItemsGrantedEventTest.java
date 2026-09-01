package org.betonquest.betonquest.api;

import org.betonquest.betonquest.api.profiles.OnlineProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerItemsGrantedEventTest {

    @Test
    void itemSnapshotsAreDefensiveAndImmutable() {
        ItemStack source = new ItemStack(Material.DIAMOND, 7);
        List<ItemStack> sourceItems = new ArrayList<>(List.of(source));
        PlayerItemsGrantedEvent event = new PlayerItemsGrantedEvent(profile(), UUID.randomUUID(), sourceItems);

        source.setAmount(1);
        sourceItems.clear();
        List<ItemStack> firstRead = event.getItems();
        firstRead.get(0).setAmount(2);

        assertEquals(7, event.getItems().get(0).getAmount());
        assertThrows(UnsupportedOperationException.class,
                () -> event.getItems().add(new ItemStack(Material.COAL, 1)));
    }

    private static OnlineProfile profile() {
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        return (OnlineProfile) Proxy.newProxyInstance(
                OnlineProfile.class.getClassLoader(),
                new Class<?>[]{OnlineProfile.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPlayer")) return player;
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
