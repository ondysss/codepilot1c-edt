package com.codepilot1c.core.agent.profiles;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.permissions.PermissionRule;

public class AgentProfileToolExposureTest {

    @Test
    public void buildProfileExposesProfilingToolsAndPermissions() {
        assertProfilingToolsExposed(new BuildAgentProfile());
    }

    @Test
    public void qaBuildProfileExposesProfilingToolsAndPermissions() {
        assertProfilingToolsExposed(new QABuildProfile());
    }

    @Test
    public void profilesWithUpdateInfobaseCommandExposeStatusPollingTool() {
        for (AgentProfile profile : new AgentProfile[] {
                new BuildAgentProfile(),
                new CodeBuildProfile(),
                new MetadataBuildProfile(),
                new QABuildProfile(),
                new DCSBuildProfile(),
                new ExtensionBuildProfile(),
                new RecoveryProfile()
        }) {
            assertTrue(profile.getId() + " must allow edt_diagnostics", //$NON-NLS-1$
                    profile.getAllowedTools().contains("edt_diagnostics")); //$NON-NLS-1$
            assertTrue(profile.getId() + " must allow update_infobase_status", //$NON-NLS-1$
                    profile.getAllowedTools().contains("update_infobase_status")); //$NON-NLS-1$
            assertTrue(profile.getId() + " must define default permission for update_infobase_status", //$NON-NLS-1$
                    hasPermissionFor(profile, "update_infobase_status")); //$NON-NLS-1$
        }
    }

    private static void assertProfilingToolsExposed(AgentProfile profile) {
        assertTrue(profile.getId() + " must allow start_profiling", //$NON-NLS-1$
                profile.getAllowedTools().contains("start_profiling")); //$NON-NLS-1$
        assertTrue(profile.getId() + " must allow get_profiling_results", //$NON-NLS-1$
                profile.getAllowedTools().contains("get_profiling_results")); //$NON-NLS-1$

        assertTrue(profile.getId() + " must define default permission for start_profiling", //$NON-NLS-1$
                hasPermissionFor(profile, "start_profiling")); //$NON-NLS-1$
        assertTrue(profile.getId() + " must define default permission for get_profiling_results", //$NON-NLS-1$
                hasPermissionFor(profile, "get_profiling_results")); //$NON-NLS-1$
    }

    private static boolean hasPermissionFor(AgentProfile profile, String toolName) {
        for (PermissionRule rule : profile.getDefaultPermissions()) {
            if (rule.matchesTool(toolName)) {
                return true;
            }
        }
        return false;
    }
}
