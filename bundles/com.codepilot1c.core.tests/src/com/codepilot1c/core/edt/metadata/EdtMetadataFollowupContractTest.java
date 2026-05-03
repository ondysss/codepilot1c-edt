package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class EdtMetadataFollowupContractTest {

    @Test
    public void sourceUsesBatchForceExportTargetsIncludingConfiguration() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"); //$NON-NLS-1$

        assertTrue(source.contains("forceExport(dtProject, targets)")); //$NON-NLS-1$
        assertTrue(source.contains("targets.add(\"Configuration\")")); //$NON-NLS-1$
    }

    @Test
    public void sourceUsesFormItemManagementServiceForNewFormItems() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"); //$NON-NLS-1$

        assertTrue(source.contains("IFormItemManagementService")); //$NON-NLS-1$
        assertTrue(source.contains("FormNewItemDescriptor")); //$NON-NLS-1$
        assertTrue(source.contains("itemManagementService.addGroup")); //$NON-NLS-1$
        assertTrue(source.contains("itemManagementService.addField")); //$NON-NLS-1$
    }

    private String readCoreSource(String relativePath) throws Exception {
        Path repoRoot = findRepoRoot();
        return Files.readString(repoRoot.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            if (Files.isDirectory(current.resolve("bundles")) && Files.isDirectory(current.resolve(".git"))) { //$NON-NLS-1$ //$NON-NLS-2$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root"); //$NON-NLS-1$
    }
}
