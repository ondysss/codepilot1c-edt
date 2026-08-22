/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Assume;

import com.codepilot1c.core.filesystem.SecureDirectoryMutation;

/** Explicit provider precondition and secure-directory provisioning for mutation tests. */
public final class GsdTestSupport {

    private GsdTestSupport() {
    }

    public static Path secureProject(Path project) throws IOException {
        Assume.assumeTrue("GSD mutation test requires a real SecureDirectoryStream provider", //$NON-NLS-1$
                SecureDirectoryMutation.supportsSecureDirectoryStreams(project));
        Files.createDirectories(project.resolve(GsdStateStore.GSD_DIR_NAME));
        return project;
    }

    /** Seeds a valid populated state by raw file write, without exercising a mutation API. */
    public static GsdState seedPortablePopulatedState(Path project) throws IOException {
        GsdState state = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION,
                "portable-cycle", //$NON-NLS-1$
                3L,
                7L,
                GsdPhase.DISCOVERY,
                "portable inspection", //$NON-NLS-1$
                List.of(),
                List.of(GsdDecision.of("decision-1", "inspect safely", //$NON-NLS-1$ //$NON-NLS-2$
                        "read-only access remains useful")), //$NON-NLS-1$
                List.of(),
                List.of(),
                List.of(),
                GsdShipment.empty(),
                List.of(),
                List.of("portable-cycle"), //$NON-NLS-1$
                GsdSessionPointer.of("portable-session", "portable-workstream")); //$NON-NLS-1$ //$NON-NLS-2$
        Path gsd = Files.createDirectories(project.resolve(GsdStateStore.GSD_DIR_NAME));
        Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON), """
                {
                  "schemaVersion": 2,
                  "cycleId": "portable-cycle",
                  "generation": 3,
                  "revision": 7,
                  "phase": "DISCOVERY",
                  "goal": "portable inspection",
                  "acceptanceCriteria": [],
                  "decisions": [{
                    "id": "decision-1",
                    "summary": "inspect safely",
                    "rationale": "read-only access remains useful",
                    "alternatives": []
                  }],
                  "tasks": [],
                  "waves": [],
                  "evidence": [],
                  "shipment": {"id": "", "deliveryReference": "", "status": "PENDING"},
                  "transitionHistory": [],
                  "usedCycleIds": ["portable-cycle"],
                  "sessionPointer": {
                    "sessionId": "portable-session",
                    "workstreamId": "portable-workstream"
                  }
                }
                """);
        return state;
    }

    /** Provisions mutation directories only for a method carrying the explicit marker. */
    public static Path projectForTest(
            Class<?> testClass, String methodName, Path project) throws IOException {
        try {
            if (testClass.getMethod(methodName).isAnnotationPresent(RequiresSecureMutation.class)) {
                return secureProject(project);
            }
            return project;
        } catch (NoSuchMethodException e) {
            throw new IOException("cannot resolve current GSD test method: " + methodName, e); //$NON-NLS-1$
        }
    }
}
