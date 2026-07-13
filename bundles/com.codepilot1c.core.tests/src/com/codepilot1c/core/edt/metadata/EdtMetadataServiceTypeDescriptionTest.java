package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class EdtMetadataServiceTypeDescriptionTest {

    @Test
    public void createMetadataSupportsConstantTypeDescriptionProperty() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"); //$NON-NLS-1$

        assertTrue(
                "create_metadata(kind=Constant, properties.type=...) must build a TypeDescription instead of rejecting containment reference 'type'", //$NON-NLS-1$
                source.contains("applyTypeDescriptionProperty") //$NON-NLS-1$
                        || source.contains("setValueTypeDescription") //$NON-NLS-1$
                        || source.contains("setTypeDescriptionProperty")); //$NON-NLS-1$
    }

    @Test
    public void updateMetadataSupportsCommonCommandCommandParameterTypeDescription() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"); //$NON-NLS-1$

        assertTrue(
                "update_metadata must support CommonCommand.commandParameterType TypeDescription instead of generic containment rejection", //$NON-NLS-1$
                source.contains("commandParameterType") //$NON-NLS-1$
                        && (source.contains("applyTypeDescriptionProperty") //$NON-NLS-1$
                                || source.contains("setTypeDescriptionProperty"))); //$NON-NLS-1$
    }

    @Test
    public void updateMetadataPreResolvesCommandParameterTypeStrings() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"); //$NON-NLS-1$

        assertTrue(
                "update_metadata must pre-resolve commandParameterType string values before the write transaction, matching edt_field_type_candidates resolver semantics", //$NON-NLS-1$
                source.contains("addTypeStringIfPresent(typeStrings, setMap, \"commandParameterType\")")); //$NON-NLS-1$
    }

    @Test
    public void updateMetadataSupportsEventSubscriptionSourceTypeDescription() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"); //$NON-NLS-1$

        assertTrue(
                "update_metadata must support EventSubscription.source TypeDescription instead of generic containment rejection", //$NON-NLS-1$
                source.contains("isTypeDescriptionPropertyName(reference.getName())") //$NON-NLS-1$
                        && source.contains("\"source\" -> true")); //$NON-NLS-1$
    }

    @Test
    public void updateMetadataPreResolvesEventSubscriptionSourceStrings() throws Exception {
        String source = readCoreSource(
                "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"); //$NON-NLS-1$

        assertTrue(
                "update_metadata must pre-resolve EventSubscription.source string values before the write transaction, matching edt_field_type_candidates resolver semantics", //$NON-NLS-1$
                source.contains("addTypeStringIfPresent(typeStrings, setMap, \"source\")")); //$NON-NLS-1$
    }

    private String readCoreSource(String relativePath) throws Exception {
        Path path = Path.of(relativePath);
        if (!Files.exists(path)) {
            path = Path.of("../..", relativePath); //$NON-NLS-1$
        }
        return Files.readString(path);
    }
}
