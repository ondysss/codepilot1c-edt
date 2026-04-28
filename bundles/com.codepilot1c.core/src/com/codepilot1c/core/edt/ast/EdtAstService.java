package com.codepilot1c.core.edt.ast;

import java.util.List;

/**
 * Default implementation for internal EDT AST services.
 */
public class EdtAstService implements IEdtAstService {

    private final EdtContentAssistService contentAssistService;
    private final EdtReferenceService referenceService;
    private final EdtMetadataInspectorService metadataInspectorService;
    private final EdtMetadataIndexService metadataIndexService;
    private final EdtTaskMarkerService taskMarkerService;

    public EdtAstService(EdtServiceGateway gateway) {
        ProjectReadinessChecker checker = new ProjectReadinessChecker(gateway);
        this.contentAssistService = new EdtContentAssistService(gateway, checker);
        this.referenceService = new EdtReferenceService(gateway, checker);
        this.metadataInspectorService = new EdtMetadataInspectorService(gateway, checker);
        this.metadataIndexService = new EdtMetadataIndexService(gateway, checker);
        this.taskMarkerService = new EdtTaskMarkerService(gateway, checker);
    }

    @Override
    public ContentAssistResult getContentAssist(ContentAssistRequest req) {
        return contentAssistService.getContentAssist(req);
    }

    @Override
    public ReferenceSearchResult findReferences(FindReferencesRequest req) {
        return referenceService.findReferences(req);
    }

    @Override
    public MetadataDetailsResult getMetadataDetails(MetadataDetailsRequest req) {
        return metadataInspectorService.getMetadataDetails(req);
    }

    @Override
    public MetadataIndexResult scanMetadataIndex(MetadataIndexRequest req) {
        return metadataIndexService.scan(req);
    }

    @Override
    public GetBookmarksResult getBookmarks(GetBookmarksRequest req) {
        return taskMarkerService.getBookmarks(req);
    }

    @Override
    public GetTasksResult getTasks(GetTasksRequest req) {
        return taskMarkerService.getTasks(req);
    }

    @Override
    public StartProfilingResult startProfiling(StartProfilingRequest req) {
        // TODO: Implement EDT debug API integration for profiling
        req.validate();
        boolean enabled = req.getEnabled() != null && req.getEnabled();
        String status = enabled ? "Profiling enabled" : "Profiling disabled";
        return new StartProfilingResult(req.getProjectName(), enabled, status);
    }

    @Override
    public GetProfilingResultsResult getProfilingResults(GetProfilingResultsRequest req) {
        // TODO: Implement EDT debug API integration for retrieving profiling data
        req.validate();
        // Return empty results for now - will be implemented when EDT API is available
        return new GetProfilingResultsResult(req.getProjectName(), List.of());
    }
}
