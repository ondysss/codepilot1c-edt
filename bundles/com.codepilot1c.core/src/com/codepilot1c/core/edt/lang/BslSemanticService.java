package com.codepilot1c.core.edt.lang;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.XtextResourceSet;

import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com.codepilot1c.core.edt.ast.ContentAssistRequest;
import com.codepilot1c.core.edt.ast.ContentAssistResult;
import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtContentAssistService;
import com.codepilot1c.core.edt.ast.EdtServiceGateway;
import com.codepilot1c.core.edt.ast.ProjectReadinessChecker;
import com.codepilot1c.core.edt.platformdoc.EdtPlatformDocumentationService;
import com.codepilot1c.core.edt.platformdoc.PlatformDocumentationException;
import com.codepilot1c.core.edt.platformdoc.PlatformDocumentationRequest;
import com.codepilot1c.core.edt.platformdoc.PlatformDocumentationResult;
import com.codepilot1c.core.edt.platformdoc.PlatformMemberFilter;

/**
 * Semantic BSL model service for symbol/type/scope extraction at source position.
 */
public class BslSemanticService {

    private static final int RESOURCE_SET_RETRY_ATTEMPTS = 10;
    private static final long RESOURCE_SET_RETRY_DELAY_MS = 300L;

    private final EdtServiceGateway gateway;
    private final ProjectReadinessChecker readinessChecker;
    private final EdtPlatformDocumentationService platformDocService;
    private final EdtContentAssistService contentAssistService;

    public BslSemanticService() {
        this(new EdtServiceGateway());
    }

    public BslSemanticService(EdtServiceGateway gateway) {
        this.gateway = gateway;
        this.readinessChecker = new ProjectReadinessChecker(gateway);
        this.platformDocService = new EdtPlatformDocumentationService();
        this.contentAssistService = new EdtContentAssistService(gateway, readinessChecker);
    }

    public BslSymbolResult getSymbolAtPosition(BslPositionRequest request) {
        request.validate();
        PositionContext context = resolveContext(request);

        String symbolName = firstNonBlank(
                getStringFeature(context.element(), "name"), //$NON-NLS-1$
                getStringFeature(context.element(), "nameRu")); //$NON-NLS-1$

        INode node = NodeModelUtils.findActualNodeFor(context.element());
        String symbolText = node != null ? safeTrim(NodeModelUtils.getTokenText(node)) : null;
        if (symbolName == null) {
            symbolName = symbolText;
        }

        int[] start = lineColFromOffset(context.text(), node != null ? node.getOffset() : context.offset());
        int[] end = lineColFromOffset(context.text(), node != null ? node.getEndOffset() : context.offset());
        EObject container = context.element().eContainer();

        return new BslSymbolResult(
                request.getProjectName(),
                request.getFilePath(),
                request.getLine(),
                request.getColumn(),
                context.offset(),
                symbolKind(context.element()),
                symbolName,
                symbolText,
                context.element().eClass().getName(),
                EcoreUtil.getURI(context.element()).toString(),
                container != null ? container.eClass().getName() : null,
                container != null ? firstNonBlank(
                        getStringFeature(container, "name"), //$NON-NLS-1$
                        getStringFeature(container, "nameRu")) : null, //$NON-NLS-1$
                start[0],
                start[1],
                end[0],
                end[1]);
    }

    public BslTypeResult getTypeAtPosition(BslPositionRequest request) {
        request.validate();
        try {
            PositionContext context = resolveContext(request);
            List<BslTypeResult.TypeInfo> types = computeTypes(context);
            return new BslTypeResult(
                    request.getProjectName(),
                    request.getFilePath(),
                    request.getLine(),
                    request.getColumn(),
                    context.offset(),
                    context.element().eClass().getName(),
                    types);
        } catch (EdtAstException e) {
            if (!canFallbackToContentAssist(e)) {
                throw e;
            }
            return new BslTypeResult(
                    request.getProjectName(),
                    request.getFilePath(),
                    request.getLine(),
                    request.getColumn(),
                    -1,
                    "Unavailable", //$NON-NLS-1$
                    List.of());
        }
    }

    public BslScopeMembersResult getScopeMembers(BslScopeMembersRequest request) {
        request.validate();
        List<BslTypeResult.TypeInfo> types = List.of();
        try {
            PositionContext context = resolveContext(request.toPositionRequest());
            types = computeTypes(context);
        } catch (EdtAstException e) {
            if (!canFallbackToContentAssist(e)) {
                throw e;
            }
        }

        List<String> resolvedTypes = new ArrayList<>();
        for (BslTypeResult.TypeInfo type : types) {
            String display = displayTypeName(type, request.getLanguageNormalized());
            if (display != null && !display.isBlank() && !resolvedTypes.contains(display)) {
                resolvedTypes.add(display);
            }
        }

        List<BslScopeMembersResult.MemberItem> all = new ArrayList<>();
        all.addAll(collectMembersFromPlatformDoc(request, types));
        if (all.isEmpty()) {
            all.addAll(collectMembersFromContentAssist(request));
        }

        int total = all.size();
        int from = Math.min(request.getOffset(), total);
        int to = Math.min(from + request.getLimit(), total);
        List<BslScopeMembersResult.MemberItem> page = all.subList(from, to);

        return new BslScopeMembersResult(
                request.getProjectName(),
                request.getFilePath(),
                request.getLine(),
                request.getColumn(),
                resolvedTypes,
                total,
                to < total,
                page);
    }

    public BslModuleMethodsResult listMethods(BslModuleMethodsRequest request) {
        request.validate();
        ModuleContext context = resolveModuleContext(request.getProjectName(), request.getFilePath());
        LineIndex lineIndex = new LineIndex(context.text());
        List<ResolvedMethod> methods = collectMethods(context.module(), context.text(), lineIndex);

        String kindFilter = request.normalizedKind();
        String nameFilter = request.normalizedNameContains();
        List<ResolvedMethod> filtered = new ArrayList<>();
        for (ResolvedMethod method : methods) {
            if (!"any".equals(kindFilter) && !kindFilter.equals(method.kind())) { //$NON-NLS-1$
                continue;
            }
            if (!nameFilter.isEmpty()) {
                String normalizedName = normalize(method.name());
                if (!normalizedName.contains(nameFilter)) {
                    continue;
                }
            }
            filtered.add(method);
        }

        int total = filtered.size();
        int from = Math.min(request.getOffset(), total);
        int to = Math.min(from + request.getLimit(), total);
        List<BslMethodInfo> page = new ArrayList<>();
        for (int i = from; i < to; i++) {
            page.add(filtered.get(i).toInfo());
        }

        return new BslModuleMethodsResult(
                request.getProjectName(),
                request.getFilePath(),
                total,
                to < total,
                page);
    }

    public BslMethodBodyResult getMethodBody(BslMethodBodyRequest request) {
        request.validate();
        ModuleContext context = resolveModuleContext(request.getProjectName(), request.getFilePath());
        LineIndex lineIndex = new LineIndex(context.text());
        List<ResolvedMethod> methods = collectMethods(context.module(), context.text(), lineIndex);

        String kindFilter = request.normalizedKind();
        String nameFilter = request.normalizedName();
        List<ResolvedMethod> matches = new ArrayList<>();
        for (ResolvedMethod method : methods) {
            if (!"any".equals(kindFilter) && !kindFilter.equals(method.kind())) { //$NON-NLS-1$
                continue;
            }
            if (!normalize(method.name()).equals(nameFilter)) {
                continue;
            }
            matches.add(method);
        }

        Integer startLineFilter = request.getStartLine();
        if (startLineFilter != null) {
            List<ResolvedMethod> filtered = new ArrayList<>();
            for (ResolvedMethod method : matches) {
                if (method.startLine() == startLineFilter.intValue()) {
                    filtered.add(method);
                }
            }
            matches = filtered;
        }

        if (matches.isEmpty()) {
            throw new EdtAstException(EdtAstErrorCode.METHOD_NOT_FOUND,
                    "Method not found: " + request.getName(), false); //$NON-NLS-1$
        }

        if (matches.size() > 1) {
            ResolvedMethod preferred = selectPreferredMethod(matches);
            if (preferred != null) {
                matches = List.of(preferred);
            } else {
                List<BslMethodCandidate> candidates = new ArrayList<>();
                for (ResolvedMethod method : matches) {
                    candidates.add(new BslMethodCandidate(
                            method.name(),
                            method.kind(),
                            method.startLine(),
                            method.endLine()));
                }
                throw new BslMethodLookupException(
                        EdtAstErrorCode.AMBIGUOUS_METHOD,
                        "Ambiguous method name: " + request.getName(), //$NON-NLS-1$
                        true,
                        candidates);
            }
        }

        ResolvedMethod method = matches.get(0);
        int startLine = method.startLine();
        int endLine = method.endLine();
        int startOffset = method.startOffset();
        int endOffset = method.endOffset();

        int contextLines = request.getContextLines();
        if (contextLines > 0) {
            int fromLine = Math.max(1, startLine - contextLines);
            int toLine = Math.min(lineIndex.totalLines(), endLine + contextLines);
            startOffset = lineIndex.startOffset(fromLine);
            endOffset = lineIndex.startOffset(toLine + 1);
            startLine = fromLine;
            endLine = toLine;
        }

        String text = sliceText(context.text(), startOffset, endOffset);
        return new BslMethodBodyResult(
                request.getProjectName(),
                request.getFilePath(),
                method.name(),
                method.kind(),
                startLine,
                endLine,
                text);
    }

    private PositionContext resolveContext(BslPositionRequest request) {
        IProject project = gateway.resolveProject(request.getProjectName());
        readinessChecker.ensureReady(project);

        IFile file = gateway.resolveSourceFile(project, request.getFilePath());
        if (file == null || !file.exists()) {
            throw new EdtAstException(EdtAstErrorCode.FILE_NOT_FOUND,
                    "File not found: " + request.getFilePath(), false); //$NON-NLS-1$
        }

        XtextResource resource = loadResource(project, file);
        String text = readResourceText(resource, file);
        int offset = calculateOffset(text, request.getLine(), request.getColumn());

        IResourceServiceProvider rsp = resourceServiceProvider(file);
        EObjectAtOffsetHelper helper = rsp != null ? rsp.get(EObjectAtOffsetHelper.class) : null;
        if (helper == null) {
            helper = new EObjectAtOffsetHelper();
        }

        EObject element = helper.resolveElementAt(resource, offset);
        if (element == null) {
            element = helper.resolveContainedElementAt(resource, offset);
        }
        if (element == null) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                    "Cannot resolve BSL element at line/column", false); //$NON-NLS-1$
        }

        return new PositionContext(project, file, resource, rsp, element, offset, text);
    }

    private ModuleContext resolveModuleContext(String projectName, String filePath) {
        IProject project = gateway.resolveProject(projectName);
        readinessChecker.ensureReady(project);

        IFile file = gateway.resolveSourceFile(project, filePath);
        if (file == null || !file.exists()) {
            throw new EdtAstException(EdtAstErrorCode.FILE_NOT_FOUND,
                    "File not found: " + filePath, false); //$NON-NLS-1$
        }

        XtextResource resource = loadResource(project, file);
        String text = readResourceText(resource, file);
        EObject root = resource.getParseResult() != null
                ? resource.getParseResult().getRootASTElement()
                : null;
        if (root == null) {
            throw new EdtAstException(EdtAstErrorCode.MODULE_PARSE_ERROR,
                    "Failed to parse BSL module: " + filePath, true); //$NON-NLS-1$
        }
        if (root.eClass().getEStructuralFeature("methods") == null //$NON-NLS-1$
                && root.eClass().getEStructuralFeature("allMethods") == null) { //$NON-NLS-1$
            throw new EdtAstException(EdtAstErrorCode.MODULE_PARSE_ERROR,
                    "Root AST element is not a BSL module for: " + filePath, true); //$NON-NLS-1$
        }

        return new ModuleContext(project, file, resource, root, text);
    }

    private XtextResource loadResource(IProject project, IFile file) {
        ResourceSet resourceSet = resolveResourceSet(project);
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        Resource resource = tryLoadResource(resourceSet, uri);
        if (resource == null) {
            resource = tryLoadResource(createStandaloneResourceSet(), uri);
        }
        if (resource == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to load BSL resource from EDT resource sets", true); //$NON-NLS-1$
        }
        if (!(resource instanceof XtextResource xtextResource)) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Resource is not XtextResource: " + uri, false); //$NON-NLS-1$
        }
        return xtextResource;
    }

    private ResourceSet resolveResourceSet(IProject project) {
        EdtAstException providerUnavailable = null;
        try {
            for (int attempt = 1; attempt <= RESOURCE_SET_RETRY_ATTEMPTS; attempt++) {
                ResourceSet resourceSet = gateway.getResourceSetProvider().get(project);
                if (resourceSet != null) {
                    return resourceSet;
                }
                if (attempt == RESOURCE_SET_RETRY_ATTEMPTS) {
                    break;
                }
                try {
                    Thread.sleep(RESOURCE_SET_RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                            "Interrupted while waiting for EDT resource set initialization", true, e); //$NON-NLS-1$
                }
            }
        } catch (EdtAstException e) {
            if (e.getCode() != EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE) {
                throw e;
            }
            providerUnavailable = e;
        }
        ResourceSet fallback = createStandaloneResourceSet();
        if (fallback != null) {
            return fallback;
        }
        if (providerUnavailable != null) {
            throw providerUnavailable;
        }
        return new ResourceSetImpl();
    }

    private Resource tryLoadResource(ResourceSet resourceSet, URI uri) {
        if (resourceSet == null || uri == null) {
            return null;
        }
        try {
            return resourceSet.getResource(uri, true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ResourceSet createStandaloneResourceSet() {
        try {
            return new XtextResourceSet();
        } catch (RuntimeException e) {
            return new ResourceSetImpl();
        }
    }

    private boolean canFallbackToContentAssist(EdtAstException e) {
        return e != null
                && e.getCode() == EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE
                && e.isRecoverable();
    }

    private IResourceServiceProvider resourceServiceProvider(IFile file) {
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        return IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
    }

    private List<BslTypeResult.TypeInfo> computeTypes(PositionContext context) {
        IResourceServiceProvider rsp = context.resourceServiceProvider();
        if (rsp == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "IResourceServiceProvider is unavailable for BSL resource", false); //$NON-NLS-1$
        }
        TypesComputer typesComputer = rsp.get(TypesComputer.class);
        if (typesComputer == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "TypesComputer is unavailable for BSL resource", false); //$NON-NLS-1$
        }

        List<TypeItem> typeItems;
        try {
            typeItems = typesComputer.computeTypes(context.element(), Environments.ALL);
        } catch (Exception e) {
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "Failed to compute BSL types: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
        if (typeItems == null || typeItems.isEmpty()) {
            return List.of();
        }

        List<BslTypeResult.TypeInfo> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TypeItem type : typeItems) {
            if (type == null) {
                continue;
            }
            String name = safeTrim(type.getName());
            String nameRu = safeTrim(type.getNameRu());
            String compositeId = type.getCompositeId() != null ? String.valueOf(type.getCompositeId()) : null;
            String key = normalize(firstNonBlank(name, nameRu, compositeId));
            if (!seen.add(key)) {
                continue;
            }
            result.add(new BslTypeResult.TypeInfo(name, nameRu, compositeId));
        }
        return result;
    }

    private List<BslScopeMembersResult.MemberItem> collectMembersFromPlatformDoc(
            BslScopeMembersRequest request,
            List<BslTypeResult.TypeInfo> types) {
        if (types.isEmpty()) {
            return List.of();
        }

        Map<String, BslScopeMembersResult.MemberItem> dedup = new LinkedHashMap<>();

        for (BslTypeResult.TypeInfo type : types) {
            String queryType = firstNonBlank(type.getName(), type.getNameRu());
            if (queryType == null || queryType.isBlank()) {
                continue;
            }

            PlatformDocumentationResult doc;
            try {
                doc = platformDocService.getDocumentation(new PlatformDocumentationRequest(
                        request.getProjectName(),
                        queryType,
                        request.getLanguageNormalized(),
                        PlatformMemberFilter.ALL,
                        request.getContains(),
                        200,
                        0));
            } catch (PlatformDocumentationException e) {
                continue;
            }

            String ownerType = firstNonBlank(doc.resolvedTypeName(), doc.resolvedTypeNameRu(), queryType);
            for (PlatformDocumentationResult.MethodDoc method : doc.methods()) {
                String signature = buildMethodSignature(method);
                List<String> returnTypes = method.returnTypes() != null ? method.returnTypes() : List.of();
                BslScopeMembersResult.MemberItem item = new BslScopeMembersResult.MemberItem(
                        "method", //$NON-NLS-1$
                        method.name(),
                        method.nameRu(),
                        ownerType,
                        signature,
                        returnTypes,
                        "platform_doc"); //$NON-NLS-1$
                dedup.putIfAbsent(memberKey(item), item);
            }
            for (PlatformDocumentationResult.PropertyDoc property : doc.properties()) {
                List<String> returnTypes = property.types() != null ? property.types() : List.of();
                BslScopeMembersResult.MemberItem item = new BslScopeMembersResult.MemberItem(
                        "property", //$NON-NLS-1$
                        property.name(),
                        property.nameRu(),
                        ownerType,
                        property.name(),
                        returnTypes,
                        "platform_doc"); //$NON-NLS-1$
                dedup.putIfAbsent(memberKey(item), item);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private List<BslScopeMembersResult.MemberItem> collectMembersFromContentAssist(BslScopeMembersRequest request) {
        ContentAssistRequest assistRequest = new ContentAssistRequest(
                request.getProjectName(),
                request.getFilePath(),
                request.getLine(),
                request.getColumn(),
                200,
                0,
                request.getContains(),
                false);
        ContentAssistResult result = contentAssistService.getContentAssist(assistRequest);
        List<BslScopeMembersResult.MemberItem> items = new ArrayList<>();
        for (ContentAssistResult.Item item : result.getItems()) {
            items.add(new BslScopeMembersResult.MemberItem(
                    "proposal", //$NON-NLS-1$
                    item.getLabel(),
                    null,
                    null,
                    item.getLabel(),
                    List.of(),
                    "content_assist")); //$NON-NLS-1$
        }
        return items;
    }

    private String buildMethodSignature(PlatformDocumentationResult.MethodDoc method) {
        if (method == null) {
            return null;
        }
        String name = firstNonBlank(method.name(), method.nameRu(), "method"); //$NON-NLS-1$
        if (method.paramSets() == null || method.paramSets().isEmpty()) {
            return name + "()"; //$NON-NLS-1$
        }
        PlatformDocumentationResult.ParamSetDoc first = method.paramSets().get(0);
        List<String> params = new ArrayList<>();
        for (PlatformDocumentationResult.ParameterDoc parameter : first.params()) {
            params.add(firstNonBlank(parameter.name(), parameter.nameRu(), "arg")); //$NON-NLS-1$
        }
        return name + "(" + String.join(", ", params) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private String memberKey(BslScopeMembersResult.MemberItem item) {
        return normalize(item.getKind()) + "|" + normalize(item.getOwnerType()) + "|" + normalize(item.getName()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String displayTypeName(BslTypeResult.TypeInfo type, String language) {
        if ("ru".equalsIgnoreCase(language)) { //$NON-NLS-1$
            return firstNonBlank(type.getNameRu(), type.getName(), type.getCompositeId());
        }
        return firstNonBlank(type.getName(), type.getNameRu(), type.getCompositeId());
    }

    private int calculateOffset(String text, int line, int column) {
        if (text == null) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                    "Cannot compute position without text content", false); //$NON-NLS-1$
        }

        int currentLine = 1;
        int index = 0;
        while (index < text.length() && currentLine < line) {
            char ch = text.charAt(index++);
            if (ch == '\n') {
                currentLine++;
            } else if (ch == '\r') {
                if (index < text.length() && text.charAt(index) == '\n') {
                    index++;
                }
                currentLine++;
            }
        }
        if (currentLine != line) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                    "Line is outside document bounds", false); //$NON-NLS-1$
        }

        int lineEnd = index;
        while (lineEnd < text.length()) {
            char ch = text.charAt(lineEnd);
            if (ch == '\n' || ch == '\r') {
                break;
            }
            lineEnd++;
        }
        int lineLength = lineEnd - index;
        int columnIndex = column - 1;
        if (columnIndex < 0 || columnIndex > lineLength) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                    "Column is outside document bounds", false); //$NON-NLS-1$
        }
        return index + columnIndex;
    }

    private int[] lineColFromOffset(String text, int offset) {
        if (text == null) {
            return new int[] {1, 1};
        }
        LineIndex index = new LineIndex(text);
        int bounded = Math.max(0, Math.min(offset, text.length()));
        int line = index.lineOfOffset(bounded);
        int lineStart = index.startOffset(line);
        return new int[] {line, bounded - lineStart + 1};
    }

    private String symbolKind(EObject element) {
        String className = element.eClass().getName();
        String normalized = className.toLowerCase(Locale.ROOT);
        if (normalized.contains("variable")) { //$NON-NLS-1$
            return "variable"; //$NON-NLS-1$
        }
        if (normalized.contains("invocation") || normalized.contains("call")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "invocation"; //$NON-NLS-1$
        }
        if (normalized.contains("method")) { //$NON-NLS-1$
            return "method"; //$NON-NLS-1$
        }
        if (normalized.contains("property")) { //$NON-NLS-1$
            return "property"; //$NON-NLS-1$
        }
        if (normalized.contains("module")) { //$NON-NLS-1$
            return "module"; //$NON-NLS-1$
        }
        return normalized;
    }

    private String readResourceText(XtextResource resource, IFile file) {
        if (resource != null
                && resource.getParseResult() != null
                && resource.getParseResult().getRootNode() != null) {
            String text = resource.getParseResult().getRootNode().getText();
            if (text != null) {
                return text;
            }
        }
        return readFileText(file);
    }

    private String readFileText(IFile file) {
        if (file == null || !file.exists()) {
            return ""; //$NON-NLS-1$
        }
        try (InputStream input = file.getContents()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (CoreException | IOException e) {
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "Failed to read BSL file content: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
    }

    private List<ResolvedMethod> collectMethods(EObject module, String text, LineIndex lineIndex) {
        List<EObject> methods = getEObjectList(module, "methods"); //$NON-NLS-1$
        if (methods.isEmpty()) {
            methods = getEObjectList(module, "allMethods"); //$NON-NLS-1$
        }

        List<ResolvedMethod> result = new ArrayList<>();
        for (EObject method : methods) {
            if (method == null) {
                continue;
            }
            String name = firstNonBlank(
                    getStringFeature(method, "name"), //$NON-NLS-1$
                    getStringFeature(method, "nameRu")); //$NON-NLS-1$
            if (name == null) {
                continue;
            }

            INode node = NodeModelUtils.getNode(method);
            if (node == null) {
                throw new EdtAstException(EdtAstErrorCode.MODULE_PARSE_ERROR,
                        "Failed to resolve AST node for method: " + name, true); //$NON-NLS-1$
            }

            int startOffset = node.getOffset();
            int endOffset = startOffset + node.getLength();
            int startLine = lineIndex.lineOfOffset(startOffset);
            int endLine = lineIndex.lineOfOffset(endOffset);

            String kind = methodKind(method);
            boolean isExport = getBooleanFeature(method, "export"); //$NON-NLS-1$
            boolean isAsync = getBooleanFeature(method, "async"); //$NON-NLS-1$
            boolean isEvent = getBooleanFeature(method, "event"); //$NON-NLS-1$
            List<BslMethodParamInfo> params = collectParams(method);

            result.add(new ResolvedMethod(
                    name,
                    kind,
                    startLine,
                    endLine,
                    startOffset,
                    endOffset,
                    isExport,
                    isAsync,
                    isEvent,
                    params));
        }
        return result;
    }

    private List<BslMethodParamInfo> collectParams(EObject method) {
        List<EObject> params = getEObjectList(method, "formalParams"); //$NON-NLS-1$
        if (params.isEmpty()) {
            return List.of();
        }
        List<BslMethodParamInfo> result = new ArrayList<>();
        for (EObject param : params) {
            if (param == null) {
                continue;
            }
            String name = firstNonBlank(
                    getStringFeature(param, "name"), //$NON-NLS-1$
                    getStringFeature(param, "nameRu")); //$NON-NLS-1$
            boolean byValue = getBooleanFeature(param, "byValue"); //$NON-NLS-1$
            EObject defaultValue = getEObjectFeature(param, "defaultValue"); //$NON-NLS-1$
            String defaultText = defaultValue != null ? extractLiteralText(defaultValue) : null;
            result.add(new BslMethodParamInfo(name, byValue, defaultText));
        }
        return result;
    }

    private String methodKind(EObject method) {
        if (method == null) {
            return "method"; //$NON-NLS-1$
        }
        String className = method.eClass().getName();
        if (className == null) {
            return "method"; //$NON-NLS-1$
        }
        String normalized = className.toLowerCase(Locale.ROOT);
        if (normalized.contains("procedure")) { //$NON-NLS-1$
            return "procedure"; //$NON-NLS-1$
        }
        if (normalized.contains("function")) { //$NON-NLS-1$
            return "function"; //$NON-NLS-1$
        }
        return "method"; //$NON-NLS-1$
    }

    private List<EObject> getEObjectList(EObject object, String featureName) {
        if (object == null || featureName == null) {
            return List.of();
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            return List.of();
        }
        Object value = object.eGet(feature);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<EObject> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof EObject eObject) {
                result.add(eObject);
            }
        }
        return result;
    }

    private EObject getEObjectFeature(EObject object, String featureName) {
        if (object == null || featureName == null) {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            return null;
        }
        Object value = object.eGet(feature);
        return value instanceof EObject eObject ? eObject : null;
    }

    private boolean getBooleanFeature(EObject object, String featureName) {
        if (object == null || featureName == null) {
            return false;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            return false;
        }
        Object value = object.eGet(feature);
        return value instanceof Boolean bool ? bool : false;
    }

    private String extractLiteralText(EObject literal) {
        if (literal == null) {
            return null;
        }
        INode node = NodeModelUtils.findActualNodeFor(literal);
        String text = node != null ? safeTrim(NodeModelUtils.getTokenText(node)) : null;
        return text;
    }

    private String sliceText(String text, int startOffset, int endOffset) {
        if (text == null || text.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        int safeStart = Math.max(0, Math.min(startOffset, text.length()));
        int safeEnd = Math.max(safeStart, Math.min(endOffset, text.length()));
        return text.substring(safeStart, safeEnd);
    }

    private String getStringFeature(EObject object, String featureName) {
        if (object == null || featureName == null) {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            return null;
        }
        Object value = object.eGet(feature);
        if (!(value instanceof String text)) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    private String safeTrim(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private ResolvedMethod selectPreferredMethod(List<ResolvedMethod> matches) {
        ResolvedMethod exported = selectUnique(matches, ResolvedMethod::exportFlag);
        if (exported != null) {
            return exported;
        }
        return null;
    }

    private ResolvedMethod selectUnique(List<ResolvedMethod> matches, Predicate<ResolvedMethod> predicate) {
        ResolvedMethod candidate = null;
        for (ResolvedMethod method : matches) {
            if (!predicate.test(method)) {
                continue;
            }
            if (candidate != null) {
                return null;
            }
            candidate = method;
        }
        return candidate;
    }

    private record ResolvedMethod(
            String name,
            String kind,
            int startLine,
            int endLine,
            int startOffset,
            int endOffset,
            boolean exportFlag,
            boolean asyncFlag,
            boolean eventFlag,
            List<BslMethodParamInfo> params) {
        BslMethodInfo toInfo() {
            return new BslMethodInfo(
                    name,
                    kind,
                    startLine,
                    endLine,
                    exportFlag,
                    asyncFlag,
                    eventFlag,
                    params);
        }
    }

    private static final class LineIndex {
        private final int[] lineStarts;
        private final int textLength;

        LineIndex(String text) {
            if (text == null || text.isEmpty()) {
                this.textLength = 0;
                this.lineStarts = new int[] {0};
                return;
            }
            this.textLength = text.length();
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            int i = 0;
            while (i < text.length()) {
                char ch = text.charAt(i++);
                if (ch == '\n') {
                    starts.add(i);
                } else if (ch == '\r') {
                    if (i < text.length() && text.charAt(i) == '\n') {
                        i++;
                    }
                    starts.add(i);
                }
            }
            this.lineStarts = new int[starts.size()];
            for (int idx = 0; idx < starts.size(); idx++) {
                this.lineStarts[idx] = starts.get(idx);
            }
        }

        int totalLines() {
            return lineStarts.length;
        }

        int startOffset(int line) {
            if (line <= 1) {
                return 0;
            }
            if (line > lineStarts.length) {
                return textLength;
            }
            return lineStarts[line - 1];
        }

        int lineOfOffset(int offset) {
            int bounded = Math.max(0, Math.min(offset, textLength));
            int low = 0;
            int high = lineStarts.length - 1;
            int result = 0;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int start = lineStarts[mid];
                if (start <= bounded) {
                    result = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return result + 1;
        }
    }

    private record ModuleContext(
            IProject project,
            IFile file,
            XtextResource resource,
            EObject module,
            String text) {
        ModuleContext {
            Objects.requireNonNull(project);
            Objects.requireNonNull(file);
            Objects.requireNonNull(resource);
            Objects.requireNonNull(module);
            Objects.requireNonNull(text);
        }
    }

    private record PositionContext(
            IProject project,
            IFile file,
            XtextResource resource,
            IResourceServiceProvider resourceServiceProvider,
            EObject element,
            int offset,
            String text) {
        PositionContext {
            Objects.requireNonNull(project);
            Objects.requireNonNull(file);
            Objects.requireNonNull(resource);
            Objects.requireNonNull(element);
            Objects.requireNonNull(text);
        }
    }
}
