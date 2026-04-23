package com.codepilot1c.core.edt.lang;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class BslMethodAnalyzerContractTest {

    @Test
    public void analyzerUsesTypedEdtStructuralApis() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/lang/BslMethodAnalyzer.java"); //$NON-NLS-1$

        assertTrue(source.contains("method.allStatements()")); //$NON-NLS-1$
        assertTrue(source.contains("invocation.isIsServerCall()")); //$NON-NLS-1$
        assertTrue(source.contains("method.getCallees()")); //$NON-NLS-1$
        assertTrue(source.contains("method.getCallers()")); //$NON-NLS-1$
        assertTrue(source.contains("tryExceptStatement.getExceptStatements()")); //$NON-NLS-1$
        assertTrue(source.contains("feature instanceof FormalParam")); //$NON-NLS-1$
    }

    @Test
    public void semanticServiceExposesAnalyzeMethodPath() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/lang/BslSemanticService.java"); //$NON-NLS-1$

        assertTrue(source.contains("public BslMethodAnalysisResult analyzeMethod")); //$NON-NLS-1$
        assertTrue(source.contains("new BslMethodAnalyzer().analyze")); //$NON-NLS-1$
    }

    private String readCoreSource(String relativePath) throws Exception {
        Path repoRoot = findRepoRoot();
        return Files.readString(repoRoot.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            if (Files.isDirectory(current.resolve("bundles")) && Files.isDirectory(current.resolve(".planning"))) { //$NON-NLS-1$ //$NON-NLS-2$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root"); //$NON-NLS-1$
    }
}
