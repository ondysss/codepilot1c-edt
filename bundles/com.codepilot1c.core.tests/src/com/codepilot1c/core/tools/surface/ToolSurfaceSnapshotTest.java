package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.BuildAgentProfile;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;
import com.codepilot1c.core.provider.config.ProviderType;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolRegistry;
import com.google.gson.Gson;

import sun.misc.Unsafe;

public class ToolSurfaceSnapshotTest {

    private final LlmProviderConfigStore store = LlmProviderConfigStore.getInstance();

    @After
    public void cleanup() throws Exception {
        setStoreState(null, null);
    }

    @Test
    public void effectiveToolSurfaceSnapshotsStayStableAcrossProviderSelections() throws Exception {
        ToolRegistry registry = createIsolatedRegistry();
        registerSnapshotTools(registry);
        String nonBackend = snapshotFor(registry, "openai-local", ProviderType.OPENAI_COMPATIBLE, "build"); //$NON-NLS-1$ //$NON-NLS-2$
        String backend = snapshotFor(registry, "backend", ProviderType.CODEPILOT_BACKEND, "build"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("""
                provider=openai-local
                profile=build
                read_file=Read file contents with optional line ranges.
                edit_file=Edit an existing text file in place.
                create_metadata=Создает новый объект метаданных 1С через EDT BM model и forceExport.
                qa_status=Проверяет QA окружение и эффективный qa-config для выбранного проекта/feature.
                skill=Показывает доступные skills и загружает их инструкции по имени. Используй для lazy skill discovery вместо угадывания skill body.
                """, nonBackend);

        assertEquals("""
                provider=backend
                profile=build
                read_file=Read an existing workspace file or a 1-based line range. Use it for exact source inspection after discovery and keep paths workspace-relative. Qwen routing: prefer read/search before mutation, keep paths workspace-relative, and switch to EDT semantic tools for platform/model questions.
                edit_file=Edit an existing workspace file in place via full replace, targeted search/replace, or SEARCH/REPLACE blocks. Do not use it to create files or mutate EDT metadata descriptors unless an explicit emergency override is intended. Qwen routing: read before edit, patch the smallest necessary region, and do not mutate EDT metadata files directly when a semantic tool exists.
                create_metadata=Создает новый объект метаданных 1С через EDT BM model и forceExport. Qwen routing: enforce edt_validate_request -> validation_token -> mutation -> diagnostics. Do not skip validation or diagnose success without re-running diagnostics.
                qa_status=Inspect the QA environment, effective configuration, and runtime readiness for the selected project or feature. Qwen routing: follow the QA pipeline in order, treat generated context as ephemeral, and use steps search only as fallback support for scenario authoring.
                skill=Показывает доступные skills и загружает их инструкции по имени. Используй для lazy skill discovery вместо угадывания skill body.
                """, backend);
    }

    private String snapshotFor(ToolRegistry registry, String activeProviderId, ProviderType type, String profileId)
            throws Exception {
        setStoreState(List.of(configured(activeProviderId, type)), activeProviderId);
        List<ToolDefinition> definitions = registry.getToolDefinitions(
                registry.createRuntimeSurfaceContext(new BuildAgentProfile()));
        return normalize("""
                provider=%s
                profile=%s
                read_file=%s
                edit_file=%s
                create_metadata=%s
                qa_status=%s
                skill=%s
                """.formatted(
                activeProviderId,
                profileId,
                descriptionOf(definitions, "read_file"), //$NON-NLS-1$
                descriptionOf(definitions, "edit_file"), //$NON-NLS-1$
                descriptionOf(definitions, "create_metadata"), //$NON-NLS-1$
                descriptionOf(definitions, "qa_status"), //$NON-NLS-1$
                descriptionOf(definitions, "skill"))); //$NON-NLS-1$
    }

    private String descriptionOf(List<ToolDefinition> definitions, String name) {
        return definitions.stream()
                .filter(definition -> name.equals(definition.getName()))
                .findFirst()
                .orElseThrow()
                .getDescription()
                .replace('\n', ' ')
                .replaceAll("\\s+", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .trim();
    }

    private String normalize(String value) {
        return value.stripIndent();
    }

    private static void registerSnapshotTools(ToolRegistry registry) {
        registry.register(new SnapshotTool("read_file", "Read file contents with optional line ranges.")); //$NON-NLS-1$ //$NON-NLS-2$
        registry.register(new SnapshotTool("edit_file", "Edit an existing text file in place.")); //$NON-NLS-1$ //$NON-NLS-2$
        registry.register(new SnapshotTool(
                "create_metadata", "Создает новый объект метаданных 1С через EDT BM model и forceExport.")); //$NON-NLS-1$ //$NON-NLS-2$
        registry.register(new SnapshotTool(
                "qa_status", "Проверяет QA окружение и эффективный qa-config для выбранного проекта/feature.")); //$NON-NLS-1$ //$NON-NLS-2$
        registry.register(new SnapshotTool(
                "skill", "Показывает доступные skills и загружает их инструкции по имени. Используй для lazy skill discovery вместо угадывания skill body.")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void setStoreState(List<LlmProviderConfig> configs, String activeProviderId) throws Exception {
        Field configsField = LlmProviderConfigStore.class.getDeclaredField("cachedConfigs"); //$NON-NLS-1$
        configsField.setAccessible(true);
        configsField.set(store, configs);

        Field activeField = LlmProviderConfigStore.class.getDeclaredField("cachedActiveProviderId"); //$NON-NLS-1$
        activeField.setAccessible(true);
        activeField.set(store, activeProviderId);
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

    private static ToolRegistry createIsolatedRegistry() throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setRegistryField(registry, "tools", new HashMap<String, ITool>()); //$NON-NLS-1$
        setRegistryField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setRegistryField(registry, "gson", new Gson()); //$NON-NLS-1$
        setRegistryField(registry, "augmentor", ToolSurfaceAugmentor.defaultAugmentor()); //$NON-NLS-1$
        return registry;
    }

    private static void setRegistryField(ToolRegistry registry, String name, Object value) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(registry, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class SnapshotTool implements ITool {
        private final String name;
        private final String description;

        private SnapshotTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String getCategory() {
            return switch (name) {
                case "read_file" -> "file"; //$NON-NLS-1$ //$NON-NLS-2$
                case "edit_file" -> "file"; //$NON-NLS-1$ //$NON-NLS-2$
                case "create_metadata" -> "metadata"; //$NON-NLS-1$ //$NON-NLS-2$
                case "qa_status" -> "diagnostics"; //$NON-NLS-1$ //$NON-NLS-2$
                default -> "general"; //$NON-NLS-1$
            };
        }

        @Override
        public String getSurfaceCategory() {
            return switch (name) {
                case "qa_status" -> "qa"; //$NON-NLS-1$ //$NON-NLS-2$
                default -> ""; //$NON-NLS-1$
            };
        }

        @Override
        public boolean isMutating() {
            return "edit_file".equals(name) || "create_metadata".equals(name); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }
}
