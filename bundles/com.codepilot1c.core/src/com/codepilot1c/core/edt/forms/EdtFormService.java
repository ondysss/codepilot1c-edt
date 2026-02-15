package com.codepilot1c.core.edt.forms;

import com.codepilot1c.core.edt.metadata.EdtMetadataService;

/**
 * Dedicated service layer for EDT managed form operations.
 */
public class EdtFormService {

    private final EdtMetadataService metadataService;

    public EdtFormService() {
        this(new EdtMetadataService());
    }

    EdtFormService(EdtMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    public CreateFormResult createForm(CreateFormRequest request) {
        return metadataService.createForm(request);
    }
}
