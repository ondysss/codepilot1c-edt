package com.codepilot1c.core.agent.profiles;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.permissions.PermissionDecision;
import com.codepilot1c.core.permissions.PermissionEvaluator;
import com.codepilot1c.core.permissions.PermissionRule;
import com.codepilot1c.core.permissions.ProfilePermissionGate;
import com.codepilot1c.core.permissions.ProfilePermissionGate.GateDecision;

/**
 * Tests for GSD phase-profile permission rules.
 *
 * <p>Verifies that Execute protects EDT metadata and Ship can write only
 * explicitly scoped release artifacts.</p>
 */
public class GsdProfilePermissionTest {

    // ---- GsdShipProfile: explicit release-artifact paths only ----------

    @Test
    public void shipProfileAsksForExplicitReleaseArtifactPaths() {
        for (String path : List.of(
                "CHANGELOG.md", //$NON-NLS-1$
                "RELEASE_NOTES.md", //$NON-NLS-1$
                "release-notes.md", //$NON-NLS-1$
                "docs/release-notes/v1.2.3.md", //$NON-NLS-1$
                "release-notes/v1.2.3.json")) { //$NON-NLS-1$
            assertEquals(path, GateDecision.ASK, evaluateShipWrite(path));
        }
    }

    @Test
    public void shipProfileDeniesSourceManifestAndProductMetadataWrites() {
        for (String path : List.of(
                "src/Main.java", //$NON-NLS-1$
                "META-INF/MANIFEST.MF", //$NON-NLS-1$
                "plugin.xml", //$NON-NLS-1$
                "feature.xml", //$NON-NLS-1$
                "product/codepilot.product", //$NON-NLS-1$
                "src/Configuration/Configuration.mdo")) { //$NON-NLS-1$
            assertEquals(path, GateDecision.DENY, evaluateShipWrite(path));
        }
    }

    @Test
    public void shipProfileDeniesTraversalDisguisedAsReleaseArtifact() {
        for (String path : List.of(
                "docs/release-notes/../../src/Main.java", //$NON-NLS-1$
                "docs/release-notes/../v1.md", //$NON-NLS-1$
                "/CHANGELOG.md", //$NON-NLS-1$
                "C:\\workspace\\CHANGELOG.md", //$NON-NLS-1$
                "docs//release-notes/v1.md")) { //$NON-NLS-1$
            assertEquals(path, GateDecision.DENY, evaluateShipWrite(path));
        }
    }

    @Test
    public void shipProfileDeniesWriteWithoutAPath() {
        GateDecision decision = ProfilePermissionGate.evaluate(
                new GsdShipProfile().getDefaultPermissions(), List.of(),
                "write_file", Map.of("content", "x")).decision(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(GateDecision.DENY, decision);
    }

    @Test
    public void shipProfileAsksOnlyForNonWorkingTreeGitOperations() {
        for (String operation : List.of("add", "commit", "push")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals(operation, GateDecision.ASK, evaluateShipGit(operation));
        }
        for (String operation : List.of(
                "init", "create", "create_repo", "clone", "remote_add", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "remote_set_url", "fetch", "pull", "checkout", "create_branch")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            assertEquals(operation, GateDecision.DENY, evaluateShipGit(operation));
        }
    }

    @Test
    public void gitMutateMissingOrInvalidOperationFailsAtPermissionBoundary() {
        for (Map<String, Object> arguments : List.<Map<String, Object>>of(
                Map.of(),
                Map.of("operation", ""), //$NON-NLS-1$ //$NON-NLS-2$
                Map.of("operation", "merge"), //$NON-NLS-1$ //$NON-NLS-2$
                Map.of("operation", 42))) { //$NON-NLS-1$
            assertEquals(GateDecision.DENY, ProfilePermissionGate.evaluate(
                    new GsdShipProfile().getDefaultPermissions(), List.of(),
                    "git_mutate", arguments).decision()); //$NON-NLS-1$
        }
    }

    // ---- GsdExecuteProfile: deny *.mdo before ask ----------------------

    @Test
    public void executeProfileDeniesWriteFileForMdo() {
        GsdExecuteProfile profile = new GsdExecuteProfile();
        PermissionDecision decision = evaluateFirstMatch(
                profile.getDefaultPermissions(), "write_file", "src/Configuration/Configuration.mdo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("write_file for *.mdo must be DENIED in Execute profile", //$NON-NLS-1$
                PermissionDecision.DENY, decision);
    }

    @Test
    public void executeProfileAsksWriteFileForNonMdo() {
        GsdExecuteProfile profile = new GsdExecuteProfile();
        PermissionDecision decision = evaluateFirstMatch(
                profile.getDefaultPermissions(), "write_file", "src/Main.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("write_file for non-.mdo must be ASK in Execute profile", //$NON-NLS-1$
                PermissionDecision.ASK, decision);
    }

    @Test
    public void executeProfileDeniesEditFileForMdo() {
        GsdExecuteProfile profile = new GsdExecuteProfile();
        PermissionDecision decision = evaluateStrictestMatch(
                profile.getDefaultPermissions(), "edit_file", //$NON-NLS-1$
                "src/Configuration/Configuration.mdo"); //$NON-NLS-1$
        assertEquals(PermissionDecision.DENY, decision);
    }

    // ---- Helper --------------------------------------------------------

    /**
     * Evaluates rules against a tool/resource pair using the same logic as
     * {@link PermissionEvaluator#firstMatch}: filter matching rules, sort by
     * priority descending, return first match's decision.
     */
    private static PermissionDecision evaluateFirstMatch(
            List<PermissionRule> rules, String toolName, String resource) {
        return PermissionEvaluator.firstMatch(rules, toolName, resource)
                .map(PermissionRule::getDecision)
                .orElse(PermissionDecision.ASK);
    }

    private static PermissionDecision evaluateStrictestMatch(
            List<PermissionRule> rules, String toolName, String resource) {
        return PermissionEvaluator.strictestMatch(rules, toolName, resource)
                .map(PermissionRule::getDecision)
                .orElse(PermissionDecision.ASK);
    }

    private static GateDecision evaluateShipWrite(String path) {
        return ProfilePermissionGate.evaluate(
                new GsdShipProfile().getDefaultPermissions(), List.of(),
                "write_file", Map.of("path", path)).decision(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static GateDecision evaluateShipGit(String operation) {
        return ProfilePermissionGate.evaluate(
                new GsdShipProfile().getDefaultPermissions(), List.of(),
                "git_mutate", Map.of("operation", operation)).decision(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
