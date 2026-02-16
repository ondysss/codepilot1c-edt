package com.codepilot1c.core.edt.ast;

/**
 * Default implementation for internal EDT AST services.
 */
public class EdtAstService implements IEdtAstService {

    private final EdtContentAssistService contentAssistService;
    private final EdtReferenceService referenceService;
    private final EdtMetadataInspectorService metadataInspectorService;
    private final EdtMetadataIndexService metadataIndexService;

    public EdtAstService(EdtServiceGateway gateway) {
        ProjectReadinessChecker checker = new ProjectReadinessChecker(gateway);
        this.contentAssistService = new EdtContentAssistService(gateway, checker);
        this.referenceService = new EdtReferenceService(gateway, checker);
        this.metadataInspectorService = new EdtMetadataInspectorService(gateway, checker);
        this.metadataIndexService = new EdtMetadataIndexService(gateway, checker);
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
}
