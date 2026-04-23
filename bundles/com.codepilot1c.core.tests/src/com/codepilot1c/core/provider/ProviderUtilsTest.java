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
        assertTrue(capabilities.supportsImageInput());
        assertTrue(capabilities.supportsDocumentInput());
    }

    @Test
    public void dynamicProviderExposesCapabilitiesFromConfig() {
        DynamicLlmProvider provider = new DynamicLlmProvider(configured(ProviderType.CODEPILOT_BACKEND));

        assertTrue(ProviderUtils.isCodePilotBackend(provider));
        assertTrue(ProviderUtils.supportsBackendOptimizations(provider));
    }

    @Test
    public void anthropicConfigPublishesImageAttachmentCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(configured(ProviderType.ANTHROPIC));

        assertTrue(capabilities.supportsImageInput());
        assertTrue(capabilities.supportsAttachmentMetadata());
    }

    @Test
    public void qwenVlBackendPublishesVisionCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.CODEPILOT_BACKEND, "qwen2.5-vl-72b")); //$NON-NLS-1$

        assertTrue(capabilities.isQwenNative());
        assertTrue(capabilities.supportsImageInput());
    }

    @Test
    public void qwenCoderBackendDoesNotPublishVisionCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.CODEPILOT_BACKEND, "qwen3-coder-plus")); //$NON-NLS-1$

        assertTrue(capabilities.isQwenNative());
        assertTrue(capabilities.supportsImageInput());
    }

    @Test
    public void openAiCompatibleVisionModelPublishesImageCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.OPENAI_COMPATIBLE, "gpt-4o")); //$NON-NLS-1$

        assertTrue(capabilities.supportsImageInput());
        assertTrue(capabilities.supportsAttachmentMetadata());
    }

    @Test
    public void ollamaConfigPublishesMultimodalCapabilities() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(configured(ProviderType.OLLAMA, "llama3.2-vision")); //$NON-NLS-1$

        assertTrue(capabilities.supportsImageInput());
        assertTrue(capabilities.supportsDocumentInput());
        assertTrue(capabilities.supportsAttachmentMetadata());
    }

    private static LlmProviderConfig configured(ProviderType type) {
        return configured(type, "auto"); //$NON-NLS-1$
    }

    private static LlmProviderConfig configured(ProviderType type, String model) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setId("test-" + type.name()); //$NON-NLS-1$
        config.setName("test-" + type.name()); //$NON-NLS-1$
        config.setType(type);
        config.setBaseUrl("https://example.com/v1"); //$NON-NLS-1$
        config.setApiKey("key"); //$NON-NLS-1$
        config.setModel(model);
        return config;
    }
}
