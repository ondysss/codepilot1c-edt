package com.codepilot1c.core.agent.profiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.agent.prompts.AgentPromptTemplates;
import com.codepilot1c.core.permissions.PermissionDecision;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.ToolSurfaceAugmentor;
import com.google.gson.Gson;

import sun.misc.Unsafe;

/**
 * Tests for {@link AgentProfileRegistry} and profile gate enforcement.
 */
public class AgentProfileRegistryTest {

    private ToolRegistry previousRegistry;

    @Before
    public void installIsolatedRegistry() throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        Map<String, ITool> tools = new HashMap<>();
        for (String name : Set.of(
                "get_diagnostics", "inspect_role_rights", "inspect_template", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "java_compile_probe", "qa_validate_feature", "validate_query")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            tools.put(name, nonMutatingTool(name));
        }
        setField(registry, "tools", tools); //$NON-NLS-1$
        setField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "dynamicToolCapabilities", //$NON-NLS-1$
                new ConcurrentHashMap<String, DynamicToolCapability>());
        setField(registry, "gson", new Gson()); //$NON-NLS-1$
        setField(registry, "augmentor", ToolSurfaceAugmentor.passthrough()); //$NON-NLS-1$
        previousRegistry = installRegistry(registry);
    }

    @After
    public void restoreRegistry() throws Exception {
        installRegistry(previousRegistry);
    }

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
        assertTrue("Explore must include java_compile_probe", tools.contains("java_compile_probe")); //$NON-NLS-1$ //$NON-NLS-2$

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
        assertTrue("Explore profile should have <= 36 tools for optimal LLM accuracy, has " + toolCount, //$NON-NLS-1$
                toolCount <= 36);
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
        assertTrue("Build must include start_profiling", tools.contains("start_profiling")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Build must include get_profiling_results", tools.contains("get_profiling_results")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Build must include discover_tools", tools.contains("discover_tools")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Build must include connect_infobase", tools.contains("connect_infobase")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Build must include update_infobase_status", tools.contains("update_infobase_status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Build must include remember_fact", tools.contains("remember_fact")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void qaProfileCanInspectProfilingResults() {
        AgentProfile qa = new QABuildProfile();
        Set<String> tools = qa.getAllowedTools();

        assertTrue("QA must include start_profiling", tools.contains("start_profiling")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("QA must include get_profiling_results", tools.contains("get_profiling_results")); //$NON-NLS-1$ //$NON-NLS-2$
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
        assertToolCount(new InitAgentProfile(), 8);
        assertToolCount(new CodeBuildProfile(), 35);
        assertToolCount(new MetadataBuildProfile(), 40);
        // QA intentionally includes EDT debug/profiling tools for YAxUnit workflows.
        assertToolCount(new QABuildProfile(), 35);
        assertToolCount(new DCSBuildProfile(), 22);
        assertToolCount(new ExtensionBuildProfile(), 27);
        assertToolCount(new RecoveryProfile(), 18);
    }

    @Test
    public void discoverToolsIsAvailableInEveryProfile() {
        for (AgentProfile profile : java.util.List.of(
                new BuildAgentProfile(),
                new InitAgentProfile(),
                new CodeBuildProfile(),
                new MetadataBuildProfile(),
                new QABuildProfile(),
                new DCSBuildProfile(),
                new ExtensionBuildProfile(),
                new RecoveryProfile(),
                new PlanAgentProfile(),
                new ExploreAgentProfile(),
                new OrchestratorProfile(),
                new GsdDiscussProfile(),
                new GsdPlanProfile(),
                new GsdExecuteProfile(),
                new GsdVerifyProfile(),
                new GsdShipProfile())) {
            assertTrue(profile.getId() + " must include discover_tools", //$NON-NLS-1$
                    profile.getAllowedTools().contains("discover_tools")); //$NON-NLS-1$
        }
    }

    @Test
    public void gsdProfilesRegisteredInRegistry() {
        AgentProfileRegistry registry = AgentProfileRegistry.getInstance();

        assertNotNull("gsd-discuss profile", registry.getProfile("gsd-discuss").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("gsd-plan profile", registry.getProfile("gsd-plan").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("gsd-execute profile", registry.getProfile("gsd-execute").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("gsd-verify profile", registry.getProfile("gsd-verify").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("gsd-ship profile", registry.getProfile("gsd-ship").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void gsdReadOnlyProfilesHaveNoProjectMutations() {
        List<String> readOnlyIds = Arrays.asList(
                "gsd-discuss", //$NON-NLS-1$
                "gsd-plan", //$NON-NLS-1$
                "gsd-verify"); //$NON-NLS-1$
        List<String> mutations = Arrays.asList(
                "edit_file", "write_file", "workspace_copy_transform", "workspace_copy_transform_batch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "create_metadata", "update_metadata", "delete_metadata", "add_metadata_child", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "create_form", "apply_form_recipe", "mutate_form_model", "ensure_module_artifact", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "dcs_manage", "extension_manage", "external_manage", "render_template", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "git_mutate", "git_clone_and_import_project", "import_project_from_infobase", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "workspace_import_project", "connect_infobase", "qa_generate", "qa_run", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "qa_prepare_form_context", "author_yaxunit_tests", "run_yaxunit_tests", "debug_yaxunit_tests", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "start_profiling", "set_breakpoint", "remove_breakpoint", "step", "resume", "evaluate_expression", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "edt_diagnostics", //$NON-NLS-1$
                "task"); //$NON-NLS-1$

        for (String profileId : readOnlyIds) {
            AgentProfile profile = AgentProfileRegistry.getInstance()
                    .getProfile(profileId).orElseThrow(() -> new AssertionError(profileId + " not registered")); //$NON-NLS-1$
            assertTrue(profileId + " must be read-only", profile.isReadOnly()); //$NON-NLS-1$
            Set<String> tools = profile.getAllowedTools();
            for (String mutation : mutations) {
                assertFalse(profileId + " must not include mutation tool " + mutation, tools.contains(mutation)); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void gsdMutatingProfilesUseAskForProjectMutations() {
        AgentProfile execute = AgentProfileRegistry.getInstance()
                .getProfile("gsd-execute").orElseThrow(() -> new AssertionError("gsd-execute missing")); //$NON-NLS-1$ //$NON-NLS-2$
        AgentProfile ship = AgentProfileRegistry.getInstance()
                .getProfile("gsd-ship").orElseThrow(() -> new AssertionError("gsd-ship missing")); //$NON-NLS-1$ //$NON-NLS-2$

        assertPermissionDecision(execute, "edit_file", PermissionDecision.ASK); //$NON-NLS-1$
        assertPermissionDecision(execute, "write_file", PermissionDecision.ASK); //$NON-NLS-1$
        assertPermissionDecision(execute, "ensure_module_artifact", PermissionDecision.ASK); //$NON-NLS-1$
        assertPermissionDecision(ship, "git_mutate", PermissionDecision.ASK); //$NON-NLS-1$
        assertPermissionDecision(ship, "write_file", PermissionDecision.ASK); //$NON-NLS-1$

        assertFalse("gsd-execute must not bypass ask for mutations", //$NON-NLS-1$
                hasDecision(execute, "edit_file", PermissionDecision.ALLOW)); //$NON-NLS-1$
        assertFalse("gsd-ship must not bypass ask for mutations", //$NON-NLS-1$
                hasDecision(ship, "git_mutate", PermissionDecision.ALLOW)); //$NON-NLS-1$
    }

    private void assertPermissionDecision(AgentProfile profile, String toolName, PermissionDecision expected) {
        boolean found = profile.getDefaultPermissions().stream()
                .anyMatch(rule -> rule.getToolName().equals(toolName) && rule.getDecision() == expected);
        assertTrue(profile.getId() + " must have " + expected + " for " + toolName, found); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private boolean hasDecision(AgentProfile profile, String toolName, PermissionDecision decision) {
        return profile.getDefaultPermissions().stream()
                .anyMatch(rule -> rule.getToolName().equals(toolName) && rule.getDecision() == decision);
    }

    @Test
    public void gsdExecuteIncludesEdtValidateRequest() {
        AgentProfile execute = AgentProfileRegistry.getInstance()
                .getProfile("gsd-execute").orElseThrow(() -> new AssertionError("gsd-execute missing")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("gsd-execute must include edt_validate_request", //$NON-NLS-1$
                execute.getAllowedTools().contains("edt_validate_request")); //$NON-NLS-1$
    }

    @Test
    public void gsdExecuteContainsAllRequiredEdtMutationTools() {
        AgentProfile execute = AgentProfileRegistry.getInstance()
                .getProfile("gsd-execute").orElseThrow(() -> new AssertionError("gsd-execute missing")); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> tools = execute.getAllowedTools();

        List<String> required = Arrays.asList(
                "edt_validate_request", //$NON-NLS-1$
                "create_metadata", //$NON-NLS-1$
                "create_form", //$NON-NLS-1$
                "add_metadata_child", //$NON-NLS-1$
                "update_metadata", //$NON-NLS-1$
                "mutate_form_model", //$NON-NLS-1$
                "delete_metadata"); //$NON-NLS-1$
        for (String tool : required) {
            assertTrue("gsd-execute must include " + tool, tools.contains(tool)); //$NON-NLS-1$
        }
    }

    @Test
    public void gsdExecuteMutationToolsRequireAskPermission() {
        AgentProfile execute = AgentProfileRegistry.getInstance()
                .getProfile("gsd-execute").orElseThrow(() -> new AssertionError("gsd-execute missing")); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> mutations = Arrays.asList(
                "edit_file", //$NON-NLS-1$
                "write_file", //$NON-NLS-1$
                "ensure_module_artifact", //$NON-NLS-1$
                "create_metadata", //$NON-NLS-1$
                "create_form", //$NON-NLS-1$
                "add_metadata_child", //$NON-NLS-1$
                "update_metadata", //$NON-NLS-1$
                "mutate_form_model", //$NON-NLS-1$
                "delete_metadata"); //$NON-NLS-1$
        for (String tool : mutations) {
            assertPermissionDecision(execute, tool, PermissionDecision.ASK);
            assertFalse("gsd-execute must not bypass ask for " + tool, //$NON-NLS-1$
                    hasDecision(execute, tool, PermissionDecision.ALLOW));
        }
    }

    @Test
    public void gsdExecuteDeniesDirectMdoWrite() {
        AgentProfile execute = AgentProfileRegistry.getInstance()
                .getProfile("gsd-execute").orElseThrow(() -> new AssertionError("gsd-execute missing")); //$NON-NLS-1$ //$NON-NLS-2$

        boolean found = execute.getDefaultPermissions().stream()
                .anyMatch(rule -> "write_file".equals(rule.getToolName()) //$NON-NLS-1$
                        && rule.getDecision() == PermissionDecision.DENY
                        && "**/*.mdo".equals(rule.getResourcePattern())); //$NON-NLS-1$
        assertTrue("gsd-execute must deny direct write_file for *.mdo/Configuration.mdo", found); //$NON-NLS-1$
    }

    @Test
    public void gsdShipDoesNotExposeEdtMutationTools() {
        AgentProfile ship = AgentProfileRegistry.getInstance()
                .getProfile("gsd-ship").orElseThrow(() -> new AssertionError("gsd-ship missing")); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> tools = ship.getAllowedTools();

        assertFalse("ship must not include edt_validate_request because it has no EDT mutation tools", //$NON-NLS-1$
                tools.contains("edt_validate_request")); //$NON-NLS-1$
        assertFalse("ship must not include edit_file", tools.contains("edit_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("ship must not include create_metadata", tools.contains("create_metadata")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("ship must not include ensure_module_artifact", tools.contains("ensure_module_artifact")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void gsdPhaseProfilesContainExpectedGsdTools() {
        AgentProfile discuss = AgentProfileRegistry.getInstance()
                .getProfile("gsd-discuss").orElseThrow(() -> new AssertionError("gsd-discuss missing")); //$NON-NLS-1$ //$NON-NLS-2$
        AgentProfile plan = AgentProfileRegistry.getInstance()
                .getProfile("gsd-plan").orElseThrow(() -> new AssertionError("gsd-plan missing")); //$NON-NLS-1$ //$NON-NLS-2$
        AgentProfile execute = AgentProfileRegistry.getInstance()
                .getProfile("gsd-execute").orElseThrow(() -> new AssertionError("gsd-execute missing")); //$NON-NLS-1$ //$NON-NLS-2$
        AgentProfile verify = AgentProfileRegistry.getInstance()
                .getProfile("gsd-verify").orElseThrow(() -> new AssertionError("gsd-verify missing")); //$NON-NLS-1$ //$NON-NLS-2$
        AgentProfile ship = AgentProfileRegistry.getInstance()
                .getProfile("gsd-ship").orElseThrow(() -> new AssertionError("gsd-ship missing")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("discuss needs gsd_record_decision", discuss.getAllowedTools().contains("gsd_record_decision")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("plan needs gsd_create_plan", plan.getAllowedTools().contains("gsd_create_plan")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("execute needs gsd_update_task", execute.getAllowedTools().contains("gsd_update_task")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("verify needs gsd_record_evidence", verify.getAllowedTools().contains("gsd_record_evidence")); //$NON-NLS-1$ //$NON-NLS-2$

        for (AgentProfile profile : Arrays.asList(discuss, plan, execute, verify, ship)) {
            Set<String> tools = profile.getAllowedTools();
            assertTrue(profile.getId() + " needs gsd_get_state", tools.contains("gsd_get_state")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(profile.getId() + " needs gsd_transition", tools.contains("gsd_transition")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(profile.getId() + " must not use old monolithic gsd_plan", tools.contains("gsd_plan")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void gsdCapabilityMatrixHasOnlySharedReadsAndPhaseCapabilities() {
        Map<AgentProfile, Set<String>> matrix = Map.of(
                new GsdDiscussProfile(), Set.of(
                        "gsd_get_state", "gsd_record_decision", "gsd_transition"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                new GsdPlanProfile(), Set.of(
                        "gsd_get_state", "gsd_create_plan", "gsd_transition"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                new GsdExecuteProfile(), Set.of(
                        "gsd_get_state", "gsd_update_task", "gsd_record_evidence", "gsd_transition", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        "edt_validate_request", "edit_file", "write_file", "ensure_module_artifact", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        "create_metadata", "create_form", "add_metadata_child", "update_metadata", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        "mutate_form_model", "delete_metadata", "remember_fact"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                new GsdVerifyProfile(), Set.of(
                        "gsd_get_state", "gsd_record_evidence", "gsd_transition", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "inspect_role_rights", "inspect_template", "java_compile_probe", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "qa_validate_feature", "validate_query"), //$NON-NLS-1$ //$NON-NLS-2$
                new GsdShipProfile(), Set.of(
                        "gsd_get_state", "gsd_transition", "git_mutate", "write_file", "remember_fact")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        for (Map.Entry<AgentProfile, Set<String>> entry : matrix.entrySet()) {
            assertTrue(entry.getKey().getId(),
                    entry.getKey().getAllowedTools().containsAll(entry.getValue()));
            assertEquals(entry.getKey().getId(),
                    GsdProfileCapabilities.allowedTools(entry.getKey().getId()),
                    entry.getKey().getAllowedTools());
        }
    }

    @Test
    public void verifyEvidenceToolsExistAndAreNonMutating() {
        AgentProfile verify = new GsdVerifyProfile();
        ToolRegistry registry = ToolRegistry.getInstance();
        for (String name : Set.of(
                "get_diagnostics", "inspect_role_rights", "inspect_template", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "java_compile_probe", "qa_validate_feature", "validate_query")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue("verify must expose " + name, verify.getAllowedTools().contains(name)); //$NON-NLS-1$
            ITool tool = registry.getTool(name);
            assertNotNull(name + " must be registered", tool); //$NON-NLS-1$
            assertFalse(name + " must not mutate project state", tool.isMutating()); //$NON-NLS-1$
        }
        assertTrue(verify.isReadOnly());
        assertFalse(verify.canExecuteShell());
    }

    @Test
    public void gsdReadOnlyProfilesCannotGsdPlanOrMutateGsdTasksOfOtherPhases() {
        AgentProfile discuss = new GsdDiscussProfile();
        AgentProfile plan = new GsdPlanProfile();
        AgentProfile verify = new GsdVerifyProfile();

        assertFalse("discuss must not update tasks", discuss.getAllowedTools().contains("gsd_update_task")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("plan must not record evidence", plan.getAllowedTools().contains("gsd_record_evidence")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("verify must not create plans", verify.getAllowedTools().contains("gsd_create_plan")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void gsdProfilesWithinOptimalToolRange() {
        assertToolCount(new GsdDiscussProfile(), 40);
        assertToolCount(new GsdPlanProfile(), 40);
        assertToolCount(new GsdExecuteProfile(), 55);
        assertToolCount(new GsdVerifyProfile(), 45);
        assertToolCount(new GsdShipProfile(), 45);
    }

    @Test
    public void gsdPhasePromptsHaveBidirectionalToolParity() {
        List<AgentProfile> profiles = Arrays.asList(
                new GsdDiscussProfile(),
                new GsdPlanProfile(),
                new GsdExecuteProfile(),
                new GsdVerifyProfile(),
                new GsdShipProfile());

        for (AgentProfile profile : profiles) {
            String prompt = AgentPromptTemplates.buildGsdPhasePrompt(profile.getId());
            assertFalse(profile.getId() + " prompt must not mention old monolithic gsd_plan", //$NON-NLS-1$
                    prompt.contains("gsd_plan")); //$NON-NLS-1$
            assertEquals(profile.getId(), profile.getAllowedTools(), promptToolSection(prompt));
            for (ITool tool : ToolRegistry.getInstance().getAllTools()) {
                boolean mentioned = Pattern.compile(
                        "(?<![A-Za-z0-9_])" + Pattern.quote(tool.getName()) //$NON-NLS-1$
                                + "(?![A-Za-z0-9_])") //$NON-NLS-1$
                        .matcher(prompt).find();
                assertEquals(profile.getId() + " prompt/tool mismatch for " + tool.getName(), //$NON-NLS-1$
                        profile.getAllowedTools().contains(tool.getName()), mentioned);
            }
        }
    }

    @Test
    public void gsdReadOnlyPromptsDoNotMentionAbsentTaskTool() {
        java.util.regex.Pattern taskWord = java.util.regex.Pattern.compile("\\btask\\b"); //$NON-NLS-1$
        for (AgentProfile profile : Arrays.asList(new GsdDiscussProfile(), new GsdPlanProfile(), new GsdVerifyProfile())) {
            String prompt = AgentPromptTemplates.buildGsdPhasePrompt(profile.getId());
            assertFalse(profile.getId() + " read-only prompt must not mention absent task tool", //$NON-NLS-1$
                    taskWord.matcher(prompt).find());
        }
    }

    @Test
    public void memoryToolIsAvailableOnlyInMutatingProfiles() {
        assertTrue(new BuildAgentProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertTrue(new CodeBuildProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertTrue(new MetadataBuildProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertTrue(new QABuildProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertTrue(new DCSBuildProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertTrue(new ExtensionBuildProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertTrue(new RecoveryProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertTrue(new GsdExecuteProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertTrue(new GsdShipProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$

        assertFalse(new PlanAgentProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertFalse(new ExploreAgentProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertFalse(new OrchestratorProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertFalse(new GsdDiscussProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertFalse(new GsdPlanProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
        assertFalse(new GsdVerifyProfile().getAllowedTools().contains("remember_fact")); //$NON-NLS-1$
    }

    @Test
    public void dcsProfileCanPerformRequiredValidationFlow() {
        Set<String> tools = new DCSBuildProfile().getAllowedTools();

        assertTrue("DCS profile must expose edt_validate_request before mutating dcs_manage", //$NON-NLS-1$
                tools.contains("edt_validate_request")); //$NON-NLS-1$
        assertTrue("DCS profile must expose dcs_manage", tools.contains("dcs_manage")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void allProfilesRegisteredInRegistry() {
        AgentProfileRegistry registry = AgentProfileRegistry.getInstance();

        assertNotNull("build profile", registry.getProfile("build").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("orchestrator profile", registry.getProfile("orchestrator").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("init profile", registry.getProfile("init").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("code profile", registry.getProfile("code").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("metadata profile", registry.getProfile("metadata").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("qa profile", registry.getProfile("qa").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("dcs profile", registry.getProfile("dcs").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("extension profile", registry.getProfile("extension").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("recovery profile", registry.getProfile("recovery").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("plan profile", registry.getProfile("plan").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("explore profile", registry.getProfile("explore").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("gsd-discuss profile", registry.getProfile("gsd-discuss").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("gsd-plan profile", registry.getProfile("gsd-plan").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("gsd-execute profile", registry.getProfile("gsd-execute").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("gsd-verify profile", registry.getProfile("gsd-verify").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("gsd-ship profile", registry.getProfile("gsd-ship").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Registry should have 16 profiles", 16, registry.getAllProfiles().size()); //$NON-NLS-1$
    }

    @Test
    public void initProfileCanOnlyRefreshCodeMd() {
        InitAgentProfile init = new InitAgentProfile();
        Set<String> tools = init.getAllowedTools();

        assertFalse("Init profile is mutating because it writes Code.md", init.isReadOnly()); //$NON-NLS-1$
        assertTrue("Init must include write_file", tools.contains("write_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Init must include scan_metadata_index", tools.contains("scan_metadata_index")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Init must include discover_tools", tools.contains("discover_tools")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Init must not include edit_file", tools.contains("edit_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Init must not include create_metadata", tools.contains("create_metadata")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Init must not include remember_fact", tools.contains("remember_fact")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Init should have enough budget to write Code.md after representative scan", 40, init.getMaxSteps()); //$NON-NLS-1$
        assertTrue("Init write_file should be allowed after explicit user action", //$NON-NLS-1$
                init.getDefaultPermissions().stream()
                        .anyMatch(rule -> "write_file".equals(rule.getToolName()) //$NON-NLS-1$
                                && rule.getDecision() == PermissionDecision.ALLOW));
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

    private static Set<String> promptToolSection(String prompt) {
        String marker = "## Инструменты\n"; //$NON-NLS-1$
        int start = prompt.indexOf(marker);
        int end = prompt.indexOf("\n\n## Формат результата", start); //$NON-NLS-1$
        assertTrue("tool section must exist", start >= 0 && end > start); //$NON-NLS-1$
        String body = prompt.substring(start + marker.length(), end).trim();
        if (body.endsWith(".")) { //$NON-NLS-1$
            body = body.substring(0, body.length() - 1);
        }
        return Set.of(body.split(", ")); //$NON-NLS-1$
    }

    private static ITool nonMutatingTool(String name) {
        return new ITool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public String getParameterSchema() { return "{\"type\":\"object\"}"; } //$NON-NLS-1$
            @Override public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
                return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
            }
        };
    }

    private static ToolRegistry installRegistry(ToolRegistry registry) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        field.setAccessible(true);
        ToolRegistry previous = (ToolRegistry) field.get(null);
        field.set(null, registry);
        return previous;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
