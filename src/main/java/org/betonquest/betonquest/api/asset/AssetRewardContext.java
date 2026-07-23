package org.betonquest.betonquest.api.asset;

import org.betonquest.betonquest.exceptions.QuestRuntimeException;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;

/**
 * Collects asset rewards while a reliable external fact is being completed.
 *
 * <p>Implementations stage changes only. The caller commits the complete batch to its authority after all quest
 * events have finished, so item and money rewards never mutate Bukkit inventory or Vault independently.</p>
 */
public interface AssetRewardContext {
    /** Stage a complete item stack for the authoritative player inventory. */
    void give(ItemStack item) throws QuestRuntimeException;

    /** Exact balance including changes already staged in this context. */
    BigDecimal balance();

    /** Stage an exact balance delta and return the new projected balance. */
    BigDecimal adjustBalance(BigDecimal delta) throws QuestRuntimeException;
}
