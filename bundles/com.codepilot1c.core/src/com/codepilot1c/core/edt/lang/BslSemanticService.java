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

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;

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
    }

    public BslScopeMembersResult getScopeMembers(BslScopeMembersRequest request) {
        request.validate();
        PositionContext context = resolveContext(request.toPositionRequest());
        List<BslTypeResult.TypeInfo> types = computeTypes(context);

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

    private XtextResource loadResource(IProject project, IFile file) {
        ResourceSet resourceSet = gateway.getResourceSetProvider().get(project);
        if (resourceSet == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "BM-aware resource set is unavailable", false); //$NON-NLS-1$
        }

        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        Resource resource;
        try {
            resource = resourceSet.getResource(uri, true);
        } catch (RuntimeException e) {
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "Failed to load BSL resource: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
        if (!(resource instanceof XtextResource xtextResource)) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Resource is not XtextResource: " + uri, false); //$NON-NLS-1$
        }
        return xtextResource;
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
        int bounded = Math.max(0, Math.min(offset, text.length()));
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < bounded; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
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
