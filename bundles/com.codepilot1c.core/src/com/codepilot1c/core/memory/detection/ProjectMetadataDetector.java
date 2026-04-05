/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.detection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.version.Version;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.memory.detection.ProjectContext.ProjectType;

/**
 * Auto-detects project context using EDT project model APIs.
 *
 * <p>Does NOT parse filesystem - uses {@link IConfigurationProvider},
 * {@link IV8ProjectManager}, and extension APIs for all metadata reads.</p>
 *
 * <p>Results are cached per project path with a 60-second TTL.</p>
 *
 * <p>Detection is split into two branches:</p>
 * <ul>
 *   <li>Base configuration: all fields from {@code IConfigurationProvider} on this project</li>
 *   <li>Extension project: extension fields from own config, base fields from parent project</li>
 * </ul>
 */
public final class ProjectMetadataDetector {

    private static final ILog LOG = Platform.getLog(ProjectMetadataDetector.class);
    private static final long CACHE_TTL_MS = 60_000;

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private record CacheEntry(ProjectContext context, Instant expiry) {
        boolean isExpired() { return Instant.now().isAfter(expiry); }
    }

    private ProjectMetadataDetector() {
    }

    /**
     * Returns cached context or detects fresh. Thread-safe.
     *
     * @param projectPath absolute project path
     * @return detected context, or null if detection fails
     */
    public static ProjectContext getCached(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return null;
        }

        CacheEntry entry = CACHE.get(projectPath);
        if (entry != null && !entry.isExpired()) {
            return entry.context();
        }

        IProject project = ProjectResolver.resolve(projectPath);
        if (project == null) {
            return null;
        }

        ProjectContext ctx = detect(project);
        if (ctx != null) {
            CACHE.put(projectPath, new CacheEntry(ctx, Instant.now().plusMillis(CACHE_TTL_MS)));
        }
        return ctx;
    }

    /**
     * Invalidates cache for the given project path.
     */
    public static void invalidate(String projectPath) {
        if (projectPath != null) {
            CACHE.remove(projectPath);
        }
    }

    /**
     * Invalidates all cached entries.
     */
    public static void invalidateAll() {
        CACHE.clear();
    }

    /**
     * Detects project context from EDT project model.
     *
     * @param project the Eclipse project
     * @return detected context, or null if EDT is not available
     */
    public static ProjectContext detect(IProject project) {
        if (project == null || !project.exists() || !project.isOpen()) {
            return null;
        }

        try {
            EdtMetadataGateway gateway = new EdtMetadataGateway();
            gateway.ensureValidationRuntimeAvailable();

            boolean isExtension = isExtensionProject(project, gateway);

            if (isExtension) {
                return detectExtensionProject(project, gateway);
            } else {
                return detectBaseConfigProject(project, gateway);
            }
        } catch (Exception e) {
            LOG.warn("Project metadata detection failed for: " + project.getName(), e); //$NON-NLS-1$
            return null;
        }
    }

    // --- Base configuration detection ---

    private static ProjectContext detectBaseConfigProject(IProject project, EdtMetadataGateway gateway) {
        ProjectContext ctx = new ProjectContext();
        ctx.setProjectType(ProjectType.BASE_CONFIGURATION);

        Configuration config = getConfiguration(project, gateway);
        if (config == null) {
            return ctx;
        }

        fillConfigurationFields(ctx, config);

        // Platform version with "unknown" fallback instead of Version.LATEST
        Version v = gateway.resolvePlatformVersion(project);
        ctx.setPlatformVersion(v != null && v != Version.LATEST ? v.toString() : "unknown"); //$NON-NLS-1$

        // Extensions in workspace that extend this config
        ctx.setExtensions(detectExtensionsForBase(project, gateway));
        ctx.setHasExtensions(!ctx.getExtensions().isEmpty());

        // Library detection
        ctx.setLibraries(LibraryDetector.detectAll(config));

        return ctx;
    }

    // --- Extension project detection ---

    private static ProjectContext detectExtensionProject(IProject project, EdtMetadataGateway gateway) {
        ProjectContext ctx = new ProjectContext();
        ctx.setProjectType(ProjectType.EXTENSION);

        IExtensionProject extProject = getExtensionProject(project, gateway);
        if (extProject == null) {
            return ctx;
        }

        // Extension's own configuration
        Configuration extConfig = getConfiguration(project, gateway);
        if (extConfig != null) {
            ctx.setExtensionName(safe(extConfig.getName()));
            ctx.setExtensionSynonym(extractSynonym(extConfig));
            if (extConfig.getConfigurationExtensionPurpose() != null) {
                ctx.setExtensionPurpose(extConfig.getConfigurationExtensionPurpose().name());
            }
            if (extConfig.getConfigurationExtensionCompatibilityMode() != null) {
                ctx.setExtensionCompatibilityMode(
                        extConfig.getConfigurationExtensionCompatibilityMode().name());
            }
            // Object counts from extension (what this extension contains/adopts)
            ctx.setExtensionDocumentCount(countMdCollection(extConfig.getDocuments()));
            ctx.setExtensionCatalogCount(countMdCollection(extConfig.getCatalogs()));
        }

        // Parent/base configuration fields
        IProject parentProject = extProject.getParentProject();
        if (parentProject != null) {
            Configuration baseConfig = getConfiguration(parentProject, gateway);
            if (baseConfig != null) {
                fillConfigurationFields(ctx, baseConfig);
                ctx.setLibraries(LibraryDetector.detectAll(baseConfig));
            }

            Version v = gateway.resolvePlatformVersion(parentProject);
            ctx.setPlatformVersion(v != null && v != Version.LATEST ? v.toString() : "unknown"); //$NON-NLS-1$
        }

        return ctx;
    }

    // --- Shared field population ---

    private static void fillConfigurationFields(ProjectContext ctx, Configuration config) {
        ctx.setConfigurationName(safe(config.getName()));
        ctx.setConfigurationSynonym(extractSynonym(config));
        ctx.setCompatibilityMode(config.getCompatibilityMode() != null
                ? config.getCompatibilityMode().name() : ""); //$NON-NLS-1$
        ctx.setTypical(detectIsTypical(config));

        ctx.setDocumentCount(countMdCollection(config.getDocuments()));
        ctx.setCatalogCount(countMdCollection(config.getCatalogs()));
        ctx.setRegisterCount(countMdCollection(config.getInformationRegisters())
                + countMdCollection(config.getAccumulationRegisters()));
        ctx.setHasHttpServices(!config.getHttpServices().isEmpty());
        ctx.setHasWebServices(!config.getWebServices().isEmpty());
        ctx.setHasManagedForms(detectManagedForms(config));

        List<String> subsystems = new ArrayList<>();
        config.getSubsystems().forEach(s -> {
            if (s != null && s.getName() != null) {
                subsystems.add(s.getName());
            }
        });
        ctx.setSubsystems(subsystems);
    }

    // --- Helpers ---

    private static Configuration getConfiguration(IProject project, EdtMetadataGateway gateway) {
        try {
            IConfigurationProvider provider = gateway.getConfigurationProvider();
            return provider.getConfiguration(project);
        } catch (Exception e) {
            LOG.warn("Cannot get configuration for: " + project.getName(), e); //$NON-NLS-1$
            return null;
        }
    }

    private static boolean isExtensionProject(IProject project, EdtMetadataGateway gateway) {
        try {
            IV8ProjectManager v8pm = gateway.getV8ProjectManager();
            IV8Project v8Project = v8pm.getProject(project);
            return v8Project instanceof IExtensionProject;
        } catch (Exception e) {
            return false;
        }
    }

    private static IExtensionProject getExtensionProject(IProject project, EdtMetadataGateway gateway) {
        try {
            IV8ProjectManager v8pm = gateway.getV8ProjectManager();
            IV8Project v8Project = v8pm.getProject(project);
            if (v8Project instanceof IExtensionProject ext) {
                return ext;
            }
        } catch (Exception e) {
            LOG.warn("Cannot resolve extension project: " + project.getName(), e); //$NON-NLS-1$
        }
        return null;
    }

    private static List<String> detectExtensionsForBase(IProject baseProject, EdtMetadataGateway gateway) {
        List<String> result = new ArrayList<>();
        try {
            IV8ProjectManager v8pm = gateway.getV8ProjectManager();
            for (IExtensionProject ext : v8pm.getProjects(IExtensionProject.class)) {
                if (ext == null || ext.getProject() == null) {
                    continue;
                }
                IProject parent = ext.getParentProject();
                if (parent != null && parent.equals(baseProject)) {
                    result.add(ext.getProject().getName());
                }
            }
        } catch (Exception e) {
            // Extension runtime not available — return empty list
        }
        return result;
    }

    private static boolean detectIsTypical(Configuration config) {
        if (config == null) {
            return false;
        }
        String name = config.getName();
        Set<String> typicalNames = Set.of(
                "\u0423\u043F\u0440\u0430\u0432\u043B\u0435\u043D\u0438\u0435\u0422\u043E\u0440\u0433\u043E\u0432\u043B\u0435\u0439", // УправлениеТорговлей
                "\u0411\u0443\u0445\u0433\u0430\u043B\u0442\u0435\u0440\u0438\u044F", // Бухгалтерия
                "\u0417\u0430\u0440\u043F\u043B\u0430\u0442\u0430\u0418\u0423\u043F\u0440\u0430\u0432\u043B\u0435\u043D\u0438\u0435\u041F\u0435\u0440\u0441\u043E\u043D\u0430\u043B\u043E\u043C", // ЗарплатаИУправлениеПерсоналом
                "\u0423\u043F\u0440\u0430\u0432\u043B\u0435\u043D\u0438\u0435\u041F\u0440\u0435\u0434\u043F\u0440\u0438\u044F\u0442\u0438\u0435\u043C", // УправлениеПредприятием
                "\u0420\u043E\u0437\u043D\u0438\u0446\u0430", // Розница
                "\u0420\u043E\u0437\u043D\u0438\u0446\u0430\u0411\u0430\u0437\u043E\u0432\u0430\u044F", // РозницаБазовая
                "\u0423\u043F\u0440\u0430\u0432\u043B\u0435\u043D\u0438\u0435\u041D\u0435\u0431\u043E\u043B\u044C\u0448\u043E\u0439\u0424\u0438\u0440\u043C\u043E\u0439", // УправлениеНебольшойФирмой
                "\u0415\u0420\u041F", // ЕРП
                "\u041A\u043E\u043C\u043F\u043B\u0435\u043A\u0441\u043D\u0430\u044F\u0410\u0432\u0442\u043E\u043C\u0430\u0442\u0438\u0437\u0430\u0446\u0438\u044F" // КомплекснаяАвтоматизация
        );
        if (typicalNames.contains(name)) {
            return true;
        }

        // Vendor "1С" + has BSP modules = likely typical
        String vendor = extractVendor(config);
        if ("\u0031\u0421".equals(vendor) || "1C".equals(vendor)) { //$NON-NLS-1$
            long bspHits = config.getCommonModules().stream()
                    .filter(m -> m != null && "\u041E\u0431\u0449\u0435\u0433\u043E\u041D\u0430\u0437\u043D\u0430\u0447\u0435\u043D\u0438\u044F".equals(m.getName())) // ОбщегоНазначения
                    .count();
            return bspHits > 0;
        }

        return false;
    }

    private static boolean detectManagedForms(Configuration config) {
        // Modern 1C configurations (8.3+) universally use managed forms.
        // Presence of common forms is a reliable signal.
        try {
            return !config.getCommonForms().isEmpty();
        } catch (Exception e) {
            return true; // default to managed for modern configs
        }
    }

    private static int countMdCollection(EList<?> collection) {
        return collection != null ? collection.size() : 0;
    }

    private static String extractSynonym(Configuration config) {
        try {
            var synonymMap = config.getSynonym();
            if (synonymMap == null || synonymMap.isEmpty()) {
                return null;
            }
            // Try Russian first, then any available language
            String ru = synonymMap.get("ru"); //$NON-NLS-1$
            if (ru != null && !ru.isBlank()) {
                return ru;
            }
            for (var entry : synonymMap.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    return entry.getValue();
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static String extractVendor(Configuration config) {
        try {
            String vendor = config.getVendor();
            if (vendor != null && !vendor.isBlank()) {
                return vendor.strip();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static String safe(String s) {
        return s != null ? s : ""; //$NON-NLS-1$
    }
}
