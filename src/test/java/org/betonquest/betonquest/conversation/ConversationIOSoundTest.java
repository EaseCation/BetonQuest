package org.betonquest.betonquest.conversation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationIOSoundTest {

    @ParameterizedTest
    @ValueSource(strings = {"start", "end"})
    void existingRenderersKeepGlobalSounds(final String cue) {
        assertFalse(new DefaultIO().playConversationSound(cue));
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "end"})
    void characterRendererMayHandleOrSilenceSound(final String cue) {
        final ConversationIO characterIO = new DefaultIO() {
            @Override
            public boolean playConversationSound(final String soundName) {
                return true;
            }
        };
        assertTrue(characterIO.playConversationSound(cue));
    }

    private static class DefaultIO implements ConversationIO {
        @Override
        public void setNpcResponse(final String npcName, final String response) { }

        @Override
        public void addPlayerOption(final String option) { }

        @Override
        public void display() { }

        @Override
        public void clear() { }

        @Override
        public void end() { }
    }
}
