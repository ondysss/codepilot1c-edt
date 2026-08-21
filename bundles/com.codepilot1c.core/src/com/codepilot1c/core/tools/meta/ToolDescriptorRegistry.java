package com.codepilot1c.core.tools.meta;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolRegistry.SlotIdentity;

/**
 * Registry of tool metadata used by routing logic.
 */
public final class ToolDescriptorRegistry {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolDescriptorRegistry.class);

    private static ToolDescriptorRegistry instance;

    private static final Object UNVERSIONED = new Object();

    private final ConcurrentMap<String, DescriptorEntry> descriptors =
            new ConcurrentHashMap<>();
    private final AtomicBoolean bootstrapStarted = new AtomicBoolean();
    private volatile boolean bootstrapAttempted;

    private ToolDescriptorRegistry() {
    }

    public static synchronized ToolDescriptorRegistry getInstance() {
        if (instance == null) {
            instance = new ToolDescriptorRegistry();
        }
        return instance;
    }

    /**
     * Creates a descriptor store for a detached/test registry that must not
     * publish into the process-wide effective tool surface.
     */
    public static ToolDescriptorRegistry createDetached() {
        ToolDescriptorRegistry detached = new ToolDescriptorRegistry();
        detached.bootstrapStarted.set(true);
        detached.bootstrapAttempted = true;
        return detached;
    }

    public void register(ToolDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        descriptors.compute(descriptor.getName(), (ignored, current) ->
                current != null && current.slotIdentity() instanceof SlotIdentity
                        ? current
                        : new DescriptorEntry(UNVERSIONED, descriptor));
    }

    /** Removes metadata for a tool that no longer has an effective implementation. */
    public void unregister(String name) {
        if (name != null) {
            descriptors.computeIfPresent(name, (ignored, current) ->
                    current.slotIdentity() == UNVERSIONED ? null : current);
        }
    }

    public void registerTool(ITool tool) {
        ToolDescriptor descriptor = describeTool(tool);
        if (descriptor != null) {
            register(descriptor);
        }
    }

    /** Computes tool-derived metadata without holding a descriptor-store lock. */
    public ToolDescriptor describeTool(ITool tool) {
        if (tool == null) {
            return null;
        }
        String name = tool.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        DescriptorEntry existingEntry = descriptors.get(name);
        ToolDescriptor existing = existingEntry != null
                && existingEntry.slotIdentity() == UNVERSIONED
                ? existingEntry.descriptor()
                : null;
        ToolCategory runtimeCategory = resolveCategory(tool.getCategory());
        ToolDescriptor.Builder builder = ToolDescriptor.builder(name)
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
        return builder.build();
    }

    /**
     * Atomically publishes metadata for one exact effective registry slot.
     * A different versioned slot can only be replaced by a caller that names
     * that slot as the expected predecessor. Unversioned/bootstrap metadata
     * and an absent entry may be claimed by the current effective slot.
     */
    public boolean publishSlot(
            SlotIdentity expectedIdentity,
            SlotIdentity slotIdentity,
            ToolDescriptor descriptor) {
        if (slotIdentity == null || descriptor == null) {
            return false;
        }
        AtomicBoolean published = new AtomicBoolean();
        descriptors.compute(descriptor.getName(), (ignored, current) -> {
            if (current == null && expectedIdentity != null) {
                return null;
            }
            if (current != null && current.slotIdentity() != UNVERSIONED
                    && current.slotIdentity() != expectedIdentity
                    && current.slotIdentity() != slotIdentity) {
                return current;
            }
            published.set(true);
            return new DescriptorEntry(slotIdentity, descriptor);
        });
        return published.get();
    }

    /** Removes metadata only when it still belongs to the specified slot. */
    public void removeSlot(String name, SlotIdentity slotIdentity) {
        if (name == null || slotIdentity == null) {
            return;
        }
        descriptors.computeIfPresent(name, (ignored, current) ->
                current.slotIdentity() == slotIdentity ? null : current);
    }

    boolean belongsToSlot(String name, SlotIdentity slotIdentity) {
        DescriptorEntry entry = name != null ? descriptors.get(name) : null;
        return entry != null && entry.slotIdentity() == slotIdentity;
    }

    public ToolDescriptor get(String name) {
        if (name == null) {
            return null;
        }
        ensureInitialized();
        DescriptorEntry entry = descriptors.get(name);
        return entry != null ? entry.descriptor() : null;
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
        return Collections.unmodifiableList(descriptors.values().stream()
                .map(DescriptorEntry::descriptor)
                .toList());
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

    private void ensureInitialized() {
        if (bootstrapAttempted
                || !bootstrapStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            ToolRegistry registry = ToolRegistry.getInstance();
            registry.refreshToolDescriptors();
            LOG.debug("ToolDescriptorRegistry bootstrapped from ToolRegistry with %d descriptors", //$NON-NLS-1$
                    Integer.valueOf(descriptors.size()));
        } catch (RuntimeException e) {
            LOG.warn("ToolDescriptorRegistry bootstrap from ToolRegistry failed: %s", e.getMessage()); //$NON-NLS-1$
        } finally {
            bootstrapAttempted = true;
        }
    }

    private record DescriptorEntry(Object slotIdentity, ToolDescriptor descriptor) {
    }
}
