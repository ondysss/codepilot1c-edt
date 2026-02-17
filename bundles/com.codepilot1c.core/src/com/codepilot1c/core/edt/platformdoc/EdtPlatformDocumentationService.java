package com.codepilot1c.core.edt.platformdoc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.IEObjectDescription;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
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

    private static final String RU_LANGUAGE = "ru"; //$NON-NLS-1$
    private static final Map<String, String> TYPE_QUERY_ALIASES = createTypeQueryAliases();

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
        try {
            List<Type> allTypes = collectAllTypes(project);
            return doCollect(allTypes, request);
        } catch (PlatformDocumentationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PlatformDocumentationException(
                    PlatformDocumentationErrorCode.EDT_TRANSACTION_FAILED,
                    "Failed to read platform documentation: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
    }

    private List<Type> collectAllTypes(IProject project) {
        List<Type> providerTypes = findAllTypesFromProvider(project);
        if (!providerTypes.isEmpty()) {
            return providerTypes;
        }
        IBmModelManager modelManager = gateway.getBmModelManager();
        return modelManager.executeReadOnlyTask(project, this::findAllTypesFromTransaction);
    }

    private PlatformDocumentationResult doCollect(List<Type> allTypes, PlatformDocumentationRequest request) {
        if (allTypes.isEmpty()) {
            throw new PlatformDocumentationException(
                    PlatformDocumentationErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Platform type index is unavailable in EDT runtime for project: " + request.projectName()
                            + ". IEObjectProvider and BM transaction returned no platform types.", true); //$NON-NLS-1$
        }
        allTypes.sort(Comparator.comparing(this::safeName, String.CASE_INSENSITIVE_ORDER));

        String query = normalize(request.typeName());
        String contains = request.normalizedContains();
        String lang = request.normalizedLanguage();
        boolean useRussian = RU_LANGUAGE.equals(lang);

        TypeLookup lookup = resolveTypeLookup(allTypes, query, contains, useRussian);
        Type resolvedType = lookup.resolvedType();
        String effectiveContains = lookup.contains();
        boolean includeCandidates = request.memberFilter() == PlatformMemberFilter.ALL;
        List<PlatformDocumentationResult.TypeCandidate> candidates = includeCandidates
                ? buildCandidates(allTypes, query, request.limit())
                : List.of();

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
                    "Type not found: " + request.typeName()
                            + ". Pass platform type in 'type_name' and method/property in 'contains'.", true); //$NON-NLS-1$
        }

        ContextDef context = resolvedType.getContextDef();
        List<Method> allMethods = context != null ? new ArrayList<>(context.allMethods()) : List.of();
        List<Property> allProperties = context != null ? new ArrayList<>(context.allProperties()) : List.of();

        allMethods.sort(Comparator.comparing(m -> safeString(m.getName()), String.CASE_INSENSITIVE_ORDER));
        allProperties.sort(Comparator.comparing(p -> safeString(p.getName()), String.CASE_INSENSITIVE_ORDER));

        List<Method> filteredMethods = filterMethods(allMethods, effectiveContains, useRussian);
        List<Property> filteredProperties = filterProperties(allProperties, effectiveContains, useRussian);

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

    private TypeLookup resolveTypeLookup(List<Type> types, String query, String contains, boolean useRussian) {
        Type resolved = resolveTypeByName(types, query);
        String effectiveContains = contains;
        if (resolved == null && (contains == null || contains.isBlank()) && query != null && !query.isBlank()) {
            // Compatibility fallback: models sometimes pass member name in type_name.
            // Try to infer owning type by method/property name and reuse the query as member filter.
            resolved = resolveTypeByMemberName(types, query, useRussian);
            effectiveContains = query;
        }
        return new TypeLookup(resolved, effectiveContains);
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

    private List<Type> findAllTypesFromProvider(IProject project) {
        Version version = gateway.resolvePlatformVersion(project);
        IEObjectProvider.Registry registry = IEObjectProvider.Registry.INSTANCE;
        IEObjectProvider typeProvider = registry.get(McorePackage.Literals.TYPE, version);
        IEObjectProvider typeItemProvider = registry.get(McorePackage.Literals.TYPE_ITEM, version);
        if (!hasDescriptions(typeProvider) && hasDescriptions(typeItemProvider)) {
            typeProvider = typeItemProvider;
        }
        if (typeProvider == null) {
            return List.of();
        }

        Iterable<IEObjectDescription> descriptions = typeProvider.getEObjectDescriptions(null);
        if (descriptions == null) {
            return List.of();
        }

        ResourceSet resourceSet = resolveResourceSet(project);
        Set<String> seen = new LinkedHashSet<>();
        List<Type> types = new ArrayList<>();
        for (IEObjectDescription description : descriptions) {
            if (description == null) {
                continue;
            }
            EObject candidate = description.getEObjectOrProxy();
            EObject resolved = resolveEObject(candidate, resourceSet);
            if (resolved instanceof Type type && !resolved.eIsProxy()) {
                addType(types, seen, type);
            }
        }
        return types;
    }

    private boolean hasDescriptions(IEObjectProvider provider) {
        if (provider == null) {
            return false;
        }
        Iterable<IEObjectDescription> descriptions = provider.getEObjectDescriptions(null);
        return descriptions != null && descriptions.iterator().hasNext();
    }

    private ResourceSet resolveResourceSet(IProject project) {
        try {
            return gateway.getResourceSetProvider().get(project);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private EObject resolveEObject(EObject object, ResourceSet resourceSet) {
        if (object == null) {
            return null;
        }
        if (!object.eIsProxy()) {
            return object;
        }
        try {
            if (resourceSet != null) {
                EObject resolved = EcoreUtil.resolve(object, resourceSet);
                if (resolved != null && !resolved.eIsProxy()) {
                    return resolved;
                }
            }
            EObject resolved = EcoreUtil.resolve(object, new ResourceSetImpl());
            return resolved != null ? resolved : object;
        } catch (RuntimeException e) {
            return object;
        }
    }

    private List<Type> findAllTypesFromTransaction(IBmTransaction tx) {
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
        if (object instanceof Type type) {
            addType(types, seen, type);
        }
    }

    private void addType(List<Type> types, Set<String> seen, Type type) {
        String key = safeFqn(type);
        if (key.isBlank()) {
            key = safeName(type) + "|" + safeNameRu(type); //$NON-NLS-1$
        }
        if (seen.add(key)) {
            types.add(type);
        }
    }

    private Type resolveTypeByName(List<Type> types, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return null;
        }

        for (String query : expandTypeQueries(normalizedQuery)) {
            Type exact = types.stream()
                    .filter(t -> normalize(t.getName()).equals(query)
                            || normalize(t.getNameRu()).equals(query)
                            || normalize(safeFqn(t)).equals(query))
                    .findFirst()
                    .orElse(null);
            if (exact != null) {
                return exact;
            }
        }

        for (String query : expandTypeQueries(normalizedQuery)) {
            Type matched = types.stream()
                    .filter(t -> normalize(t.getName()).contains(query)
                            || normalize(t.getNameRu()).contains(query)
                            || normalize(safeFqn(t)).contains(query))
                    .findFirst()
                    .orElse(null);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    private Type resolveTypeByMemberName(List<Type> types, String memberQuery, boolean useRussian) {
        if (memberQuery == null || memberQuery.isBlank()) {
            return null;
        }

        for (Type type : types) {
            ContextDef context = type.getContextDef();
            if (context == null) {
                continue;
            }
            boolean hasExactMethod = hasMatchingMember(
                    context.allMethods(),
                    memberQuery,
                    useRussian,
                    Method::getName,
                    Method::getNameRu,
                    true);
            boolean hasExactProperty = hasMatchingMember(
                    context.allProperties(),
                    memberQuery,
                    useRussian,
                    Property::getName,
                    Property::getNameRu,
                    true);
            if (hasExactMethod || hasExactProperty) {
                return type;
            }
        }

        for (Type type : types) {
            ContextDef context = type.getContextDef();
            if (context == null) {
                continue;
            }
            boolean hasMethod = hasMatchingMember(
                    context.allMethods(),
                    memberQuery,
                    useRussian,
                    Method::getName,
                    Method::getNameRu,
                    false);
            boolean hasProperty = hasMatchingMember(
                    context.allProperties(),
                    memberQuery,
                    useRussian,
                    Property::getName,
                    Property::getNameRu,
                    false);
            if (hasMethod || hasProperty) {
                return type;
            }
        }
        return null;
    }

    private boolean memberMatchesExactly(String name, String nameRu, String query, boolean useRussian) {
        String preferred = normalize(resolveMemberName(name, nameRu, useRussian));
        return preferred.equals(query)
                || normalize(name).equals(query)
                || normalize(nameRu).equals(query);
    }

    private boolean memberContains(String name, String nameRu, String query, boolean useRussian) {
        String preferred = normalize(resolveMemberName(name, nameRu, useRussian));
        return contains(preferred, query)
                || contains(normalize(name), query)
                || contains(normalize(nameRu), query);
    }

    private List<PlatformDocumentationResult.TypeCandidate> buildCandidates(
            List<Type> allTypes,
            String normalizedQuery,
            int limit
    ) {
        int safeLimit = Math.max(1, limit);
        return allTypes.stream()
                .filter(t -> normalizedQuery == null
                        || normalizedQuery.isBlank()
                        || normalize(t.getName()).contains(normalizedQuery)
                        || normalize(t.getNameRu()).contains(normalizedQuery)
                        || normalize(safeFqn(t)).contains(normalizedQuery))
                .limit(safeLimit)
                .map(t -> new PlatformDocumentationResult.TypeCandidate(t.getName(), t.getNameRu(), safeFqn(t)))
                .toList();
    }

    private List<Method> filterMethods(List<Method> methods, String contains, boolean useRussian) {
        return filterMembers(methods, contains, useRussian, Method::getName, Method::getNameRu);
    }

    private List<Property> filterProperties(List<Property> properties, String contains, boolean useRussian) {
        return filterMembers(properties, contains, useRussian, Property::getName, Property::getNameRu);
    }

    private <T> List<T> filterMembers(
            List<T> members,
            String contains,
            boolean useRussian,
            Function<T, String> nameExtractor,
            Function<T, String> nameRuExtractor
    ) {
        if (contains == null || contains.isBlank()) {
            return members;
        }
        return members.stream()
                .filter(member -> memberContains(
                        nameExtractor.apply(member),
                        nameRuExtractor.apply(member),
                        contains,
                        useRussian))
                .toList();
    }

    private <T> boolean hasMatchingMember(
            Collection<T> members,
            String query,
            boolean useRussian,
            Function<T, String> nameExtractor,
            Function<T, String> nameRuExtractor,
            boolean exact
    ) {
        for (T member : members) {
            String name = nameExtractor.apply(member);
            String nameRu = nameRuExtractor.apply(member);
            boolean matched = exact
                    ? memberMatchesExactly(name, nameRu, query, useRussian)
                    : memberContains(name, nameRu, query, useRussian);
            if (matched) {
                return true;
            }
        }
        return false;
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
            try {
                if (!bmObject.bmIsTransient()) {
                    IBmObject top = bmObject;
                    if (!top.bmIsTop()) {
                        top = top.bmGetTopObject();
                    }
                    if (top != null && !top.bmIsTransient() && top.bmIsTop()) {
                        String fqn = top.bmGetFqn();
                        return fqn != null ? fqn : ""; //$NON-NLS-1$
                    }
                }
            } catch (RuntimeException e) {
                // Detached BM object may throw on bmGetFqn()/bmGetTopObject(); return empty FQN.
            }
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

    private record TypeLookup(Type resolvedType, String contains) {
    }

    private static Map<String, String> createTypeQueryAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("запрос", "query"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("результатзапроса", "queryresult"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("выборкаизрезультатазапроса", "queryresultselection"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("построительзапроса", "querybuilder"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("таблицазначений", "valuetable"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("строкатаблицызначений", "valuetablerow"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("деревозначений", "valuetree"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("структура", "structure"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("соответствие", "map"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("массив", "array"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("фиксированныймассив", "fixedarray"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("фиксированнаяструктура", "fixedstructure"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("списокзначений", "valuelist"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("описаниеоповещения", "notificationdescription"); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableMap(aliases);
    }

    private List<String> expandTypeQueries(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return List.of();
        }
        String compact = compactToken(normalizedQuery);
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(normalizedQuery);
        queries.add(compact);
        String alias = TYPE_QUERY_ALIASES.get(compact);
        if (alias != null && !alias.isBlank()) {
            queries.add(alias);
            queries.add(alias.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(queries);
    }

    private String compactToken(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        String lowered = value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder sb = new StringBuilder(lowered.length());
        for (int i = 0; i < lowered.length(); i++) {
            char ch = lowered.charAt(i);
            if (Character.isWhitespace(ch) || ch == '_' || ch == '-' || ch == '.') {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }
}
