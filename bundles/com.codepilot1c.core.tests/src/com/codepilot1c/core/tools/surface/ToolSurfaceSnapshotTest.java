package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.BuildAgentProfile;
import com.codepilot1c.core.agent.prompts.ToolPromptRenderer;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;
import com.codepilot1c.core.provider.config.ProviderType;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonParser;

import sun.misc.Unsafe;

public class ToolSurfaceSnapshotTest {

    private static final String EXPECTED_PROVIDER_NEUTRAL_DESCRIPTIONS = """
            read_file=Read an existing workspace file or line range. Bare Code.md resolves to current project root; otherwise use workspace-relative paths. Tool routing: prefer read/search before mutation, keep paths workspace-relative, and switch to EDT semantic tools for platform/model questions.
            edit_file=Edit existing workspace text files; create only project-root Code.md. Never edit .mdo/.form/.mxl/DCS/.cmi artifacts directly; use metadata/form/dcs/template/command interface tools. Tool routing: read before edit, patch the smallest necessary region, and do not mutate EDT metadata files directly when a semantic tool exists.
            create_metadata=Создаёт метаданный объект через BM API. Свойства: COMMON_MODULE — clientManagedApplication/server/global. DOCUMENT — useStandardCommands. CATALOG — hierarchical+hierarchyType. После создания запусти диагностику. Tool routing: enforce edt_validate_request -> validation_token -> mutation -> diagnostics. Do not skip validation or diagnose success without re-running diagnostics.
            qa_inspect=Читает состояние QA без изменений файлов: объясняет qa-config, проверяет окружение и ищет доступные шаги Vanessa Automation. Tool routing: follow the QA pipeline in order, treat generated context as ephemeral, and use steps search only as fallback support for scenario authoring.
            skill=Показывает доступные skills и загружает инструкцию выбранного skill по имени. Используй для подключения специализированного workflow.
            """; //$NON-NLS-1$

    private final LlmProviderConfigStore store = LlmProviderConfigStore.getInstance();

    @After
    public void cleanup() throws Exception {
        setStoreState(null, null);
    }

    @Test
    public void effectiveToolSurfaceIsIdenticalAcrossProviderSelections() throws Exception {
        ToolRegistry registry = createIsolatedRegistry();
        registerSnapshotTools(registry);
        String codePilot = surfaceSnapshotFor(registry, "backend", ProviderType.CODEPILOT_BACKEND, "backend-coder"); //$NON-NLS-1$ //$NON-NLS-2$
        String openAi = surfaceSnapshotFor(registry, "openai-local", ProviderType.OPENAI_COMPATIBLE, "gpt-5"); //$NON-NLS-1$ //$NON-NLS-2$
        String ollama = surfaceSnapshotFor(registry, "ollama", ProviderType.OLLAMA, "llama3.2"); //$NON-NLS-1$ //$NON-NLS-2$
        setStoreState(List.of(), null);
        String noProvider = surfaceSnapshot(registry);

        assertEquals(codePilot, openAi);
        assertEquals(codePilot, ollama);
        assertEquals(codePilot, noProvider);
        assertEquals(EXPECTED_PROVIDER_NEUTRAL_DESCRIPTIONS, descriptionSnapshot(registry));
    }

    @Test
    public void providerNeutralSurfaceStaysWithinFormerBackendEnvelope() throws Exception {
        ToolRegistry registry = createIsolatedRegistry();
        registerSnapshotTools(registry);
        List<ToolDefinition> definitions = registry.getToolDefinitions(
                registry.createRuntimeSurfaceContext(new BuildAgentProfile()));

        assertEquals(5, definitions.size());
        assertTrue(totalDescriptionChars(definitions) <= 1_600);
        assertTrue(totalSchemaChars(definitions) <= 3_500);

        List<LlmMessage> rendered = ToolPromptRenderer.applyToMessages(
                List.of(LlmMessage.system("BASE")), definitions); //$NON-NLS-1$
        assertTrue(rendered.get(0).getContent().length() <= 2_100);
    }

    private String surfaceSnapshotFor(ToolRegistry registry, String activeProviderId, ProviderType type, String model)
            throws Exception {
        setStoreState(List.of(configured(activeProviderId, type, model)), activeProviderId);
        return surfaceSnapshot(registry);
    }

    private String surfaceSnapshot(ToolRegistry registry) {
        List<ToolDefinition> definitions = registry.getToolDefinitions(
                registry.createRuntimeSurfaceContext(new BuildAgentProfile()));
        StringBuilder snapshot = new StringBuilder();
        for (ToolDefinition definition : definitions) {
            snapshot.append("name=").append(definition.getName()).append('\n'); //$NON-NLS-1$
            snapshot.append("description=").append(normalizeDescription(definition.getDescription())).append('\n'); //$NON-NLS-1$
            snapshot.append("schema=") //$NON-NLS-1$
                    .append(JsonParser.parseString(definition.getParametersSchema()).toString())
                    .append('\n');
        }
        return snapshot.toString();
    }

    private String descriptionSnapshot(ToolRegistry registry) {
        StringBuilder snapshot = new StringBuilder();
        List<ToolDefinition> definitions = registry.getToolDefinitions(
                registry.createRuntimeSurfaceContext(new BuildAgentProfile()));
        for (String name : List.of("read_file", "edit_file", "create_metadata", "qa_inspect", "skill")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            ToolDefinition definition = definitions.stream()
                    .filter(candidate -> name.equals(candidate.getName()))
                    .findFirst()
                    .orElseThrow();
            snapshot.append(name)
                    .append('=')
                    .append(normalizeDescription(definition.getDescription()))
                    .append('\n');
        }
        return snapshot.toString();
    }

    private String normalizeDescription(String description) {
        return description
                .replace('\n', ' ')
                .replaceAll("\\s+", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .trim();
    }

    private int totalDescriptionChars(List<ToolDefinition> definitions) {
        return definitions.stream()
                .map(ToolDefinition::getDescription)
                .mapToInt(String::length)
                .sum();
    }

    private int totalSchemaChars(List<ToolDefinition> definitions) {
        return definitions.stream()
                .map(ToolDefinition::getParametersSchema)
                .mapToInt(String::length)
                .sum();
    }

    private static void registerSnapshotTools(ToolRegistry registry) {
        registry.register(new SnapshotTool("read_file", "Read file contents with optional line ranges.")); //$NON-NLS-1$ //$NON-NLS-2$
        registry.register(new SnapshotTool("edit_file", "Edit an existing text file in place.")); //$NON-NLS-1$ //$NON-NLS-2$
        registry.register(new SnapshotTool(
                "create_metadata", "Создает новый объект метаданных 1С через EDT BM model и forceExport.")); //$NON-NLS-1$ //$NON-NLS-2$
        registry.register(new SnapshotTool(
                "qa_inspect", "Читает состояние QA без изменений файлов: объясняет qa-config, проверяет окружение и ищет доступные шаги Vanessa Automation.")); //$NON-NLS-1$ //$NON-NLS-2$
        registry.register(new SnapshotTool(
                "skill", "Показывает доступные skills и загружает инструкцию выбранного skill по имени. Используй для подключения специализированного workflow.")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void setStoreState(List<LlmProviderConfig> configs, String activeProviderId) throws Exception {
        Field configsField = LlmProviderConfigStore.class.getDeclaredField("cachedConfigs"); //$NON-NLS-1$
        configsField.setAccessible(true);
        configsField.set(store, configs);

        Field activeField = LlmProviderConfigStore.class.getDeclaredField("cachedActiveProviderId"); //$NON-NLS-1$
        activeField.setAccessible(true);
        activeField.set(store, activeProviderId);
    }

    private static LlmProviderConfig configured(String id, ProviderType type, String model) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setId(id);
        config.setName(id);
        config.setType(type);
        config.setBaseUrl("https://example.com/v1"); //$NON-NLS-1$
        config.setApiKey("key"); //$NON-NLS-1$
        config.setModel(model);
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
                case "qa_inspect" -> "diagnostics"; //$NON-NLS-1$ //$NON-NLS-2$
                default -> "general"; //$NON-NLS-1$
            };
        }

        @Override
        public String getSurfaceCategory() {
            return switch (name) {
                case "qa_inspect" -> "qa"; //$NON-NLS-1$ //$NON-NLS-2$
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
