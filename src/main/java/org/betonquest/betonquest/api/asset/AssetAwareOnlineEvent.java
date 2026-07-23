package org.betonquest.betonquest.api.asset;

import org.betonquest.betonquest.api.profiles.Profile;
import org.betonquest.betonquest.exceptions.QuestRuntimeException;

/** Online event that can stage its asset effect even if the player disconnected after the source fact committed. */
public interface AssetAwareOnlineEvent {
    void executeAsset(Profile profile, AssetRewardContext context) throws QuestRuntimeException;
}
