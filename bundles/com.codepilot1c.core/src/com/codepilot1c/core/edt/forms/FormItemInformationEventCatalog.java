package com.codepilot1c.core.edt.forms;

import java.util.List;

import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.mcore.Event;

/**
 * Default {@link EventHandlerCatalog} implementation delegating to the platform
 * {@link FormItemInformationService}.
 *
 * <p>This is the only class in the codebase that references
 * {@code FormItemInformationService} directly, so the platform dependency stays
 * isolated behind the {@link EventHandlerCatalog} seam.</p>
 */
public class FormItemInformationEventCatalog implements EventHandlerCatalog {

    @Override
    public List<Event> allowedEvents(FormVisualEntity item) {
        return new FormItemInformationService().getAllowedEvents(item);
    }
}
