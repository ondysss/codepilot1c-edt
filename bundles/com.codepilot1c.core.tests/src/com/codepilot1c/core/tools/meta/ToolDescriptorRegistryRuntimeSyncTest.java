package com.codepilot1c.core.tools.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;

public class ToolDescriptorRegistryRuntimeSyncTest {

    @Test
    public void registerToolBuildsDescriptorFromRuntimeMetadata() {
        ToolDescriptorRegistry registry = ToolDescriptorRegistry.getInstance();
        registry.registerTool(new RuntimeTool());

        ToolDescriptor descriptor = registry.get("runtime_tool"); //$NON-NLS-1$
        assertEquals(ToolCategory.BSL, descriptor.getCategory());
        assertTrue(descriptor.isMutating());
        assertTrue(descriptor.requiresValidationToken());
        assertTrue(descriptor.getTags().contains("workspace")); //$NON-NLS-1$
    }

    @Test
    public void registerToolDoesNotDegradeExistingBuiltinDescriptorCategory() {
        ToolDescriptorRegistry registry = ToolDescriptorRegistry.getInstance();
        registry.registerTool(new ExistingBuiltinTool());

        ToolDescriptor descriptor = registry.get("qa_prepare_form_context"); //$NON-NLS-1$
        assertEquals(ToolCategory.FORMS, descriptor.getCategory());
        assertTrue(descriptor.isMutating());
        assertTrue(descriptor.getTags().contains("edt")); //$NON-NLS-1$
    }

    @Test
    public void builtinsBootstrapFromToolRegistryWithoutManualDefaults() {
        ToolDescriptorRegistry registry = ToolDescriptorRegistry.getInstance();

        assertEquals(ToolCategory.FILES, registry.getOrDefault("read_file").getCategory()); //$NON-NLS-1$
        assertEquals(ToolCategory.FORMS, registry.getOrDefault("qa_prepare_form_context").getCategory()); //$NON-NLS-1$
        assertEquals(ToolCategory.DIAGNOSTICS, registry.getOrDefault("edt_launch_app").getCategory()); //$NON-NLS-1$
    }

    private static final class RuntimeTool implements ITool {
        @Override public String getName() { return "runtime_tool"; } //$NON-NLS-1$
        @Override public String getDescription() { return "Runtime descriptor sync"; } //$NON-NLS-1$
        @Override public String getParameterSchema() { return "{\"type\":\"object\"}"; } //$NON-NLS-1$
        @Override public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
        @Override public String getCategory() { return "bsl"; } //$NON-NLS-1$
        @Override public boolean isMutating() { return true; }
        @Override public boolean requiresValidationToken() { return true; }
        @Override public Set<String> getTags() { return Set.of("workspace", "edt"); } //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class ExistingBuiltinTool implements ITool {
        @Override public String getName() { return "qa_prepare_form_context"; } //$NON-NLS-1$
        @Override public String getDescription() { return "Runtime refresh for existing builtin"; } //$NON-NLS-1$
        @Override public String getParameterSchema() { return "{\"type\":\"object\"}"; } //$NON-NLS-1$
        @Override public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
        @Override public String getCategory() { return "qa"; } //$NON-NLS-1$
        @Override public boolean isMutating() { return true; }
        @Override public Set<String> getTags() { return Set.of("workspace", "edt"); } //$NON-NLS-1$ //$NON-NLS-2$
    }
}
