package com.codepilot1c.core.edt.ast;

/**
 * Internal EDT AST service facade.
 */
public interface IEdtAstService {

    ContentAssistResult getContentAssist(ContentAssistRequest req);

    ReferenceSearchResult findReferences(FindReferencesRequest req);

    MetadataDetailsResult getMetadataDetails(MetadataDetailsRequest req);

    MetadataIndexResult scanMetadataIndex(MetadataIndexRequest req);
}
