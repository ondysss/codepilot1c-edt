package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Scans top-level metadata objects from EDT configuration.
 */
public class EdtMetadataIndexService {

    private static final List<String> OBJECT_MODULE_FEATURES = List.of(
            "objectModule", //$NON-NLS-1$
            "module", //$NON-NLS-1$
            "recordSetModule", //$NON-NLS-1$
            "valueManagerModule" //$NON-NLS-1$
    );

    private final EdtServiceGateway gateway;
    private final ProjectReadinessChecker readinessChecker;

    public EdtMetadataIndexService(EdtServiceGateway gateway, ProjectReadinessChecker readinessChecker) {
        this.gateway = gateway;
        this.readinessChecker = readinessChecker;
    }

    public MetadataIndexResult scan(MetadataIndexRequest request) {
        request.validate();

        IProject project = gateway.resolveProject(request.projectName());
        readinessChecker.ensureReady(project);

        IConfigurationProvider configProvider = gateway.getConfigurationProvider();
        Configuration configuration = configProvider.getConfiguration(project);
        if (configuration == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Configuration is unavailable for project", false); //$NON-NLS-1$
        }

        String scope = request.normalizedScope();
        String nameFilter = request.normalizedNameContains();
        String language = resolveLanguage(configuration, request.normalizedLanguage());

        List<MetadataIndexResult.Item> collected = collect(configuration, scope, nameFilter, language);
        collected.sort(Comparator
                .comparing(MetadataIndexResult.Item::getKind, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MetadataIndexResult.Item::getName, String.CASE_INSENSITIVE_ORDER));

        int total = collected.size();
        int returned = Math.min(request.limit(), total);
        boolean hasMore = total > returned;
        List<MetadataIndexResult.Item> page = returned == total
                ? collected
                : new ArrayList<>(collected.subList(0, returned));

        return new MetadataIndexResult(
                request.projectName(),
                "edt_configuration_scan", //$NON-NLS-1$
                scope,
                language,
                total,
                returned,
                hasMore,
                page);
    }

    private List<MetadataIndexResult.Item> collect(
            Configuration configuration,
            String scope,
            String nameFilter,
            String language) {
        List<MetadataIndexResult.Item> items = new ArrayList<>();

        for (EReference reference : configuration.eClass().getEAllReferences()) {
            if (!reference.isContainment() || !reference.isMany()) {
                continue;
            }
            if (reference.isDerived() || reference.isTransient() || reference.isVolatile()) {
                continue;
            }
            Object raw = configuration.eGet(reference);
            if (!(raw instanceof Collection<?> collection) || collection.isEmpty()) {
                continue;
            }

            String collectionToken = normalize(reference.getName());
            String singularToken = singularize(collectionToken);

            for (Object element : collection) {
                if (!(element instanceof MdObject mdObject)) {
                    continue;
                }
                String kind = mdObject.eClass().getName();
                String normalizedKind = normalize(kind);
                if (!matchesScope(scope, collectionToken, singularToken, normalizedKind)) {
                    continue;
                }
                String name = safe(mdObject.getName());
                if (!nameFilter.isEmpty() && !normalize(name).contains(nameFilter)) {
                    continue;
                }

                items.add(new MetadataIndexResult.Item(
                        safeFqn(mdObject, kind, name),
                        name,
                        resolveSynonym(mdObject.getSynonym(), language),
                        safe(mdObject.getComment()),
                        kind,
                        reference.getName(),
                        hasAnyFeature(mdObject, OBJECT_MODULE_FEATURES),
                        hasAnyFeature(mdObject, List.of("managerModule")))); //$NON-NLS-1$
            }
        }

        return items;
    }

    private boolean matchesScope(String scope, String collectionToken, String singularToken, String normalizedKind) {
        if (scope == null || scope.isBlank() || "all".equals(scope)) { //$NON-NLS-1$
            return true;
        }
        return scope.equals(collectionToken) || scope.equals(singularToken) || scope.equals(normalizedKind);
    }

    private boolean hasAnyFeature(MdObject object, List<String> featureNames) {
        for (String featureName : featureNames) {
            EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
            if (feature == null) {
                continue;
            }
            Object value = object.eGet(feature);
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    private String resolveLanguage(Configuration configuration, String requestedLanguage) {
        if (requestedLanguage != null && !requestedLanguage.isBlank()) {
            return requestedLanguage;
        }
        if (configuration.getDefaultLanguage() != null && configuration.getDefaultLanguage().getName() != null) {
            return configuration.getDefaultLanguage().getName().toLowerCase(Locale.ROOT);
        }
        return "ru"; //$NON-NLS-1$
    }

    private String resolveSynonym(EMap<String, String> synonymMap, String language) {
        if (synonymMap == null || synonymMap.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        if (language != null) {
            String direct = synonymMap.get(language);
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
        }
        for (Map.Entry<String, String> entry : synonymMap.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                return entry.getValue();
            }
        }
        return ""; //$NON-NLS-1$
    }

    private String safeFqn(MdObject object, String kind, String name) {
        if (object instanceof IBmObject bmObject) {
            IBmObject top = bmObject;
            if (!top.bmIsTop()) {
                top = top.bmGetTopObject();
            }
            if (top != null && top.bmIsTop()) {
                String fqn = top.bmGetFqn();
                if (fqn != null && !fqn.isBlank()) {
                    return fqn;
                }
            }
        }
        return kind + "." + name; //$NON-NLS-1$
    }

    private String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String singularize(String value) {
        if (value == null || value.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        if (value.endsWith("ies")) { //$NON-NLS-1$
            return value.substring(0, value.length() - 3) + "y"; //$NON-NLS-1$
        }
        if (value.endsWith("s") && !value.endsWith("ss")) { //$NON-NLS-1$ //$NON-NLS-2$
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String safe(String value) {
        return value != null ? value : ""; //$NON-NLS-1$
    }
}
