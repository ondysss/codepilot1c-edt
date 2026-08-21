package com.codepilot1c.core.tools.meta;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolRegistry.SlotIdentity;

/**
 * Registry of tool metadata used by routing logic.
 */
public final class ToolDescriptorRegistry {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolDescriptorRegistry.class);

    private static final Object INSTANCE_LOCK = new Object();
    private static volatile ToolDescriptorRegistry instance;

    private static final Object UNVERSIONED = new Object();

    private final ConcurrentMap<String, DescriptorEntry> descriptors =
            new ConcurrentHashMap<>();
    private final BootstrapControl bootstrap;
    private final Consumer<ToolDescriptorRegistry> bootstrapAction;

    private ToolDescriptorRegistry() {
        this(BootstrapState.NEW,
                registry -> ToolRegistry.getInstance().refreshToolDescriptors());
    }

    private ToolDescriptorRegistry(
            BootstrapState initialState,
            Consumer<ToolDescriptorRegistry> bootstrapAction) {
        bootstrap = new BootstrapControl(initialState);
        this.bootstrapAction = bootstrapAction;
    }

    public static ToolDescriptorRegistry getInstance() {
        ToolDescriptorRegistry current = instance;
        if (current == null) {
            synchronized (INSTANCE_LOCK) {
                current = instance;
                if (current == null) {
                    current = new ToolDescriptorRegistry();
                    instance = current;
                }
            }
        }
        return current;
    }

    /**
     * Creates a descriptor store for a detached/test registry that must not
     * publish into the process-wide effective tool surface.
     */
    public static ToolDescriptorRegistry createDetached() {
        return new ToolDescriptorRegistry(BootstrapState.READY, ignored -> {
        });
    }

    static ToolDescriptorRegistry createForBootstrapTests(
            Consumer<ToolDescriptorRegistry> bootstrapAction) {
        return new ToolDescriptorRegistry(BootstrapState.NEW, bootstrapAction);
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

    /**
     * Captures metadata that is about to be replaced structurally. This is a
     * callback-free snapshot used only for exact-slot registration rollback.
     */
    public ToolDescriptor descriptorForReplacement(
            String name, SlotIdentity expectedIdentity) {
        DescriptorEntry entry = name != null ? descriptors.get(name) : null;
        if (entry == null) {
            return null;
        }
        Object identity = entry.slotIdentity();
        if (expectedIdentity == null) {
            return identity == UNVERSIONED ? entry.descriptor() : null;
        }
        return identity == expectedIdentity ? entry.descriptor() : null;
    }

    /**
     * Restores the predecessor of a failed registration when the descriptor
     * is still owned by that slot (or was concurrently removed). A different
     * current slot always wins. The operation leaves no tombstone.
     */
    public boolean restoreSlot(
            String name,
            SlotIdentity failedIdentity,
            SlotIdentity previousIdentity,
            ToolDescriptor previousDescriptor) {
        if (name == null || failedIdentity == null) {
            return false;
        }
        AtomicBoolean restored = new AtomicBoolean();
        descriptors.compute(name, (ignored, current) -> {
            if (current != null && current.slotIdentity() != failedIdentity) {
                return current;
            }
            restored.set(true);
            if (previousDescriptor == null) {
                return null;
            }
            Object identity = previousIdentity != null
                    ? previousIdentity : UNVERSIONED;
            return new DescriptorEntry(identity, previousDescriptor);
        });
        return restored.get();
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
        BootstrapAccess access = ensureInitialized();
        DescriptorEntry entry = descriptors.get(name);
        if (entry != null) {
            return entry.descriptor();
        }
        return access == BootstrapAccess.OWNER_REENTRY
                || access == BootstrapAccess.CONSERVATIVE_READY
                ? conservativeDescriptor(name)
                : null;
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

    /**
     * Lets ToolRegistry own descriptor completion when registry initialization
     * starts first. The lease never waits and invokes no callbacks.
     */
    public BootstrapLease beginExternalBootstrap() {
        return new BootstrapLease(bootstrap.tryStart());
    }

    private BootstrapAccess ensureInitialized() {
        BootstrapAccess access = bootstrap.access();
        if (access == BootstrapAccess.READY
                || access == BootstrapAccess.CONSERVATIVE_READY
                || access == BootstrapAccess.OWNER_REENTRY) {
            return access;
        }
        if (access == BootstrapAccess.WAIT) {
            bootstrap.awaitCompletion();
            return bootstrap.access();
        }
        try {
            bootstrapAction.accept(this);
            LOG.debug("ToolDescriptorRegistry bootstrapped from ToolRegistry with %d descriptors", //$NON-NLS-1$
                    Integer.valueOf(descriptors.size()));
            bootstrap.complete();
            return BootstrapAccess.READY;
        } catch (Throwable failure) {
            if (bootstrap.fail(failure)) {
                throw propagate(failure);
            }
            LOG.error("Keeping conservative descriptors after late bootstrap failure", //$NON-NLS-1$
                    failure);
            return bootstrap.access();
        }
    }

    private ToolDescriptor conservativeDescriptor(String name) {
        return ToolDescriptor.builder(name)
                .category(ToolCategory.OTHER)
                .mutating(true)
                .requiresValidationToken(true)
                .build();
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Tool descriptor bootstrap failed", failure); //$NON-NLS-1$
    }

    /**
     * Coordinates ToolRegistry's structural publication and later metadata
     * refinement without making authorization readers wait for refinement.
     */
    public final class BootstrapLease {
        private final boolean owned;
        private boolean structuralPublished;

        private BootstrapLease(boolean owned) {
            this.owned = owned;
        }

        public void structuralReady() {
            try {
                bootstrap.structuralReady();
                structuralPublished = true;
            } finally {
                bootstrap.leaveExternalBootstrap();
            }
        }

        public void refinementComplete() {
            if (structuralPublished) {
                bootstrap.complete();
            }
        }

        /** Preserves the original one-phase lease contract for external callers. */
        public void complete() {
            structuralReady();
            refinementComplete();
        }

        public void fail(Throwable failure) {
            try {
                if (owned) {
                    bootstrap.fail(failure);
                }
            } finally {
                bootstrap.leaveExternalBootstrap();
            }
        }
    }

    private enum BootstrapState {
        NEW,
        INITIALIZING,
        STRUCTURAL_READY,
        READY,
        FAILED
    }

    private enum BootstrapAccess {
        OWNER,
        OWNER_REENTRY,
        WAIT,
        CONSERVATIVE_READY,
        READY
    }

    private static final class BootstrapControl {
        private final Object stateLock = new Object();
        private final CountDownLatch completion = new CountDownLatch(1);
        private final ThreadLocal<Boolean> externalParticipant =
                ThreadLocal.withInitial(() -> Boolean.FALSE);
        private BootstrapState state;
        private Thread owner;
        private volatile Throwable failure;

        private BootstrapControl(BootstrapState initialState) {
            state = initialState;
            if (initialState == BootstrapState.READY) {
                completion.countDown();
            }
        }

        private boolean tryStart() {
            synchronized (stateLock) {
                if (state == BootstrapState.NEW) {
                    state = BootstrapState.INITIALIZING;
                    owner = Thread.currentThread();
                    externalParticipant.set(Boolean.TRUE);
                    return true;
                }
                if (state == BootstrapState.INITIALIZING) {
                    externalParticipant.set(Boolean.TRUE);
                }
                if (state == BootstrapState.FAILED) {
                    throw propagate(failure);
                }
                return false;
            }
        }

        private BootstrapAccess access() {
            synchronized (stateLock) {
                return switch (state) {
                    case NEW -> {
                        state = BootstrapState.INITIALIZING;
                        owner = Thread.currentThread();
                        yield BootstrapAccess.OWNER;
                    }
                    case INITIALIZING -> owner == Thread.currentThread()
                            || externalParticipant.get().booleanValue()
                            ? BootstrapAccess.OWNER_REENTRY
                            : BootstrapAccess.WAIT;
                    case STRUCTURAL_READY -> BootstrapAccess.CONSERVATIVE_READY;
                    case READY -> BootstrapAccess.READY;
                    case FAILED -> throw propagate(failure);
                };
            }
        }

        private void complete() {
            synchronized (stateLock) {
                if (state != BootstrapState.INITIALIZING
                        && state != BootstrapState.STRUCTURAL_READY) {
                    return;
                }
                state = BootstrapState.READY;
                owner = null;
            }
            completion.countDown();
        }

        private void structuralReady() {
            synchronized (stateLock) {
                if (state == BootstrapState.FAILED) {
                    throw propagate(failure);
                }
                if (state != BootstrapState.INITIALIZING) {
                    return;
                }
                state = BootstrapState.STRUCTURAL_READY;
                owner = null;
            }
            completion.countDown();
        }

        private boolean fail(Throwable cause) {
            synchronized (stateLock) {
                if (state != BootstrapState.INITIALIZING) {
                    return false;
                }
                failure = cause;
                state = BootstrapState.FAILED;
                owner = null;
            }
            completion.countDown();
            return true;
        }

        private void awaitCompletion() {
            try {
                completion.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted waiting for tool descriptor bootstrap", e); //$NON-NLS-1$
            }
            Throwable completedFailure = failure;
            if (completedFailure != null) {
                throw propagate(completedFailure);
            }
        }

        private void leaveExternalBootstrap() {
            externalParticipant.remove();
        }
    }

    private record DescriptorEntry(Object slotIdentity, ToolDescriptor descriptor) {
    }
}
