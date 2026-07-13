package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class EventHandlerTargetResolverTest {

    private static final String EVENT_ON_CHANGE_EN = "OnChange"; //$NON-NLS-1$
    private static final String EVENT_ON_CHANGE_RU = "ПриИзменении"; //$NON-NLS-1$
    private static final String EVENT_ON_OPEN_EN = "OnOpen"; //$NON-NLS-1$
    private static final String EVENT_ON_OPEN_RU = "ПриОткрытии"; //$NON-NLS-1$

    private final Event onChangeEvent = createEvent(EVENT_ON_CHANGE_EN, EVENT_ON_CHANGE_RU);
    private final Event onOpenEvent = createEvent(EVENT_ON_OPEN_EN, EVENT_ON_OPEN_RU);
    private final EventHandlerCatalog fakeCatalog =
            item -> List.of(onChangeEvent, onOpenEvent);
    private final EventHandlerTargetResolver resolver = new EventHandlerTargetResolver(fakeCatalog);

    @Test
    public void resolvesRequestedEventCaseInsensitiveRussian() {
        Form form = FormFactory.eINSTANCE.createForm();

        Event resolved = resolver.resolveConcreteEvent(form, EVENT_ON_CHANGE_RU.toLowerCase());

        assertSame(onChangeEvent, resolved);
    }

    @Test
    public void resolvesRequestedEventEnglish() {
        Form form = FormFactory.eINSTANCE.createForm();

        Event resolved = resolver.resolveConcreteEvent(form, EVENT_ON_CHANGE_EN);

        assertSame(onChangeEvent, resolved);
    }

    @Test
    public void unknownEventThrowsMetadataNotFoundWithAllowedNames() {
        Form form = FormFactory.eINSTANCE.createForm();

        try {
            resolver.resolveConcreteEvent(form, "Nonexistent"); //$NON-NLS-1$
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.METADATA_NOT_FOUND, e.getCode());
            assertTrue(e.getMessage().contains(EVENT_ON_CHANGE_EN));
            assertTrue(e.getMessage().contains(EVENT_ON_OPEN_EN));
        }
    }

    @Test
    public void nonContainerTargetThrowsInvalidMetadataChangeWithAddCommandRedirect() {
        Button button = FormFactory.eINSTANCE.createButton();

        try {
            resolver.requireEventHandlerContainer(button);
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue(e.getMessage().contains("add_command")); //$NON-NLS-1$
        }
    }

    @Test
    public void containerTargetReturnsEventHandlerContainer() {
        Form form = FormFactory.eINSTANCE.createForm();

        EventHandlerContainer container = resolver.requireEventHandlerContainer(form);

        assertSame(form, container);
    }

    private static Event createEvent(String nameEn, String nameRu) {
        Event event = McoreFactory.eINSTANCE.createEvent();
        event.setName(nameEn);
        event.setNameRu(nameRu);
        return event;
    }
}
