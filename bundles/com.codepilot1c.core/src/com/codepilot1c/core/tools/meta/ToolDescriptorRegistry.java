package com.codepilot1c.core.tools.meta;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Registry of tool metadata used by routing logic.
 */
public final class ToolDescriptorRegistry {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolDescriptorRegistry.class);

    private static ToolDescriptorRegistry instance;

    private final Map<String, ToolDescriptor> descriptors = new HashMap<>();
    private boolean bootstrapAttempted;
    private boolean bootstrapping;

    private ToolDescriptorRegistry() {
    }

    public static synchronized ToolDescriptorRegistry getInstance() {
        if (instance == null) {
            instance = new ToolDescriptorRegistry();
        }
        return instance;
    }

    public void register(ToolDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        descriptors.put(descriptor.getName(), descriptor);
    }

    public void registerTool(ITool tool) {
        if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
            return;
        }
        ToolDescriptor existing = descriptors.get(tool.getName());
        ToolCategory runtimeCategory = resolveCategory(tool.getCategory());
        ToolDescriptor.Builder builder = ToolDescriptor.builder(tool.getName())
                .category(resolveMergedCategory(existing, runtimeCategory))
                .mutating(tool.isMutating())
                .requiresValidationToken(tool.requiresValidationToken());
        if (existing != null) {
            for (String tag : existing.getTags()) {
                builder.tag(tag);
            }
        }
        for (String tag : tool.getTags()) {
            builder.tag(tag);
        }
        register(builder.build());
    }

    public ToolDescriptor get(String name) {
        if (name == null) {
            return null;
        }
        ensureInitialized();
        return descriptors.get(name);
    }

    public ToolDescriptor getOrDefault(String name) {
        ToolDescriptor descriptor = get(name);
        if (descriptor != null) {
            return descriptor;
        }
        return ToolDescriptor.builder(name)
                .category(ToolCategory.OTHER)
                .mutating(false)
                .requiresValidationToken(false)
                .build();
    }

    public Collection<ToolDescriptor> getAll() {
        ensureInitialized();
        return Collections.unmodifiableCollection(descriptors.values());
    }

    private ToolCategory resolveCategory(String rawCategory) {
        if (rawCategory == null || rawCategory.isBlank()) {
            return ToolCategory.OTHER;
        }
        return switch (rawCategory.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "file", "files" -> ToolCategory.FILES; //$NON-NLS-1$ //$NON-NLS-2$
            case "bsl" -> ToolCategory.BSL; //$NON-NLS-1$
            case "metadata" -> ToolCategory.METADATA; //$NON-NLS-1$
            case "forms", "form" -> ToolCategory.FORMS; //$NON-NLS-1$ //$NON-NLS-2$
            case "external" -> ToolCategory.EXTERNAL; //$NON-NLS-1$
            case "dcs" -> ToolCategory.DCS; //$NON-NLS-1$
            case "diagnostics", "diagnostic" -> ToolCategory.DIAGNOSTICS; //$NON-NLS-1$ //$NON-NLS-2$
            case "extension", "extensions" -> ToolCategory.EXTENSION; //$NON-NLS-1$ //$NON-NLS-2$
            case "workspace" -> ToolCategory.WORKSPACE; //$NON-NLS-1$
            case "git" -> ToolCategory.GIT; //$NON-NLS-1$
            case "mcp", "mcp_generic" -> ToolCategory.MCP_GENERIC; //$NON-NLS-1$ //$NON-NLS-2$
            default -> ToolCategory.OTHER;
        };
    }

    private ToolCategory resolveMergedCategory(ToolDescriptor existing, ToolCategory runtimeCategory) {
        if (existing != null && existing.getCategory() != ToolCategory.OTHER) {
            return existing.getCategory();
        }
        return runtimeCategory != null ? runtimeCategory : ToolCategory.OTHER;
    }

    private synchronized void ensureInitialized() {
        if (bootstrapAttempted || bootstrapping) {
            return;
        }
        bootstrapping = true;
        try {
            ToolRegistry registry = ToolRegistry.getInstance();
            for (ITool tool : registry.getAllTools()) {
                registerTool(tool);
            }
            LOG.debug("ToolDescriptorRegistry bootstrapped from ToolRegistry with %d descriptors", //$NON-NLS-1$
                    Integer.valueOf(descriptors.size()));
        } catch (RuntimeException e) {
            LOG.warn("ToolDescriptorRegistry bootstrap from ToolRegistry failed: %s", e.getMessage()); //$NON-NLS-1$
        } finally {
            bootstrapAttempted = true;
            bootstrapping = false;
        }
    }
}
