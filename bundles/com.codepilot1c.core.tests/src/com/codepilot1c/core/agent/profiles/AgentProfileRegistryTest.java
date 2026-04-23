package com.codepilot1c.core.agent.profiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

/**
 * Tests for {@link AgentProfileRegistry} and profile gate enforcement.
 */
public class AgentProfileRegistryTest {

    @Test
    public void exploreProfileContainsOnlyWhitelistedTools() {
        AgentProfile explore = new ExploreAgentProfile();
        Set<String> tools = explore.getAllowedTools();

        assertNotNull("Explore profile must define allowed tools", tools); //$NON-NLS-1$
        assertFalse("Explore profile must not be empty", tools.isEmpty()); //$NON-NLS-1$
        assertTrue("Explore must include read_file", tools.contains("read_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Explore must include glob", tools.contains("glob")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Explore must include grep", tools.contains("grep")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Explore must include bsl_list_methods", tools.contains("bsl_list_methods")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Explore must include bsl_analyze_method", tools.contains("bsl_analyze_method")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Explore must include bsl_module_context", tools.contains("bsl_module_context")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Explore must include bsl_module_exports", tools.contains("bsl_module_exports")); //$NON-NLS-1$ //$NON-NLS-2$

        // Explore must NOT include write/mutate tools
        assertFalse("Explore must not include edit_file", tools.contains("edit_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Explore must not include write_file", tools.contains("write_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Explore must not include create_metadata", tools.contains("create_metadata")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Explore must not include git_mutate", tools.contains("git_mutate")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void exploreProfileToolCountWithinOptimalRange() {
        AgentProfile explore = new ExploreAgentProfile();
        int toolCount = explore.getAllowedTools().size();
        assertTrue("Explore profile should have <= 30 tools for optimal LLM accuracy, has " + toolCount, //$NON-NLS-1$
                toolCount <= 30);
    }

    @Test
    public void buildProfileContainsAllTools() {
        AgentProfile build = new BuildAgentProfile();
        Set<String> tools = build.getAllowedTools();

        assertNotNull("Build profile must define allowed tools", tools); //$NON-NLS-1$
        assertFalse("Build profile must not be empty", tools.isEmpty()); //$NON-NLS-1$
        // Build profile is the superset — includes write tools
        assertTrue("Build must include edit_file", tools.contains("edit_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Build must include write_file", tools.contains("write_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Build must include create_metadata", tools.contains("create_metadata")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Build must include git_mutate", tools.contains("git_mutate")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void codeBuildProfileIsSubsetOfBuild() {
        Set<String> buildTools = new BuildAgentProfile().getAllowedTools();
        Set<String> codeTools = new CodeBuildProfile().getAllowedTools();

        for (String tool : codeTools) {
            assertTrue("CodeBuild tool '" + tool + "' must exist in Build profile", //$NON-NLS-1$ //$NON-NLS-2$
                    buildTools.contains(tool));
        }
    }

    @Test
    public void metadataBuildProfileIsSubsetOfBuild() {
        Set<String> buildTools = new BuildAgentProfile().getAllowedTools();
        Set<String> metaTools = new MetadataBuildProfile().getAllowedTools();

        for (String tool : metaTools) {
            assertTrue("MetadataBuild tool '" + tool + "' must exist in Build profile", //$NON-NLS-1$ //$NON-NLS-2$
                    buildTools.contains(tool));
        }
    }

    @Test
    public void domainProfilesWithinOptimalToolRange() {
        assertToolCount(new OrchestratorProfile(), 10);
        assertToolCount(new CodeBuildProfile(), 25);
        assertToolCount(new MetadataBuildProfile(), 35);
        assertToolCount(new QABuildProfile(), 25);
        assertToolCount(new DCSBuildProfile(), 20);
        assertToolCount(new ExtensionBuildProfile(), 25);
        assertToolCount(new RecoveryProfile(), 15);
    }

    @Test
    public void allProfilesRegisteredInRegistry() {
        AgentProfileRegistry registry = AgentProfileRegistry.getInstance();

        assertNotNull("build profile", registry.getProfile("build").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("orchestrator profile", registry.getProfile("orchestrator").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("code profile", registry.getProfile("code").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("metadata profile", registry.getProfile("metadata").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("qa profile", registry.getProfile("qa").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("dcs profile", registry.getProfile("dcs").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("extension profile", registry.getProfile("extension").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("recovery profile", registry.getProfile("recovery").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("plan profile", registry.getProfile("plan").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("explore profile", registry.getProfile("explore").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Registry should have 10 profiles", 10, registry.getAllProfiles().size()); //$NON-NLS-1$
    }

    @Test
    public void planProfileIsReadOnly() {
        AgentProfile plan = new PlanAgentProfile();
        assertTrue("Plan profile must be read-only", plan.isReadOnly()); //$NON-NLS-1$
        assertFalse("Plan must not include edit_file", plan.getAllowedTools().contains("edit_file")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void recoveryProfileIsMinimal() {
        RecoveryProfile recovery = new RecoveryProfile();
        Set<String> tools = recovery.getAllowedTools();
        assertTrue("Recovery must include get_diagnostics", tools.contains("get_diagnostics")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Recovery must include edt_diagnostics", tools.contains("edt_diagnostics")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Recovery must not include create_metadata", tools.contains("create_metadata")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Recovery must not include git_mutate", tools.contains("git_mutate")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void orchestratorProfileIsReadOnlyAndDelegationFocused() {
        OrchestratorProfile orchestrator = new OrchestratorProfile();
        Set<String> tools = orchestrator.getAllowedTools();
        assertTrue("Orchestrator must be read-only", orchestrator.isReadOnly()); //$NON-NLS-1$
        assertTrue("Orchestrator must include delegate_to_agent", tools.contains("delegate_to_agent")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Orchestrator must include task", tools.contains("task")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Orchestrator must not include edit_file", tools.contains("edit_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Orchestrator must not include create_metadata", tools.contains("create_metadata")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void assertToolCount(AgentProfile profile, int maxExpected) {
        int count = profile.getAllowedTools().size();
        assertTrue(
                String.format("Profile '%s' has %d tools, expected <= %d", //$NON-NLS-1$
                        profile.getId(), count, maxExpected),
                count <= maxExpected);
    }
}
