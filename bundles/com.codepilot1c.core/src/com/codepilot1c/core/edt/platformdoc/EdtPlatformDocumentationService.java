package com.codepilot1c.core.edt.platformdoc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.Help;
import com._1c.g5.v8.dt.mcore.HelpPage;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;

/**
 * Query service for platform language documentation from EDT runtime mcore model.
 */
public class EdtPlatformDocumentationService {

    private final EdtMetadataGateway gateway;

    public EdtPlatformDocumentationService() {
        this(new EdtMetadataGateway());
    }

    public EdtPlatformDocumentationService(EdtMetadataGateway gateway) {
        this.gateway = gateway;
    }

    public boolean isEdtAvailable() {
        return gateway.isEdtAvailable();
    }

    public PlatformDocumentationResult getDocumentation(PlatformDocumentationRequest request) {
        request.validate();
        IProject project = requireProject(request.projectName());
        IBmModelManager modelManager = gateway.getBmModelManager();

        try {
            return modelManager.executeReadOnlyTask(project, tx -> doCollect(tx, request));
        } catch (PlatformDocumentationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PlatformDocumentationException(
                    PlatformDocumentationErrorCode.EDT_TRANSACTION_FAILED,
                    "Failed to read platform documentation: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
    }

    private PlatformDocumentationResult doCollect(IBmTransaction transaction, PlatformDocumentationRequest request) {
        List<Type> allTypes = findAllTypes(transaction);
        allTypes.sort(Comparator.comparing(this::safeName, String.CASE_INSENSITIVE_ORDER));

        String query = normalize(request.typeName());
        String contains = request.normalizedContains();
        String lang = request.normalizedLanguage();
        boolean useRussian = "ru".equals(lang); //$NON-NLS-1$

        Type resolvedType = resolveType(allTypes, query);
        List<PlatformDocumentationResult.TypeCandidate> candidates = buildCandidates(allTypes, query);

        if (request.typeName() == null || request.typeName().isBlank()) {
            return new PlatformDocumentationResult(
                    request.projectName(),
                    null,
                    null,
                    null,
                    null,
                    lang,
                    request.memberFilter(),
                    request.limit(),
                    request.offset(),
                    0,
                    0,
                    false,
                    false,
                    candidates,
                    List.of(),
                    List.of());
        }

        if (resolvedType == null) {
            throw new PlatformDocumentationException(
                    PlatformDocumentationErrorCode.TYPE_NOT_FOUND,
                    "Type not found: " + request.typeName(), false); //$NON-NLS-1$
        }

        ContextDef context = resolvedType.getContextDef();
        List<Method> allMethods = context != null ? new ArrayList<>(context.allMethods()) : List.of();
        List<Property> allProperties = context != null ? new ArrayList<>(context.allProperties()) : List.of();

        allMethods.sort(Comparator.comparing(m -> safeString(m.getName()), String.CASE_INSENSITIVE_ORDER));
        allProperties.sort(Comparator.comparing(p -> safeString(p.getName()), String.CASE_INSENSITIVE_ORDER));

        List<Method> filteredMethods = filterMethods(allMethods, contains, useRussian);
        List<Property> filteredProperties = filterProperties(allProperties, contains, useRussian);

        Page<Method> methodsPage = page(filteredMethods, request.offset(), request.limit());
        Page<Property> propertiesPage = page(filteredProperties, request.offset(), request.limit());

        List<PlatformDocumentationResult.MethodDoc> methods = request.memberFilter() == PlatformMemberFilter.PROPERTIES
                ? List.of()
                : mapMethods(methodsPage.items, lang, useRussian);
        List<PlatformDocumentationResult.PropertyDoc> properties = request.memberFilter() == PlatformMemberFilter.METHODS
                ? List.of()
                : mapProperties(propertiesPage.items, lang, useRussian);

        boolean hasMoreMethods = request.memberFilter() == PlatformMemberFilter.PROPERTIES
                ? false
                : methodsPage.hasMore;
        boolean hasMoreProperties = request.memberFilter() == PlatformMemberFilter.METHODS
                ? false
                : propertiesPage.hasMore;

        int totalMethods = request.memberFilter() == PlatformMemberFilter.PROPERTIES ? 0 : filteredMethods.size();
        int totalProperties = request.memberFilter() == PlatformMemberFilter.METHODS ? 0 : filteredProperties.size();

        return new PlatformDocumentationResult(
                request.projectName(),
                request.typeName(),
                resolvedType.getName(),
                resolvedType.getNameRu(),
                safeFqn(resolvedType),
                lang,
                request.memberFilter(),
                request.limit(),
                request.offset(),
                totalMethods,
                totalProperties,
                hasMoreMethods,
                hasMoreProperties,
                candidates,
                methods,
                properties);
    }

    private IProject requireProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new PlatformDocumentationException(
                    PlatformDocumentationErrorCode.PROJECT_NOT_FOUND,
                    "Project not found: " + projectName, false); //$NON-NLS-1$
        }
        return project;
    }

    private List<Type> findAllTypes(IBmTransaction tx) {
        Set<String> seen = new LinkedHashSet<>();
        List<Type> types = new ArrayList<>();

        for (var it = tx.getTopObjectIterator(McorePackage.eINSTANCE.getType()); it.hasNext();) {
            addType(types, seen, it.next());
        }
        for (var it = tx.getContainedObjectIterator(McorePackage.eINSTANCE.getType()); it.hasNext();) {
            addType(types, seen, it.next());
        }
        return types;
    }

    private void addType(List<Type> types, Set<String> seen, IBmObject object) {
        if (!(object instanceof Type type)) {
            return;
        }
        String key = safeFqn(type);
        if (key.isBlank()) {
            key = safeName(type) + "|" + safeNameRu(type); //$NON-NLS-1$
        }
        if (seen.add(key)) {
            types.add(type);
        }
    }

    private Type resolveType(List<Type> types, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return null;
        }

        Type exact = types.stream()
                .filter(t -> normalize(t.getName()).equals(normalizedQuery)
                        || normalize(t.getNameRu()).equals(normalizedQuery)
                        || normalize(safeFqn(t)).equals(normalizedQuery))
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return exact;
        }

        return types.stream()
                .filter(t -> normalize(t.getName()).contains(normalizedQuery)
                        || normalize(t.getNameRu()).contains(normalizedQuery)
                        || normalize(safeFqn(t)).contains(normalizedQuery))
                .findFirst()
                .orElse(null);
    }

    private List<PlatformDocumentationResult.TypeCandidate> buildCandidates(List<Type> allTypes, String normalizedQuery) {
        return allTypes.stream()
                .filter(t -> normalizedQuery == null
                        || normalizedQuery.isBlank()
                        || normalize(t.getName()).contains(normalizedQuery)
                        || normalize(t.getNameRu()).contains(normalizedQuery)
                        || normalize(safeFqn(t)).contains(normalizedQuery))
                .limit(30)
                .map(t -> new PlatformDocumentationResult.TypeCandidate(t.getName(), t.getNameRu(), safeFqn(t)))
                .toList();
    }

    private List<Method> filterMethods(List<Method> methods, String contains, boolean useRussian) {
        if (contains == null || contains.isBlank()) {
            return methods;
        }
        return methods.stream()
                .filter(m -> contains(normalize(m.getName()), contains)
                        || contains(normalize(m.getNameRu()), contains)
                        || contains(normalize(resolveMemberName(m.getName(), m.getNameRu(), useRussian)), contains))
                .toList();
    }

    private List<Property> filterProperties(List<Property> properties, String contains, boolean useRussian) {
        if (contains == null || contains.isBlank()) {
            return properties;
        }
        return properties.stream()
                .filter(p -> contains(normalize(p.getName()), contains)
                        || contains(normalize(p.getNameRu()), contains)
                        || contains(normalize(resolveMemberName(p.getName(), p.getNameRu(), useRussian)), contains))
                .toList();
    }

    private List<PlatformDocumentationResult.MethodDoc> mapMethods(List<Method> methods, String language, boolean useRussian) {
        List<PlatformDocumentationResult.MethodDoc> result = new ArrayList<>();
        for (Method method : methods) {
            List<PlatformDocumentationResult.ParamSetDoc> paramSets = new ArrayList<>();
            for (ParamSet paramSet : method.getParamSet()) {
                List<PlatformDocumentationResult.ParameterDoc> params = new ArrayList<>();
                for (Parameter parameter : paramSet.getParams()) {
                    params.add(new PlatformDocumentationResult.ParameterDoc(
                            resolveMemberName(parameter.getName(), parameter.getNameRu(), useRussian),
                            parameter.getNameRu(),
                            parameter.isOut(),
                            parameter.isDefaultValue(),
                            mapTypeNames(parameter.getType(), useRussian)));
                }
                paramSets.add(new PlatformDocumentationResult.ParamSetDoc(
                        paramSet.getMinParams(),
                        paramSet.getMaxParams(),
                        params));
            }

            result.add(new PlatformDocumentationResult.MethodDoc(
                    resolveMemberName(method.getName(), method.getNameRu(), useRussian),
                    method.getNameRu(),
                    method.isRetVal(),
                    mapTypeNames(method.getRetValType(), useRussian),
                    paramSets,
                    extractHelp(method, language)));
        }
        return result;
    }

    private List<PlatformDocumentationResult.PropertyDoc> mapProperties(
            List<Property> properties,
            String language,
            boolean useRussian) {
        List<PlatformDocumentationResult.PropertyDoc> result = new ArrayList<>();
        for (Property property : properties) {
            result.add(new PlatformDocumentationResult.PropertyDoc(
                    resolveMemberName(property.getName(), property.getNameRu(), useRussian),
                    property.getNameRu(),
                    property.isReadable(),
                    property.isWritable(),
                    mapTypeNames(property.getTypes(), useRussian),
                    extractHelp(property, language)));
        }
        return result;
    }

    private List<String> mapTypeNames(Collection<TypeItem> typeItems, boolean useRussian) {
        List<String> result = new ArrayList<>();
        for (TypeItem typeItem : typeItems) {
            String name = useRussian ? firstNonBlank(typeItem.getNameRu(), typeItem.getName())
                    : firstNonBlank(typeItem.getName(), typeItem.getNameRu());
            if (name != null && !name.isBlank() && !result.contains(name)) {
                result.add(name);
            }
        }
        return result;
    }

    private Map<String, Object> extractHelp(EObject object, String language) {
        try {
            EStructuralFeature helpFeature = object.eClass().getEStructuralFeature("help"); //$NON-NLS-1$
            if (helpFeature == null) {
                return Map.of();
            }
            Object helpValue = object.eGet(helpFeature);
            if (!(helpValue instanceof Help help)) {
                return Map.of();
            }
            if (help == null || help.getPages().isEmpty()) {
                return Map.of();
            }
            String lang = language == null || language.isBlank() ? "ru" : language.toLowerCase(Locale.ROOT); //$NON-NLS-1$
            HelpPage page = help.getPages().stream()
                    .filter(p -> lang.equalsIgnoreCase(p.getLang()))
                    .findFirst()
                    .orElse(help.getPages().get(0));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("lang", page.getLang()); //$NON-NLS-1$
            for (EStructuralFeature feature : page.eClass().getEAllStructuralFeatures()) {
                if ("lang".equals(feature.getName())) { //$NON-NLS-1$
                    continue;
                }
                Object featureValue = page.eGet(feature);
                if (featureValue == null) {
                    continue;
                }
                if (featureValue instanceof String text && !text.isBlank()) {
                    details.put(feature.getName(), text);
                } else if (featureValue instanceof Collection<?> collection && !collection.isEmpty()) {
                    details.put(feature.getName(), collection.stream().map(String::valueOf).toList());
                }
            }
            return details;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private <T> Page<T> page(List<T> source, int offset, int limit) {
        if (source.isEmpty()) {
            return new Page<>(List.of(), false);
        }
        int from = Math.min(offset, source.size());
        int to = Math.min(from + limit, source.size());
        List<T> items = source.subList(from, to);
        boolean hasMore = to < source.size();
        return new Page<>(items, hasMore);
    }

    private boolean contains(String value, String query) {
        return value != null && query != null && value.contains(query);
    }

    private String resolveMemberName(String name, String nameRu, boolean useRussian) {
        return useRussian ? firstNonBlank(nameRu, name) : firstNonBlank(name, nameRu);
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private String safeName(Type type) {
        return type != null ? safeString(type.getName()) : ""; //$NON-NLS-1$
    }

    private String safeNameRu(Type type) {
        return type != null ? safeString(type.getNameRu()) : ""; //$NON-NLS-1$
    }

    private String safeFqn(Type type) {
        if (type instanceof IBmObject bmObject) {
            String fqn = bmObject.bmGetFqn();
            return fqn != null ? fqn : ""; //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    private String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String safeString(String value) {
        return value != null ? value : ""; //$NON-NLS-1$
    }

    private record Page<T>(List<T> items, boolean hasMore) {
    }
}
