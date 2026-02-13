package com.codepilot1c.core.edt.platformdoc;

import java.util.List;
import java.util.Map;

/**
 * JSON-friendly result for platform documentation query.
 */
public record PlatformDocumentationResult(
        String project,
        String queryTypeName,
        String resolvedTypeName,
        String resolvedTypeNameRu,
        String resolvedTypeFqn,
        String language,
        PlatformMemberFilter memberFilter,
        int limit,
        int offset,
        int totalMethods,
        int totalProperties,
        boolean hasMoreMethods,
        boolean hasMoreProperties,
        List<TypeCandidate> candidates,
        List<MethodDoc> methods,
        List<PropertyDoc> properties
) {
    public record TypeCandidate(
            String name,
            String nameRu,
            String fqn
    ) {
    }

    public record MethodDoc(
            String name,
            String nameRu,
            boolean hasReturnValue,
            List<String> returnTypes,
            List<ParamSetDoc> paramSets,
            Map<String, Object> help
    ) {
    }

    public record ParamSetDoc(
            int minParams,
            int maxParams,
            List<ParameterDoc> params
    ) {
    }

    public record ParameterDoc(
            String name,
            String nameRu,
            boolean out,
            boolean defaultValue,
            List<String> types
    ) {
    }

    public record PropertyDoc(
            String name,
            String nameRu,
            boolean readable,
            boolean writable,
            List<String> types,
            Map<String, Object> help
    ) {
    }
}
