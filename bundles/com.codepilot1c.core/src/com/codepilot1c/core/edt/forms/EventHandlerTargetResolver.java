package com.codepilot1c.core.edt.forms;

import java.util.List;
import java.util.stream.Collectors;

import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.mcore.Event;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Pure owner-kind guard + event-name resolution over an {@link EventHandlerCatalog}.
 *
 * <p>No event-name literal appears in this class — every comparison operand comes
 * from the injected {@link EventHandlerCatalog}'s result, so it stays resolvable
 * against whatever event catalog the current EDT platform version exposes.</p>
 */
public class EventHandlerTargetResolver {

    private final EventHandlerCatalog catalog;

    public EventHandlerTargetResolver() {
        this(new FormItemInformationEventCatalog());
    }

    EventHandlerTargetResolver(EventHandlerCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Requires the given form visual entity to support event handlers, i.e. to
     * implement {@link EventHandlerContainer}. Button/group actions are not
     * event-handler targets and must go through {@code add_command} instead.
     *
     * @param target the resolved form visual entity (a form item, or the form itself)
     * @return the target cast to {@link EventHandlerContainer}
     * @throws MetadataOperationException with code {@code INVALID_METADATA_CHANGE}
     *         if the target does not support event handlers
     */
    public EventHandlerContainer requireEventHandlerContainer(FormVisualEntity target) {
        if (!(target instanceof EventHandlerContainer container)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Event handlers are not supported on " + target.eClass().getName() //$NON-NLS-1$
                            + "; button/group actions go through add_command, not event handlers.", //$NON-NLS-1$
                    false);
        }
        return container;
    }

    /**
     * Resolves the requested event name (EN or RU, case-insensitive) against the
     * container's allowed events, obtained from the injected {@link EventHandlerCatalog}.
     *
     * @param container      the event-handler container (a {@link FormVisualEntity})
     * @param requestedEvent the requested event name, EN or RU, any case
     * @return the concrete matching {@link Event}
     * @throws MetadataOperationException with code {@code METADATA_NOT_FOUND} if no
     *         allowed event matches; the message enumerates the allowed names
     */
    public Event resolveConcreteEvent(EventHandlerContainer container, String requestedEvent) {
        List<Event> allowed = catalog.allowedEvents((FormVisualEntity) container);
        return allowed.stream()
                .filter(event -> requestedEvent != null
                        && (requestedEvent.equalsIgnoreCase(event.getName())
                                || requestedEvent.equalsIgnoreCase(event.getNameRu())))
                .findFirst()
                .orElseThrow(() -> new MetadataOperationException(
                        MetadataOperationCode.METADATA_NOT_FOUND,
                        "Unknown event '" + requestedEvent + "' for this item. Available events: " //$NON-NLS-1$ //$NON-NLS-2$
                                + allowed.stream().map(Event::getName).collect(Collectors.joining(", ")), //$NON-NLS-1$
                        false));
    }
}
