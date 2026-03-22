package com.codepilot1c.core.agent.prompts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;
import com.codepilot1c.core.provider.config.ProviderType;

public class PromptSnapshotTest {

    private final LlmProviderConfigStore store = LlmProviderConfigStore.getInstance();

    @After
    public void cleanup() throws Exception {
        setStoreState(null, null);
    }

    @Test
    public void effectivePromptSnapshotsStayStableAcrossProviderSelections() throws Exception {
        assertEquals("""
                backend=false
                build_has_code_md=false
                build_has_skill_section=false
                build_has_task=false
                orchestrator_has_delegate=false
                plan_has_goal=true
                plan_has_task=false
                explore_has_output=true
                explore_has_task=false
                subagent_has_role=true
                """, snapshotFor(false));

        assertEquals("""
                backend=true
                build_has_code_md=false
                build_has_skill_section=false
                build_has_task=true
                orchestrator_has_delegate=true
                plan_has_goal=true
                plan_has_task=true
                explore_has_output=true
                explore_has_task=true
                subagent_has_role=true
                """, snapshotFor(true));
    }

    private String snapshotFor(boolean backendSelected) throws Exception {
        String providerId = backendSelected ? "backend" : "openai"; //$NON-NLS-1$ //$NON-NLS-2$
        ProviderType type = backendSelected ? ProviderType.CODEPILOT_BACKEND : ProviderType.OPENAI_COMPATIBLE;
        setStoreState(List.of(configured(providerId, type)), providerId);

        String build = SystemPromptAssembler.getInstance().assemble(
                AgentPromptTemplates.buildBuildPrompt(),
                null,
                "build", //$NON-NLS-1$
                List.of());
        String plan = SystemPromptAssembler.getInstance().assemble(
                AgentPromptTemplates.buildPlanPrompt(),
                null,
                "plan", //$NON-NLS-1$
                List.of());
        String orchestrator = SystemPromptAssembler.getInstance().assemble(
                AgentPromptTemplates.buildOrchestratorPrompt(),
                null,
                "orchestrator", //$NON-NLS-1$
                List.of());
        String explore = SystemPromptAssembler.getInstance().assemble(
                AgentPromptTemplates.buildExplorePrompt(),
                null,
                "explore", //$NON-NLS-1$
                List.of());
        String subagent = SystemPromptAssembler.getInstance().assemble(
                AgentPromptTemplates.buildSubagentPrompt("build", "desc", false), //$NON-NLS-1$ //$NON-NLS-2$
                null,
                "subagent", //$NON-NLS-1$
                List.of());

        return """
                backend=%s
                build_has_code_md=%s
                build_has_skill_section=%s
                build_has_task=%s
                orchestrator_has_delegate=%s
                plan_has_goal=%s
                plan_has_task=%s
                explore_has_output=%s
                explore_has_task=%s
                subagent_has_role=%s
                """.formatted(
                backendSelected,
                build.contains("Layered Context: Code.md"), //$NON-NLS-1$
                build.contains("Loaded Skills"), //$NON-NLS-1$
                build.contains("task"), //$NON-NLS-1$
                orchestrator.contains("delegate_to_agent"), //$NON-NLS-1$
                plan.contains("## Цель"), //$NON-NLS-1$
                plan.contains("task"), //$NON-NLS-1$
                explore.contains("## Формат ответа"), //$NON-NLS-1$
                explore.contains("task"), //$NON-NLS-1$
                subagent.contains("## Роль")) //$NON-NLS-1$
                .stripIndent();
    }

    @Test
    public void buildPromptIncludesSingleMethodStopRuleForBslAnalysis() {
        String build = AgentPromptTemplates.buildBuildPrompt();
        assertTrue(build.contains("не вызывай bsl_analyze_method повторно")); //$NON-NLS-1$
        assertTrue(build.contains("заверши ответ после первого успешного анализа")); //$NON-NLS-1$
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
}
