package com.codepilot1c.core.workspace;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.osgi.framework.Bundle;

/** Native, optional integration with EDT Extension Tweaks. No project files are rewritten. */
public class EdtProjectContextService {
    static final String PLUGIN = "ru.xelgo.edt.contextlinks.ui";
    static final QualifiedName PROPERTY = new QualifiedName(PLUGIN, "contextProjects");

    public interface Gateway {
        void validateProject(String project) throws Exception;
        List<String> readPersistent(String project) throws Exception;
        List<String> readEffective(String project) throws Exception;
        void write(String project, Set<String> contexts) throws Exception;
        void build(String project) throws Exception;
        String pluginVersion() throws Exception;
    }

    public record Snapshot(String project, List<String> persistentContexts, List<String> effectiveContexts,
            boolean cacheMatchesPersistent, String pluginVersion) {}
    public record Outcome(String status, Snapshot before, Snapshot after, List<String> requestedContexts,
            boolean settingsChanged, boolean buildCompleted) {}

    public static class ContextException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String code;
        public ContextException(String code, String message) { super(message); this.code = code; }
        public ContextException(String code, String message, Throwable cause) {
            super(message, cause); this.code = code;
        }
    }

    private final Gateway gateway;
    public EdtProjectContextService() { this(new NativeGateway()); }
    public EdtProjectContextService(Gateway gateway) { this.gateway = gateway; }

    public synchronized Outcome execute(String project, String action, List<?> contexts,
            boolean dryRun, boolean cleanBuild) {
        requireName(project);
        if (!"inspect".equals(action) && !"set".equals(action)) {
            throw new ContextException("INVALID_ARGUMENT", "action must be inspect or set");
        }
        if ("inspect".equals(action) && (contexts != null || cleanBuild)) {
            throw new ContextException("INVALID_ARGUMENT", "inspect does not accept context_projects or clean_build");
        }
        List<String> requested = "set".equals(action) ? validateContexts(project, contexts) : List.of();
        try {
            gateway.validateProject(project);
            // Validate the entire replacement before activating or writing plugin settings.
            for (String context : requested) gateway.validateProject(context);
            Snapshot before = snapshot(project);
            if ("inspect".equals(action)) return new Outcome("inspected", before, before, requested, false, false);
            if (dryRun) return new Outcome("dry_run", before, before, requested, false, false);
            boolean changed = !new LinkedHashSet<>(before.persistentContexts()).equals(new LinkedHashSet<>(requested))
                    || !before.cacheMatchesPersistent();
            Snapshot after;
            try {
                gateway.write(project, new LinkedHashSet<>(requested));
                after = snapshot(project);
                if (!new LinkedHashSet<>(after.persistentContexts()).equals(new LinkedHashSet<>(requested))
                        || !after.cacheMatchesPersistent()) {
                    throw new ContextException("READBACK_MISMATCH", "Native context readback differs from request");
                }
            } catch (Exception failure) {
                try { gateway.write(project, new LinkedHashSet<>(before.persistentContexts())); }
                catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
            if (cleanBuild) {
                try { gateway.build(project); }
                catch (Exception failure) {
                    throw new ContextException("PROJECT_BUILD_FAILED",
                            "Context settings were saved and verified; target project build failed: " + failure.getMessage(), failure);
                }
            }
            return new Outcome("configured", before, after, requested, changed, cleanBuild);
        } catch (ContextException e) { throw e; }
        catch (Exception e) { throw new ContextException("CONTEXT_OPERATION_FAILED", e.getMessage(), e); }
    }

    private Snapshot snapshot(String project) throws Exception {
        List<String> persistent = List.copyOf(gateway.readPersistent(project));
        List<String> effective = List.copyOf(gateway.readEffective(project));
        return new Snapshot(project, persistent, effective,
                new LinkedHashSet<>(persistent).equals(new LinkedHashSet<>(effective)), gateway.pluginVersion());
    }

    static List<String> validateContexts(String project, List<?> contexts) {
        if (contexts == null) throw new ContextException("INVALID_ARGUMENT", "set requires context_projects, including [] to clear");
        Set<String> seen = new LinkedHashSet<>();
        for (Object value : contexts) {
            if (!(value instanceof String name)) throw new ContextException("INVALID_ARGUMENT", "Context names must be strings");
            requireName(name);
            if (project.equals(name)) throw new ContextException("INVALID_ARGUMENT", "Self context is not allowed");
            if (!seen.add(name)) throw new ContextException("INVALID_ARGUMENT", "Duplicate context: " + name);
        }
        return List.copyOf(seen);
    }

    static void requireName(String name) {
        if (name == null || name.isBlank() || !name.equals(name.trim())) {
            throw new ContextException("INVALID_ARGUMENT", "Project name must be nonblank without surrounding whitespace");
        }
    }

    static List<String> parsePersistent(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> names = new ArrayList<>();
        for (String name : value.split("\\R")) if (!name.isBlank()) names.add(name.trim());
        return names;
    }

    static class NativeGateway implements Gateway {
        private IProject project(String name) {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
            if (!project.exists() || !project.isOpen()) {
                throw new ContextException("PROJECT_NOT_READY", "Project must exist and be open: " + name);
            }
            return project;
        }
        @Override public void validateProject(String name) { project(name); }
        @Override public List<String> readPersistent(String name) throws Exception {
            return parsePersistent(project(name).getPersistentProperty(PROPERTY));
        }
        private Bundle plugin() throws Exception {
            Bundle bundle = Platform.getBundle(PLUGIN);
            if (bundle == null) throw new ContextException("CONTEXT_PLUGIN_UNAVAILABLE", "EDT Extension Tweaks is not installed");
            if (bundle.getState() != Bundle.ACTIVE) bundle.start();
            return bundle;
        }
        private Object invoke(String method, Class<?>[] types, Object... args) throws Exception {
            try {
                return plugin().loadClass("ru.xelgo.edt.contextlinks.core.ContextLinks")
                        .getMethod(method, types).invoke(null, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                throw new ContextException("CONTEXT_PLUGIN_FAILURE", "Native plugin invocation failed", e.getCause());
            }
        }
        @Override public List<String> readEffective(String name) throws Exception {
            Object value = invoke("getContextProjectNames", new Class<?>[]{IProject.class}, project(name));
            if (!(value instanceof Set<?> names)) throw new ContextException("CONTEXT_PLUGIN_API_MISMATCH", "Unexpected context names type");
            List<String> result = new ArrayList<>();
            for (Object item : names) {
                if (!(item instanceof String text)) throw new ContextException("CONTEXT_PLUGIN_API_MISMATCH", "Unexpected context name type");
                result.add(text);
            }
            return result;
        }
        @Override public void write(String name, Set<String> contexts) throws Exception {
            invoke("setContextProjectNames", new Class<?>[]{IProject.class, Set.class}, project(name), contexts);
            ResourcesPlugin.getWorkspace().save(false, new NullProgressMonitor());
        }
        @Override public void build(String name) throws Exception {
            IProject target = project(name);
            target.build(IncrementalProjectBuilder.CLEAN_BUILD, new NullProgressMonitor());
            target.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
        }
        @Override public String pluginVersion() throws Exception { return plugin().getVersion().toString(); }
    }
}
