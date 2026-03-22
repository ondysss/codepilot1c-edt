package com.codepilot1c.core.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.codepilot1c.core.provider.config.DynamicLlmProvider;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;
import com.codepilot1c.core.provider.config.ProviderType;

public class LlmProviderRegistryTest {

    private final LlmProviderConfigStore store = LlmProviderConfigStore.getInstance();

    @After
    public void resetStore() throws Exception {
        setField(store, "cachedConfigs", null); //$NON-NLS-1$
        setField(store, "cachedActiveProviderId", null); //$NON-NLS-1$
    }

    @Test
    public void skipsBackendProviderWhenNoExplicitSelectionIsStored() throws Exception {
        LlmProviderConfig backend = configured("backend-dynamic", ProviderType.CODEPILOT_BACKEND); //$NON-NLS-1$
        LlmProviderConfig openAi = configured("openai", ProviderType.OPENAI_COMPATIBLE); //$NON-NLS-1$
        setField(store, "cachedConfigs", List.of(backend, openAi)); //$NON-NLS-1$
        setField(store, "cachedActiveProviderId", ""); //$NON-NLS-1$ //$NON-NLS-2$

        LlmProviderRegistry registry = newRegistry(store);
        dynamicProviders(registry).put(backend.getId(), new DynamicLlmProvider(backend));
        dynamicProviders(registry).put(openAi.getId(), new DynamicLlmProvider(openAi));

        ILlmProvider active = registry.getActiveProvider();

        assertNotNull(active);
        assertEquals("openai", active.getId()); //$NON-NLS-1$
    }

    @Test
    public void returnsNullWhenOnlyBackendDynamicProviderExistsWithoutExplicitSelection() throws Exception {
        LlmProviderConfig backend = configured("backend-dynamic", ProviderType.CODEPILOT_BACKEND); //$NON-NLS-1$
        setField(store, "cachedConfigs", List.of(backend)); //$NON-NLS-1$
        setField(store, "cachedActiveProviderId", ""); //$NON-NLS-1$ //$NON-NLS-2$

        LlmProviderRegistry registry = newRegistry(store);
        dynamicProviders(registry).put(backend.getId(), new DynamicLlmProvider(backend));

        assertNull(registry.getActiveProvider());
    }

    private static LlmProviderRegistry newRegistry(LlmProviderConfigStore store) throws Exception {
        Constructor<LlmProviderRegistry> constructor = LlmProviderRegistry.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        LlmProviderRegistry registry = constructor.newInstance();
        setField(registry, "configStore", store); //$NON-NLS-1$
        setField(registry, "initialized", true); //$NON-NLS-1$
        return registry;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, DynamicLlmProvider> dynamicProviders(LlmProviderRegistry registry) throws Exception {
        Field field = LlmProviderRegistry.class.getDeclaredField("dynamicProviders"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Map<String, DynamicLlmProvider>) field.get(registry);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
