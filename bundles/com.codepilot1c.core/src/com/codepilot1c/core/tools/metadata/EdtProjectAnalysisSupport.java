package com.codepilot1c.core.tools.metadata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Shared implementation for read-only EDT project analysis tools.
 */
public final class EdtProjectAnalysisSupport {

    static final Gson GSON = new Gson();

    private static final QualifiedName TAGS_PROPERTY =
            new QualifiedName("com._1c.g5.v8.dt.metadata", "tags"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "(?im)^\\s*(procedure|function|процедура|функция)\\s+([A-Za-zА-Яа-я_][A-Za-zА-Яа-я0-9_]*)\\s*\\(([^)]*)\\)([^\\r\\n]*)"); //$NON-NLS-1$
    private static final Pattern REGION_DECLARATION = Pattern.compile(
            "(?im)^\\s*#\\s*(region|область)\\s+(.+)$"); //$NON-NLS-1$
    private static final Pattern END_REGION_DECLARATION = Pattern.compile(
            "(?im)^\\s*#\\s*(endregion|конецобласти)\\b.*$"); //$NON-NLS-1$
    private static final Pattern CALL_EXPRESSION = Pattern.compile(
            "\\b([A-Za-zА-Яа-я_][A-Za-zА-Яа-я0-9_]*)\\s*\\("); //$NON-NLS-1$
    private static final Set<String> CALL_KEYWORDS = Set.of(
            "if", "for", "while", "try", "new", "если", "для", "пока", "попытка", "новый", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
            "procedure", "function", "процедура", "функция"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private final EdtMetadataGateway gateway;

    public EdtProjectAnalysisSupport(EdtMetadataGateway gateway) {
        this.gateway = gateway != null ? gateway : new EdtMetadataGateway();
    }

    IProject resolveExistingProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_NOT_FOUND,
                    "EDT project not found: " + projectName); //$NON-NLS-1$
        }
        if (!project.isOpen()) {
            throw new EdtToolException(EdtToolErrorCode.EDT_NOT_READY,
                    "EDT project is not open: " + projectName); //$NON-NLS-1$
        }
        return project;
    }

    JsonObject configurationProperties(String projectName) {
        IProject project = resolveExistingProject(projectName);
        JsonObject result = base(projectName);
        result.addProperty("source", "workspace"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject properties = new JsonObject();
        JsonObject counts = new JsonObject();

        try {
            IConfigurationProvider provider = gateway.getConfigurationProvider();
            Configuration configuration = provider.getConfiguration(project);
            if (configuration != null) {
                result.addProperty("source", "edt_configuration_provider"); //$NON-NLS-1$ //$NON-NLS-2$
                collectEObjectProperties(configuration, properties);
                collectCollectionCounts(configuration, counts);
            }
        } catch (RuntimeException e) {
            result.addProperty("edt_provider_warning", safe(e.getMessage())); //$NON-NLS-1$
        }

        IFile configurationFile = project.getFile("src/Configuration/Configuration.mdo"); //$NON-NLS-1$
        result.addProperty("configuration_file", toProjectPath(configurationFile)); //$NON-NLS-1$
        result.addProperty("configuration_file_exists", configurationFile.exists()); //$NON-NLS-1$
        if (properties.size() == 0 && configurationFile.exists()) {
            readConfigurationMdoFallback(configurationFile, properties);
        }
        result.add("properties", properties); //$NON-NLS-1$
        result.add("collection_counts", counts); //$NON-NLS-1$
        return result;
    }

    JsonObject problemSummary(String projectName) {
        IProject project = resolveExistingProject(projectName);
        JsonObject result = base(projectName);
        JsonArray problems = new JsonArray();
        int errors = 0;
        int warnings = 0;
        int infos = 0;

        try {
            IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            for (IMarker marker : markers) {
                int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                if (severity == IMarker.SEVERITY_ERROR) {
                    errors++;
                } else if (severity == IMarker.SEVERITY_WARNING) {
                    warnings++;
                } else {
                    infos++;
                }
                JsonObject item = new JsonObject();
                item.addProperty("severity", severityName(severity)); //$NON-NLS-1$
                item.addProperty("message", marker.getAttribute(IMarker.MESSAGE, "")); //$NON-NLS-1$ //$NON-NLS-2$
                item.addProperty("path", marker.getResource() != null ? marker.getResource().getFullPath().toString() : ""); //$NON-NLS-1$ //$NON-NLS-2$
                item.addProperty("line", marker.getAttribute(IMarker.LINE_NUMBER, -1)); //$NON-NLS-1$
                item.addProperty("marker_type", marker.getType()); //$NON-NLS-1$
                problems.add(item);
            }
        } catch (CoreException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to read project problem markers: " + e.getMessage(), e); //$NON-NLS-1$
        }

        result.addProperty("total_errors", errors); //$NON-NLS-1$
        result.addProperty("total_warnings", warnings); //$NON-NLS-1$
        result.addProperty("total_infos", infos); //$NON-NLS-1$
        result.addProperty("total_problems", errors + warnings + infos); //$NON-NLS-1$
        result.add("problems", problems); //$NON-NLS-1$
        return result;
    }

    JsonObject tags(String projectName) {
        IProject project = resolveExistingProject(projectName);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TaggedObject object : taggedObjects(project, List.of())) {
            for (String tag : object.tags()) {
                counts.put(tag, Integer.valueOf(counts.getOrDefault(tag, Integer.valueOf(0)).intValue() + 1));
            }
        }
        JsonArray tags = new JsonArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> {
                    JsonObject tag = new JsonObject();
                    tag.addProperty("name", entry.getKey()); //$NON-NLS-1$
                    tag.addProperty("object_count", entry.getValue()); //$NON-NLS-1$
                    tags.add(tag);
                });
        JsonObject result = base(projectName);
        result.add("tags", tags); //$NON-NLS-1$
        result.addProperty("total_tags", counts.size()); //$NON-NLS-1$
        return result;
    }

    JsonObject objectsByTags(String projectName, List<String> requestedTags) {
        IProject project = resolveExistingProject(projectName);
        List<String> normalizedTags = normalizeTags(requestedTags);
        if (normalizedTags.isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "tags must contain at least one non-empty tag"); //$NON-NLS-1$
        }
        JsonArray objects = new JsonArray();
        for (TaggedObject object : taggedObjects(project, normalizedTags)) {
            JsonObject item = new JsonObject();
            item.addProperty("name", object.name()); //$NON-NLS-1$
            item.addProperty("fqn", object.fqn()); //$NON-NLS-1$
            item.addProperty("kind", object.kind()); //$NON-NLS-1$
            item.addProperty("path", object.path()); //$NON-NLS-1$
            item.add("tags", GSON.toJsonTree(object.tags())); //$NON-NLS-1$
            objects.add(item);
        }
        JsonObject result = base(projectName);
        result.add("filter_tags", GSON.toJsonTree(requestedTags)); //$NON-NLS-1$
        result.add("objects", objects); //$NON-NLS-1$
        result.addProperty("total_objects", objects.size()); //$NON-NLS-1$
        return result;
    }

    JsonObject listModules(String projectName, String objectTypeFilter) {
        IProject project = resolveExistingProject(projectName);
        String normalizedFilter = normalize(objectTypeFilter);
        List<ModuleInfo> modules = modules(project);
        JsonArray array = new JsonArray();
        for (ModuleInfo module : modules) {
            if (!normalizedFilter.isBlank()
                    && !normalize(module.objectType()).contains(normalizedFilter)
                    && !normalize(module.ownerFqn()).contains(normalizedFilter)) {
                continue;
            }
            array.add(module.toJson());
        }
        JsonObject result = base(projectName);
        result.addProperty("object_type_filter", safe(objectTypeFilter)); //$NON-NLS-1$
        result.add("modules", array); //$NON-NLS-1$
        result.addProperty("total_modules", array.size()); //$NON-NLS-1$
        return result;
    }

    JsonObject moduleStructure(String projectName, String moduleFqn, boolean full) {
        IProject project = resolveExistingProject(projectName);
        ModuleInfo module = resolveModule(project, moduleFqn);
        String text = readFile(module.file());
        ModuleStructure structure = parseModule(module, text);
        JsonObject result = base(projectName);
        result.add("module", module.toJson()); //$NON-NLS-1$
        result.add("sections", GSON.toJsonTree(structure.sections())); //$NON-NLS-1$
        result.add("methods", GSON.toJsonTree(structure.methods())); //$NON-NLS-1$
        result.add("exports", GSON.toJsonTree(structure.methods().stream()
                .filter(MethodInfo::exported)
                .toList())); //$NON-NLS-1$
        result.addProperty("total_methods", structure.methods().size()); //$NON-NLS-1$
        result.addProperty("total_exports", structure.methods().stream().filter(MethodInfo::exported).count()); //$NON-NLS-1$
        if (full) {
            result.add("calls", GSON.toJsonTree(collectCallSites(structure, text))); //$NON-NLS-1$
        }
        return result;
    }

    public JsonObject searchInCode(String projectName, String query, String searchType, String scope) {
        IProject project = resolveExistingProject(projectName);
        String normalizedType = defaulted(searchType, "text").toLowerCase(Locale.ROOT); //$NON-NLS-1$
        String normalizedScope = defaulted(scope, "all").toLowerCase(Locale.ROOT); //$NON-NLS-1$
        if (!"text".equals(normalizedType) && !"regex".equals(normalizedType)) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "searchType must be one of: text, regex"); //$NON-NLS-1$
        }
        if (query == null || query.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "query is required"); //$NON-NLS-1$
        }
        Pattern regex = "regex".equals(normalizedType) //$NON-NLS-1$
                ? Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                : null;
        JsonArray results = new JsonArray();
        for (IFile file : codeFiles(project, normalizedScope)) {
            searchFile(file, query, normalizedType, regex, results);
        }
        JsonObject result = base(projectName);
        result.addProperty("query", query); //$NON-NLS-1$
        result.addProperty("search_type", normalizedType); //$NON-NLS-1$
        result.addProperty("scope", normalizedScope); //$NON-NLS-1$
        result.add("results", results); //$NON-NLS-1$
        result.addProperty("total_results", results.size()); //$NON-NLS-1$
        return result;
    }

    JsonObject methodCallHierarchy(String projectName, String methodFqn, String direction, int depth) {
        IProject project = resolveExistingProject(projectName);
        String normalizedDirection = defaulted(direction, "both").toLowerCase(Locale.ROOT); //$NON-NLS-1$
        if (!Set.of("callers", "callees", "both").contains(normalizedDirection)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "direction must be one of: callers, callees, both"); //$NON-NLS-1$
        }
        int effectiveDepth = Math.max(1, Math.min(depth, 5));
        CodeIndex index = buildCodeIndex(project);
        MethodKey root = index.resolveMethod(methodFqn);
        if (root == null) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "Method not found: " + methodFqn); //$NON-NLS-1$
        }
        JsonObject result = base(projectName);
        result.add("root", index.method(root).toJson()); //$NON-NLS-1$
        result.addProperty("direction", normalizedDirection); //$NON-NLS-1$
        result.addProperty("depth", effectiveDepth); //$NON-NLS-1$
        if (!"callees".equals(normalizedDirection)) { //$NON-NLS-1$
            result.add("callers", hierarchy(root, effectiveDepth, index, false)); //$NON-NLS-1$
        }
        if (!"callers".equals(normalizedDirection)) { //$NON-NLS-1$
            result.add("callees", hierarchy(root, effectiveDepth, index, true)); //$NON-NLS-1$
        }
        return result;
    }

    JsonObject goToDefinition(String projectName, Map<String, Object> position, String symbolFqn) {
        IProject project = resolveExistingProject(projectName);
        SymbolResolution resolution = resolveSymbol(project, position, symbolFqn);
        JsonObject result = base(projectName);
        result.add("definition", resolution.toJson()); //$NON-NLS-1$
        return result;
    }

    JsonObject symbolInfo(String projectName, Map<String, Object> position, String symbolFqn) {
        IProject project = resolveExistingProject(projectName);
        SymbolResolution resolution = resolveSymbol(project, position, symbolFqn);
        JsonObject result = base(projectName);
        JsonObject info = resolution.toJson();
        info.addProperty("scope", resolution.exported() ? "export" : "module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        info.addProperty("documentation", safe(resolution.documentation())); //$NON-NLS-1$
        result.add("symbol", info); //$NON-NLS-1$
        return result;
    }

    public static JsonObject errorPayload(String projectName, String code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("project_name", projectName == null ? "" : projectName); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("error_code", code == null ? "" : code); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    public static String pretty(JsonObject object) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> objectParam(Map<String, Object> raw, String name) {
        Object value = raw.get(name);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    static List<String> stringListParam(Map<String, Object> raw, String name) {
        Object value = raw.get(name);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
            return result;
        }
        return List.of();
    }

    private JsonObject base(String projectName) {
        JsonObject result = new JsonObject();
        result.addProperty("project_name", projectName); //$NON-NLS-1$
        result.addProperty("status", "ok"); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    private void collectEObjectProperties(EObject object, JsonObject properties) {
        for (String featureName : List.of("name", "synonym", "comment", "version", "vendor", "defaultLanguage")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
            if (feature == null || feature.isMany()) {
                continue;
            }
            Object value = object.eGet(feature);
            if (value != null) {
                properties.addProperty(featureName, String.valueOf(value));
            }
        }
        properties.addProperty("eClass", object.eClass().getName()); //$NON-NLS-1$
    }

    private void collectCollectionCounts(EObject object, JsonObject counts) {
        for (EReference reference : object.eClass().getEAllReferences()) {
            if (!reference.isContainment() || !reference.isMany()) {
                continue;
            }
            Object value = object.eGet(reference);
            if (value instanceof List<?> list && !list.isEmpty()) {
                counts.addProperty(reference.getName(), list.size());
            }
        }
    }

    private void readConfigurationMdoFallback(IFile file, JsonObject properties) {
        String text = readFile(file);
        for (String tag : List.of("name", "synonym", "comment", "version", "vendor")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            Matcher matcher = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(text);
            if (matcher.find()) {
                properties.addProperty(tag, matcher.group(1).trim());
            }
        }
    }

    private List<TaggedObject> taggedObjects(IProject project, List<String> requiredTags) {
        List<String> normalizedRequired = normalizeTags(requiredTags);
        List<TaggedObject> result = new ArrayList<>();
        for (IFile file : metadataFiles(project)) {
            Set<String> tags = new LinkedHashSet<>();
            try {
                String property = file.getPersistentProperty(TAGS_PROPERTY);
                if (property != null) {
                    tags.addAll(normalizeTags(List.of(property.split(",")))); //$NON-NLS-1$
                }
            } catch (CoreException e) {
                // Continue with file-content fallback.
            }
            tags.addAll(readTagsFromContent(file));
            if (tags.isEmpty()) {
                continue;
            }
            if (!normalizedRequired.isEmpty() && !tags.containsAll(normalizedRequired)) {
                continue;
            }
            result.add(new TaggedObject(
                    file.getName(),
                    fqnFromMetadataPath(file),
                    kindFromPath(file),
                    toProjectPath(file),
                    new ArrayList<>(tags)));
        }
        return result;
    }

    private Set<String> readTagsFromContent(IFile file) {
        String text = readFile(file);
        Set<String> tags = new LinkedHashSet<>();
        Matcher attr = Pattern.compile("\\btag(?:s)?\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(text); //$NON-NLS-1$
        while (attr.find()) {
            tags.addAll(normalizeTags(List.of(attr.group(1).split(",")))); //$NON-NLS-1$
        }
        Matcher tag = Pattern.compile("<tag(?:s)?>(.*?)</tag(?:s)?>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text); //$NON-NLS-1$
        while (tag.find()) {
            tags.addAll(normalizeTags(List.of(tag.group(1).split(",")))); //$NON-NLS-1$
        }
        return tags;
    }

    private List<ModuleInfo> modules(IProject project) {
        List<ModuleInfo> result = new ArrayList<>();
        for (IFile file : codeFiles(project, "modules")) { //$NON-NLS-1$
            if (!"bsl".equalsIgnoreCase(file.getFileExtension())) { //$NON-NLS-1$
                continue;
            }
            result.add(moduleInfo(file));
        }
        result.sort(Comparator.comparing(ModuleInfo::fqn, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private ModuleInfo resolveModule(IProject project, String moduleFqn) {
        if (moduleFqn == null || moduleFqn.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "moduleFqn is required"); //$NON-NLS-1$
        }
        String normalized = normalize(moduleFqn);
        for (ModuleInfo module : modules(project)) {
            if (normalize(module.fqn()).equals(normalized)
                    || normalize(module.filePath()).equals(normalized)
                    || normalize(module.filePath()).endsWith("/" + normalized)) { //$NON-NLS-1$
                return module;
            }
        }
        IFile direct = project.getFile("src/" + moduleFqn); //$NON-NLS-1$
        if (direct.exists()) {
            return moduleInfo(direct);
        }
        throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                "Module not found: " + moduleFqn); //$NON-NLS-1$
    }

    private ModuleInfo moduleInfo(IFile file) {
        String filePath = stripSrc(toProjectPath(file));
        String[] parts = filePath.split("/"); //$NON-NLS-1$
        String objectType = parts.length > 0 ? singularObjectType(parts[0]) : ""; //$NON-NLS-1$
        String ownerName = parts.length > 1 ? parts[1] : file.getName();
        String moduleName = file.getName().replaceFirst("\\.bsl$", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String ownerFqn = objectType.isBlank() ? ownerName : objectType + "." + ownerName; //$NON-NLS-1$
        String fqn = ownerFqn + "." + moduleName; //$NON-NLS-1$
        return new ModuleInfo(fqn, ownerFqn, ownerName, objectType, moduleName, filePath, toFileUri(file), file);
    }

    private ModuleStructure parseModule(ModuleInfo module, String text) {
        List<SectionInfo> sections = new ArrayList<>();
        Matcher regionMatcher = REGION_DECLARATION.matcher(text);
        while (regionMatcher.find()) {
            int startLine = lineOf(text, regionMatcher.start());
            int endLine = findRegionEnd(text, regionMatcher.end(), startLine);
            sections.add(new SectionInfo(regionMatcher.group(2).trim(), startLine, endLine));
        }

        List<MethodInfo> methods = new ArrayList<>();
        Matcher matcher = METHOD_DECLARATION.matcher(text);
        while (matcher.find()) {
            String kind = normalize(matcher.group(1)).startsWith("ф") || normalize(matcher.group(1)).startsWith("f") //$NON-NLS-1$ //$NON-NLS-2$
                    ? "function" : "procedure"; //$NON-NLS-1$ //$NON-NLS-2$
            String name = matcher.group(2);
            int startLine = lineOf(text, matcher.start());
            int endOffset = findMethodEnd(text, matcher.end());
            int endLine = lineOf(text, endOffset);
            String tail = matcher.group(4) == null ? "" : matcher.group(4); //$NON-NLS-1$
            boolean exported = normalize(tail).contains("export") || normalize(tail).contains("экспорт"); //$NON-NLS-1$ //$NON-NLS-2$
            String signature = lineAt(text, startLine).trim();
            String documentation = documentationBefore(text, startLine);
            methods.add(new MethodInfo(
                    module.fqn() + "." + name, //$NON-NLS-1$
                    name,
                    kind,
                    signature,
                    startLine,
                    endLine,
                    exported,
                    documentation,
                    module.filePath(),
                    module.fileUri()));
        }
        return new ModuleStructure(sections, methods);
    }

    private List<CallSite> collectCallSites(ModuleStructure structure, String text) {
        List<CallSite> calls = new ArrayList<>();
        for (MethodInfo method : structure.methods()) {
            String body = sliceLines(text, method.startLine(), method.endLine());
            Matcher matcher = CALL_EXPRESSION.matcher(body);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (CALL_KEYWORDS.contains(normalize(name)) || name.equals(method.name())) {
                    continue;
                }
                calls.add(new CallSite(method.fqn(), name, method.startLine() + lineOf(body, matcher.start()) - 1));
            }
        }
        return calls;
    }

    private CodeIndex buildCodeIndex(IProject project) {
        Map<MethodKey, MethodInfo> methods = new LinkedHashMap<>();
        Map<MethodKey, Set<MethodKey>> callees = new LinkedHashMap<>();
        Map<MethodKey, Set<MethodKey>> callers = new LinkedHashMap<>();
        Map<String, List<MethodKey>> byName = new HashMap<>();

        for (ModuleInfo module : modules(project)) {
            String text = readFile(module.file());
            ModuleStructure structure = parseModule(module, text);
            for (MethodInfo method : structure.methods()) {
                MethodKey key = new MethodKey(module.fqn(), method.name());
                methods.put(key, method);
                byName.computeIfAbsent(normalize(method.name()), ignored -> new ArrayList<>()).add(key);
            }
        }

        for (ModuleInfo module : modules(project)) {
            String text = readFile(module.file());
            ModuleStructure structure = parseModule(module, text);
            for (MethodInfo method : structure.methods()) {
                MethodKey from = new MethodKey(module.fqn(), method.name());
                for (CallSite call : collectCallSites(structure, text)) {
                    if (!call.callerFqn().equals(method.fqn())) {
                        continue;
                    }
                    List<MethodKey> targets = byName.getOrDefault(normalize(call.calleeName()), List.of());
                    for (MethodKey target : targets) {
                        callees.computeIfAbsent(from, ignored -> new LinkedHashSet<>()).add(target);
                        callers.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(from);
                    }
                }
            }
        }
        return new CodeIndex(methods, callees, callers);
    }

    private JsonArray hierarchy(MethodKey root, int maxDepth, CodeIndex index, boolean calleeDirection) {
        JsonArray result = new JsonArray();
        ArrayDeque<HierarchyNode> queue = new ArrayDeque<>();
        Set<MethodKey> seen = new HashSet<>();
        queue.add(new HierarchyNode(root, 0));
        seen.add(root);
        while (!queue.isEmpty()) {
            HierarchyNode node = queue.removeFirst();
            if (node.depth() >= maxDepth) {
                continue;
            }
            Set<MethodKey> next = calleeDirection ? index.callees(node.key()) : index.callers(node.key());
            for (MethodKey key : next) {
                MethodInfo method = index.method(key);
                if (method == null) {
                    continue;
                }
                JsonObject item = method.toJson();
                item.addProperty("depth", node.depth() + 1); //$NON-NLS-1$
                result.add(item);
                if (seen.add(key)) {
                    queue.addLast(new HierarchyNode(key, node.depth() + 1));
                }
            }
        }
        return result;
    }

    private SymbolResolution resolveSymbol(IProject project, Map<String, Object> position, String symbolFqn) {
        if (symbolFqn != null && !symbolFqn.isBlank()) {
            return resolveSymbolFqn(project, symbolFqn);
        }
        if (position == null || position.isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "Either position or symbolFqn is required"); //$NON-NLS-1$
        }
        String fileUri = asString(position.get("fileUri")); //$NON-NLS-1$
        int line = asInt(position.get("line"), 1); //$NON-NLS-1$
        int column = asInt(position.get("column"), 1); //$NON-NLS-1$
        String filePath = filePathFromUri(project, fileUri);
        IFile file = project.getFile("src/" + filePath); //$NON-NLS-1$
        if (!file.exists()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "File not found for position: " + fileUri); //$NON-NLS-1$
        }
        String text = readFile(file);
        String symbol = symbolAt(text, line, column);
        if (symbol.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "Cannot resolve symbol at position"); //$NON-NLS-1$
        }
        try {
            return resolveSymbolFqn(project, symbol);
        } catch (EdtToolException e) {
            ModuleInfo module = moduleInfo(file);
            return new SymbolResolution(
                    module.fqn() + "." + symbol, //$NON-NLS-1$
                    symbol,
                    "reference", //$NON-NLS-1$
                    module.fqn(),
                    filePath,
                    toFileUri(file),
                    line,
                    column,
                    "", //$NON-NLS-1$
                    false,
                    ""); //$NON-NLS-1$
        }
    }

    private SymbolResolution resolveSymbolFqn(IProject project, String symbolFqn) {
        String normalized = normalize(symbolFqn);
        for (ModuleInfo module : modules(project)) {
            if (normalize(module.fqn()).equals(normalized) || normalize(module.filePath()).equals(normalized)) {
                return new SymbolResolution(module.fqn(), module.moduleName(), "module", module.fqn(), //$NON-NLS-1$
                        module.filePath(), module.fileUri(), 1, 1, module.moduleName(), true, ""); //$NON-NLS-1$
            }
            ModuleStructure structure = parseModule(module, readFile(module.file()));
            for (MethodInfo method : structure.methods()) {
                if (normalize(method.fqn()).equals(normalized)
                        || normalize(method.name()).equals(normalized)
                        || normalize(module.fqn() + "." + method.name()).equals(normalized)) { //$NON-NLS-1$
                    return new SymbolResolution(method.fqn(), method.name(), method.kind(), module.fqn(),
                            method.filePath(), method.fileUri(), method.startLine(), 1, method.signature(),
                            method.exported(), method.documentation());
                }
            }
        }
        throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                "Symbol not found: " + symbolFqn); //$NON-NLS-1$
    }

    private List<IFile> codeFiles(IProject project, String scope) {
        List<IFile> result = new ArrayList<>();
        visit(project, resource -> {
            if (resource instanceof IFile file && isInSrc(file) && matchesCodeScope(file, scope)) {
                result.add(file);
            }
            return true;
        });
        result.sort(Comparator.comparing(this::toProjectPath, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private List<IFile> metadataFiles(IProject project) {
        List<IFile> result = new ArrayList<>();
        visit(project, resource -> {
            if (resource instanceof IFile file && isInSrc(file)
                    && ("mdo".equalsIgnoreCase(file.getFileExtension()) //$NON-NLS-1$
                            || "bsl".equalsIgnoreCase(file.getFileExtension()))) { //$NON-NLS-1$
                result.add(file);
            }
            return true;
        });
        return result;
    }

    private void visit(IProject project, IResourceVisitor visitor) {
        try {
            project.accept(visitor, IResource.DEPTH_INFINITE, false);
        } catch (CoreException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to visit project resources: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    private boolean matchesCodeScope(IFile file, String scope) {
        String extension = safe(file.getFileExtension()).toLowerCase(Locale.ROOT);
        String path = stripSrc(toProjectPath(file)).toLowerCase(Locale.ROOT);
        if ("queries".equals(scope)) { //$NON-NLS-1$
            return extension.equals("query") || extension.equals("sdbl") || path.contains("/queries/") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    || path.contains("/query"); //$NON-NLS-1$
        }
        if ("modules".equals(scope)) { //$NON-NLS-1$
            return extension.equals("bsl"); //$NON-NLS-1$
        }
        return extension.equals("bsl") || extension.equals("query") || extension.equals("sdbl") || extension.equals("mdo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private void searchFile(IFile file, String query, String searchType, Pattern regex, JsonArray results) {
        String text = readFile(file);
        String[] lines = text.split("\\R", -1); //$NON-NLS-1$
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher matcher = regex != null ? regex.matcher(line) : null;
            boolean found = regex != null ? matcher.find() : line.toLowerCase(Locale.ROOT).contains(normalizedQuery);
            if (!found) {
                continue;
            }
            int column = regex != null ? matcher.start() + 1
                    : line.toLowerCase(Locale.ROOT).indexOf(normalizedQuery) + 1;
            JsonObject item = new JsonObject();
            item.addProperty("file_path", stripSrc(toProjectPath(file))); //$NON-NLS-1$
            item.addProperty("file_uri", toFileUri(file)); //$NON-NLS-1$
            item.addProperty("line", i + 1); //$NON-NLS-1$
            item.addProperty("column", column); //$NON-NLS-1$
            item.addProperty("match", regex != null ? matcher.group() : query); //$NON-NLS-1$
            item.addProperty("snippet", line.trim()); //$NON-NLS-1$
            item.addProperty("search_type", searchType); //$NON-NLS-1$
            results.add(item);
            if (results.size() >= 500) {
                return;
            }
        }
    }

    private String readFile(IFile file) {
        try (InputStream stream = file.getContents();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } catch (CoreException | IOException e) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to read file " + toProjectPath(file) + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private int findRegionEnd(String text, int fromOffset, int defaultLine) {
        Matcher matcher = END_REGION_DECLARATION.matcher(text);
        if (matcher.find(fromOffset)) {
            return lineOf(text, matcher.start());
        }
        return defaultLine;
    }

    private int findMethodEnd(String text, int fromOffset) {
        Matcher next = METHOD_DECLARATION.matcher(text);
        if (next.find(fromOffset)) {
            return Math.max(fromOffset, next.start() - 1);
        }
        return text.length();
    }

    private String documentationBefore(String text, int startLine) {
        StringBuilder docs = new StringBuilder();
        for (int line = startLine - 1; line >= 1; line--) {
            String current = lineAt(text, line).trim();
            if (!current.startsWith("//") || current.length() <= 2) { //$NON-NLS-1$
                break;
            }
            docs.insert(0, current.substring(2).trim() + "\n"); //$NON-NLS-1$
        }
        return docs.toString().trim();
    }

    private int lineOf(String text, int offset) {
        int line = 1;
        int max = Math.min(Math.max(offset, 0), text.length());
        for (int i = 0; i < max; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String lineAt(String text, int lineNumber) {
        String[] lines = text.split("\\R", -1); //$NON-NLS-1$
        if (lineNumber < 1 || lineNumber > lines.length) {
            return ""; //$NON-NLS-1$
        }
        return lines[lineNumber - 1];
    }

    private String sliceLines(String text, int startLine, int endLine) {
        String[] lines = text.split("\\R", -1); //$NON-NLS-1$
        StringBuilder builder = new StringBuilder();
        for (int i = Math.max(1, startLine); i <= Math.min(endLine, lines.length); i++) {
            builder.append(lines[i - 1]).append('\n');
        }
        return builder.toString();
    }

    private String symbolAt(String text, int line, int column) {
        String current = lineAt(text, line);
        if (current.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        int index = Math.max(0, Math.min(column - 1, current.length() - 1));
        int start = index;
        while (start > 0 && isIdentifierPart(current.charAt(start - 1))) {
            start--;
        }
        int end = index;
        while (end < current.length() && isIdentifierPart(current.charAt(end))) {
            end++;
        }
        return start < end ? current.substring(start, end) : ""; //$NON-NLS-1$
    }

    private boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private String filePathFromUri(IProject project, String fileUri) {
        String value = safe(fileUri);
        if (value.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        try {
            if (value.startsWith("file:")) { //$NON-NLS-1$
                value = URI.create(value).getPath();
            }
        } catch (IllegalArgumentException e) {
            // Use raw value below.
        }
        String projectPrefix = "/" + project.getName() + "/src/"; //$NON-NLS-1$ //$NON-NLS-2$
        int projectIndex = value.indexOf(projectPrefix);
        if (projectIndex >= 0) {
            return value.substring(projectIndex + projectPrefix.length());
        }
        int srcIndex = value.indexOf("/src/"); //$NON-NLS-1$
        if (srcIndex >= 0) {
            return value.substring(srcIndex + 5);
        }
        return stripSrc(value);
    }

    private boolean isInSrc(IFile file) {
        return toProjectPath(file).startsWith("/" + file.getProject().getName() + "/src/"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String stripSrc(String path) {
        String marker = "/src/"; //$NON-NLS-1$
        int index = path.indexOf(marker);
        return index >= 0 ? path.substring(index + marker.length()) : path.replaceFirst("^src/", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String toProjectPath(IResource resource) {
        return resource == null || resource.getFullPath() == null ? "" : resource.getFullPath().toString(); //$NON-NLS-1$
    }

    private String toFileUri(IFile file) {
        IPath location = file.getLocation();
        return location != null ? location.toFile().toURI().toString() : toProjectPath(file);
    }

    private String fqnFromMetadataPath(IFile file) {
        if ("bsl".equalsIgnoreCase(file.getFileExtension())) { //$NON-NLS-1$
            return moduleInfo(file).fqn();
        }
        String path = stripSrc(toProjectPath(file));
        String[] parts = path.split("/"); //$NON-NLS-1$
        if (parts.length >= 2) {
            return singularObjectType(parts[0]) + "." + parts[1].replaceFirst("\\.mdo$", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return file.getName().replaceFirst("\\.mdo$", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String kindFromPath(IFile file) {
        String path = stripSrc(toProjectPath(file));
        String[] parts = path.split("/"); //$NON-NLS-1$
        return parts.length > 0 ? singularObjectType(parts[0]) : safe(file.getFileExtension());
    }

    private String singularObjectType(String collection) {
        String value = safe(collection);
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "commonmodules" -> "CommonModule"; //$NON-NLS-1$ //$NON-NLS-2$
            case "catalogs" -> "Catalog"; //$NON-NLS-1$ //$NON-NLS-2$
            case "documents" -> "Document"; //$NON-NLS-1$ //$NON-NLS-2$
            case "reports" -> "Report"; //$NON-NLS-1$ //$NON-NLS-2$
            case "dataprocessors" -> "DataProcessor"; //$NON-NLS-1$ //$NON-NLS-2$
            case "informationregisters" -> "InformationRegister"; //$NON-NLS-1$ //$NON-NLS-2$
            case "accumulationregisters" -> "AccumulationRegister"; //$NON-NLS-1$ //$NON-NLS-2$
            case "enums" -> "Enum"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> value.endsWith("s") && value.length() > 1 //$NON-NLS-1$
                    ? value.substring(0, value.length() - 1)
                    : value;
        };
    }

    private List<String> normalizeTags(List<String> tags) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null) {
                    continue;
                }
                String normalized = tag.trim();
                if (!normalized.isEmpty()) {
                    result.add(normalized);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private String severityName(int severity) {
        return switch (severity) {
            case IMarker.SEVERITY_ERROR -> "error"; //$NON-NLS-1$
            case IMarker.SEVERITY_WARNING -> "warning"; //$NON-NLS-1$
            default -> "info"; //$NON-NLS-1$
        };
    }

    private String defaulted(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String normalize(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT).replace('\\', '/');
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim(); //$NON-NLS-1$
    }

    private int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private record TaggedObject(String name, String fqn, String kind, String path, List<String> tags) { }

    private record ModuleStructure(List<SectionInfo> sections, List<MethodInfo> methods) { }

    private record SectionInfo(String name, int startLine, int endLine) { }

    private record CallSite(String callerFqn, String calleeName, int line) { }

    private record MethodKey(String moduleFqn, String methodName) { }

    private record HierarchyNode(MethodKey key, int depth) { }

    private record ModuleInfo(
            String fqn,
            String ownerFqn,
            String ownerName,
            String objectType,
            String moduleName,
            String filePath,
            String fileUri,
            IFile file) {

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("fqn", fqn); //$NON-NLS-1$
            object.addProperty("owner_fqn", ownerFqn); //$NON-NLS-1$
            object.addProperty("owner_name", ownerName); //$NON-NLS-1$
            object.addProperty("object_type", objectType); //$NON-NLS-1$
            object.addProperty("module_name", moduleName); //$NON-NLS-1$
            object.addProperty("file_path", filePath); //$NON-NLS-1$
            object.addProperty("file_uri", fileUri); //$NON-NLS-1$
            return object;
        }
    }

    private record MethodInfo(
            String fqn,
            String name,
            String kind,
            String signature,
            int startLine,
            int endLine,
            boolean exported,
            String documentation,
            String filePath,
            String fileUri) {

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("fqn", fqn); //$NON-NLS-1$
            object.addProperty("name", name); //$NON-NLS-1$
            object.addProperty("kind", kind); //$NON-NLS-1$
            object.addProperty("signature", signature); //$NON-NLS-1$
            object.addProperty("start_line", startLine); //$NON-NLS-1$
            object.addProperty("end_line", endLine); //$NON-NLS-1$
            object.addProperty("export", exported); //$NON-NLS-1$
            object.addProperty("file_path", filePath); //$NON-NLS-1$
            object.addProperty("file_uri", fileUri); //$NON-NLS-1$
            return object;
        }
    }

    private record SymbolResolution(
            String fqn,
            String name,
            String kind,
            String moduleFqn,
            String filePath,
            String fileUri,
            int line,
            int column,
            String signature,
            boolean exported,
            String documentation) {

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("fqn", fqn); //$NON-NLS-1$
            object.addProperty("name", name); //$NON-NLS-1$
            object.addProperty("kind", kind); //$NON-NLS-1$
            object.addProperty("module_fqn", moduleFqn); //$NON-NLS-1$
            object.addProperty("file_path", filePath); //$NON-NLS-1$
            object.addProperty("file_uri", fileUri); //$NON-NLS-1$
            object.addProperty("line", line); //$NON-NLS-1$
            object.addProperty("column", column); //$NON-NLS-1$
            object.addProperty("signature", signature); //$NON-NLS-1$
            object.addProperty("export", exported); //$NON-NLS-1$
            return object;
        }
    }

    private final class CodeIndex {

        private final Map<MethodKey, MethodInfo> methods;
        private final Map<MethodKey, Set<MethodKey>> callees;
        private final Map<MethodKey, Set<MethodKey>> callers;

        CodeIndex(Map<MethodKey, MethodInfo> methods, Map<MethodKey, Set<MethodKey>> callees,
                Map<MethodKey, Set<MethodKey>> callers) {
            this.methods = methods;
            this.callees = callees;
            this.callers = callers;
        }

        MethodInfo method(MethodKey key) {
            return methods.get(key);
        }

        Set<MethodKey> callees(MethodKey key) {
            return callees.getOrDefault(key, Set.of());
        }

        Set<MethodKey> callers(MethodKey key) {
            return callers.getOrDefault(key, Set.of());
        }

        MethodKey resolveMethod(String methodFqn) {
            String normalized = normalize(methodFqn);
            MethodKey suffixMatch = null;
            for (Map.Entry<MethodKey, MethodInfo> entry : methods.entrySet()) {
                String fqn = normalize(entry.getValue().fqn());
                if (fqn.equals(normalized)) {
                    return entry.getKey();
                }
                if (fqn.endsWith("." + normalized) || normalize(entry.getValue().name()).equals(normalized)) { //$NON-NLS-1$
                    suffixMatch = entry.getKey();
                }
            }
            return suffixMatch;
        }
    }
}
