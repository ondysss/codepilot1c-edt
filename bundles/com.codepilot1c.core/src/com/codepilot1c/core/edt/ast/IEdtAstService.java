package com.codepilot1c.core.edt.ast;

/**
 * Internal EDT AST service facade.
 */
public interface IEdtAstService {

    ContentAssistResult getContentAssist(ContentAssistRequest req);

    ReferenceSearchResult findReferences(FindReferencesRequest req);

    MetadataDetailsResult getMetadataDetails(MetadataDetailsRequest req);

    MetadataIndexResult scanMetadataIndex(MetadataIndexRequest req);

    GetBookmarksResult getBookmarks(GetBookmarksRequest req);

    GetTasksResult getTasks(GetTasksRequest req);

    /**
     * Start or stop profiling on the active debug target.
     */
    StartProfilingResult startProfiling(StartProfilingRequest req);

    /**
     * Retrieve profiling results for a project.
     */
    GetProfilingResultsResult getProfilingResults(GetProfilingResultsRequest req);
}
