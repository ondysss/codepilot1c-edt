package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class LlmProviderConfigStoreSanitizationTest {

    @Test
    public void stripsReservedBackendConfigAndClearsMatchingActiveSelection() {
        LlmProviderConfig reserved = configured("backend", ProviderType.CODEPILOT_BACKEND); //$NON-NLS-1$
        LlmProviderConfig user = configured("openai", ProviderType.OPENAI_COMPATIBLE); //$NON-NLS-1$

        LlmProviderConfigStore.LoadedState state = LlmProviderConfigStore.sanitizeLoadedState(
                List.of(reserved, user),
                "backend"); //$NON-NLS-1$

        assertEquals(1, state.configs().size());
        assertEquals("openai", state.configs().get(0).getId()); //$NON-NLS-1$
        assertEquals("", state.activeProviderId()); //$NON-NLS-1$
    }

    @Test
    public void keepsExplicitBackendSelectionWhenNoReservedConfigWasPersisted() {
        LlmProviderConfig user = configured("openai", ProviderType.OPENAI_COMPATIBLE); //$NON-NLS-1$

        LlmProviderConfigStore.LoadedState state = LlmProviderConfigStore.sanitizeLoadedState(
                List.of(user),
                "backend"); //$NON-NLS-1$

        assertEquals(1, state.configs().size());
        assertEquals("backend", state.activeProviderId()); //$NON-NLS-1$
    }

    @Test
    public void reservedIdDetectionMatchesBackendIdOnly() {
        assertTrue(LlmProviderConfigStore.isReservedId("backend")); //$NON-NLS-1$
        assertFalse(LlmProviderConfigStore.isReservedId("backend-copy")); //$NON-NLS-1$
        assertFalse(LlmProviderConfigStore.isReservedId("openai")); //$NON-NLS-1$
    }

    private static LlmProviderConfig configured(String id, ProviderType type) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setId(id);
        config.setName(id);
        config.setType(type);
        config.setBaseUrl("https://example.com/v1"); //$NON-NLS-1$
        config.setApiKey("key"); //$NON-NLS-1$
        config.setModel("model"); //$NON-NLS-1$
        return config;
    }
}
