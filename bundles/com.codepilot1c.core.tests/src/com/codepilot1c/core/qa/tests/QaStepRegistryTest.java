package com.codepilot1c.core.qa.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.qa.QaStepRegistry;

public class QaStepRegistryTest {

    @Test
    public void loadsStructuredRegistryAndInfersRecipe() throws Exception {
        QaStepRegistry registry = QaStepRegistry.loadDefault();
        assertTrue(registry.findStepByIntent("document.open_list_form").isPresent()); //$NON-NLS-1$
        assertTrue(registry.findRecipe("create_document_draft").isPresent()); //$NON-NLS-1$
        assertEquals("create_document_draft",
                registry.inferRecipe("Подготовь smoke тест создания документа поступления товаров",
                        "Document") //$NON-NLS-1$ //$NON-NLS-2$
                        .orElseThrow()
                        .id());
    }
}
