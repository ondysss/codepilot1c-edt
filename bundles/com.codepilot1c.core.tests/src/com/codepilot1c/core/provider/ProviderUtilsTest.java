package com.codepilot1c.core.provider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.provider.config.DynamicLlmProvider;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.ProviderType;

public class ProviderUtilsTest {

    @Test
    public void codePilotBackendConfigPublishesBackendCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(configured(ProviderType.CODEPILOT_BACKEND));

        assertTrue(capabilities.isCodePilotBackend());
        assertTrue(capabilities.supportsBackendOptimizations());
        assertTrue(capabilities.supportsPromptCacheHeaders());
        assertTrue(capabilities.supportsResolvedModel());
    }

    @Test
    public void genericOpenAiConfigDoesNotPublishBackendCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(configured(ProviderType.OPENAI_COMPATIBLE));

        assertFalse(capabilities.isCodePilotBackend());
        assertFalse(capabilities.supportsBackendOptimizations());
        assertFalse(capabilities.supportsPromptCacheHeaders());
        assertFalse(capabilities.supportsResolvedModel());
    }

    @Test
    public void dynamicProviderExposesCapabilitiesFromConfig() {
        DynamicLlmProvider provider = new DynamicLlmProvider(configured(ProviderType.CODEPILOT_BACKEND));

        assertTrue(ProviderUtils.isCodePilotBackend(provider));
        assertTrue(ProviderUtils.supportsBackendOptimizations(provider));
    }

    private static LlmProviderConfig configured(ProviderType type) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setId("test-" + type.name()); //$NON-NLS-1$
        config.setName("test-" + type.name()); //$NON-NLS-1$
        config.setType(type);
        config.setBaseUrl("https://example.com/v1"); //$NON-NLS-1$
        config.setApiKey("key"); //$NON-NLS-1$
        config.setModel("auto"); //$NON-NLS-1$
        return config;
    }
}
