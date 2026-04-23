package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;

public class BuiltinToolTaxonomyTest {

    @Test
    public void knownToolNamesResolveToPublicCategories() {
        assertEquals(ToolCategory.FILES_READ_SEARCH, BuiltinToolTaxonomy.categoryOf("read_file")); //$NON-NLS-1$
        assertEquals(ToolCategory.METADATA_MUTATION, BuiltinToolTaxonomy.categoryOf("ensure_module_artifact")); //$NON-NLS-1$
        assertEquals(ToolCategory.QA, BuiltinToolTaxonomy.categoryOf("qa_inspect")); //$NON-NLS-1$
        assertEquals(ToolCategory.EDT_SEMANTIC_READ, BuiltinToolTaxonomy.categoryOf("bsl_module_context")); //$NON-NLS-1$
        assertEquals(ToolCategory.SMOKE_RUNTIME_RECOVERY, BuiltinToolTaxonomy.categoryOf("edt_diagnostics")); //$NON-NLS-1$
    }

    @Test
    public void unknownToolNameFallsBackToDynamic() {
        assertEquals(ToolCategory.DYNAMIC, BuiltinToolTaxonomy.categoryOf("__unknown_tool__")); //$NON-NLS-1$
        assertFalse(BuiltinToolTaxonomy.find("__unknown_tool__").isPresent()); //$NON-NLS-1$
        assertFalse(BuiltinToolTaxonomy.isKnownBuiltin("__unknown_tool__")); //$NON-NLS-1$
    }

    @Test
    public void nullInputDoesNotThrow() {
        assertEquals(ToolCategory.DYNAMIC, BuiltinToolTaxonomy.categoryOf((String) null));
        assertFalse(BuiltinToolTaxonomy.find(null).isPresent());
    }

    @Test
    public void exportedContextUsesPublicCategoryType() throws Exception {
        assertEquals(
                ToolCategory.class,
                ToolSurfaceContext.class.getMethod("getCategory").getReturnType()); //$NON-NLS-1$
        assertTrue(ToolSurfaceContext.class.getPackageName().endsWith(".tools.surface")); //$NON-NLS-1$
    }

    @Test
    public void runtimeMetadataCanInferPublicCategoryWithoutManualNameEntry() {
        assertEquals(ToolCategory.QA, BuiltinToolTaxonomy.categoryOf(new RuntimeTool("runtime_qa", "qa", "", false))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(ToolCategory.EDT_SEMANTIC_READ,
                BuiltinToolTaxonomy.categoryOf(new RuntimeTool("runtime_edt", "edt", "", false))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(ToolCategory.FILES_WRITE_EDIT,
                BuiltinToolTaxonomy.categoryOf(new RuntimeTool("runtime_edit", "file", "", true))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void explicitSurfaceCategoryOverridesBroadRuntimeCategory() {
        ITool tool = new RuntimeTool(
                "runtime_launch", //$NON-NLS-1$
                "workspace", //$NON-NLS-1$
                "smoke_runtime_recovery", //$NON-NLS-1$
                true);
        assertEquals(ToolCategory.SMOKE_RUNTIME_RECOVERY, BuiltinToolTaxonomy.categoryOf(tool));
    }

    private static final class RuntimeTool implements ITool {
        private final String name;
        private final String category;
        private final String surfaceCategory;
        private final boolean mutating;

        private RuntimeTool(String name, String category, String surfaceCategory, boolean mutating) {
            this.name = name;
            this.category = category;
            this.surfaceCategory = surfaceCategory;
            this.mutating = mutating;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "runtime"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }

        @Override
        public String getCategory() {
            return category;
        }

        @Override
        public String getSurfaceCategory() {
            return surfaceCategory;
        }

        @Override
        public boolean isMutating() {
            return mutating;
        }
    }
}
