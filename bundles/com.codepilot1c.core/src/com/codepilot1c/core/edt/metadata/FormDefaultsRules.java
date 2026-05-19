package com.codepilot1c.core.edt.metadata;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java decision tables for the "materialize platform defaults" pass
 * that runs after every {@code mutate_form_model} / {@code apply_form_recipe}
 * mutation.
 *
 * <p>Background: the BM API form serializer drops EMF features whose
 * {@code eIsSet()} is {@code false}, even when the 1С platform (and
 * Configurator on round-trip) requires them. EDT designer then silently
 * fails to render the form despite {@code get_diagnostics} reporting zero
 * markers. See {@code 2026-05-18-bm-serialization-lossy.md} for the
 * incident report.</p>
 *
 * <p>This class encapsulates the <em>decisions</em> that drive that pass —
 * which events live in {@code InputFieldExtInfo} vs at top-level
 * FormField, which attribute names get implicit {@code view}/{@code edit}
 * blocks, how to name the auto-generated {@code ContextMenu} and the
 * three {@code Table} {@code Addition} sub-elements. The corresponding
 * EMF model mutations live in {@code EdtMetadataService} where they
 * have access to {@code FormFactory} / {@code McoreFactory}.</p>
 */
public final class FormDefaultsRules {

    private FormDefaultsRules() { }

    // ---- Event-handler container priority --------------------------------

    /**
     * Events that the 1С platform declares on {@code InputFieldExtInfo}
     * rather than the FormField top-level. Configurator emits handlers
     * for these inside the {@code <extInfo>} block; the BM API's
     * {@code applyEventHandlersBinding} historically routed them
     * top-level because its {@code findEventByName(topLevel,...)} check
     * runs first and a partial overlap can mask the right answer.
     *
     * <p>This set lets the normalize pass re-bucket existing handlers
     * after a mutation: if the handler's Event name is in here and the
     * field carries an {@code InputFieldExtInfo}, move the handler from
     * the top-level FormField container into the extInfo's container.</p>
     *
     * <p>Source: feedback file (Category #2) + cross-check against the
     * Configurator-rewritten {@code Form.form} for
     * {@code DataProcessor.EdtFormPlayground.Form.Form}.</p>
     */
    public static final Set<String> INPUT_FIELD_EXT_INFO_EVENTS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Set.of(
                    "Clearing", //$NON-NLS-1$
                    "TextEditEnd", //$NON-NLS-1$
                    "AutoComplete", //$NON-NLS-1$
                    "StartChoice", //$NON-NLS-1$
                    "ChoiceProcessing"))); //$NON-NLS-1$
    // Kept narrow on purpose: only the events the Configurator-rewritten
    // form for DataProcessor.EdtFormPlayground.Form.Form moved into
    // <extInfo>. Adding more events without observing Configurator's
    // round-trip behavior would risk re-bucketing handlers in the wrong
    // direction.

    /**
     * Events that must <strong>stay</strong> at the top-level FormField
     * container even when the field has an {@code InputFieldExtInfo} that
     * also declares the same event. Configurator round-trips these at
     * top-level on every form.
     */
    public static final Set<String> FORM_FIELD_TOP_LEVEL_EVENTS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Set.of(
                    "OnChange", //$NON-NLS-1$
                    "OnReceiveHandler"))); //$NON-NLS-1$

    /**
     * Decide whether a given event name should be routed into the
     * {@code InputFieldExtInfo} container instead of the top-level
     * FormField container.
     *
     * <p>The rule is: events explicitly pinned top-level
     * ({@link #FORM_FIELD_TOP_LEVEL_EVENTS}) stay top-level; events
     * listed in {@link #INPUT_FIELD_EXT_INFO_EVENTS} go to extInfo;
     * everything else stays where the EDT
     * {@code FormItemInformationService.getAllowedEvents} resolver
     * placed it.</p>
     */
    public static boolean preferExtInfoForInputField(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return false;
        }
        if (FORM_FIELD_TOP_LEVEL_EVENTS.contains(eventName)) {
            return false;
        }
        return INPUT_FIELD_EXT_INFO_EVENTS.contains(eventName);
    }

    // ---- Attribute view/edit -------------------------------------------

    /**
     * Attribute names that do <strong>not</strong> get an implicit
     * {@code <view>} / {@code <edit>} block emitted by the normalize
     * pass. {@code Object} is the data-bound primary attribute (already
     * implicit per the platform); explicit view/edit blocks on it would
     * be wrong.
     */
    public static final Set<String> ATTRIBUTES_WITHOUT_IMPLICIT_VIEW_EDIT = Collections.unmodifiableSet(
            new LinkedHashSet<>(Set.of("Object"))); //$NON-NLS-1$

    /**
     * Whether the normalize pass should materialize {@code <view><common>true</common></view>}
     * and {@code <edit><common>true</common></edit>} on the given form
     * attribute when those EMF features are currently null.
     */
    public static boolean shouldMaterializeAttributeViewEdit(String attributeName) {
        if (attributeName == null || attributeName.isBlank()) {
            return false;
        }
        return !ATTRIBUTES_WITHOUT_IMPLICIT_VIEW_EDIT.contains(attributeName);
    }

    // ---- Auto-generated sub-element names ------------------------------

    /**
     * Suffix used by Configurator for the auto-generated context menu
     * attached to a visual item. Stable across the 8.3 platform line.
     */
    public static final String CONTEXT_MENU_SUFFIX = "ContextMenu"; //$NON-NLS-1$

    /**
     * Suffix used by Configurator for the auto-generated extended tooltip
     * label attached to a visual item.
     */
    public static final String EXTENDED_TOOLTIP_SUFFIX = "ExtendedTooltip"; //$NON-NLS-1$

    /**
     * Name pattern for an auto-generated {@code ContextMenu} hanging off
     * the given parent item. Configurator emits
     * {@code <parentName>ContextMenu}.
     */
    public static String contextMenuNameFor(String parentItemName) {
        return safe(parentItemName) + CONTEXT_MENU_SUFFIX;
    }

    /**
     * Name pattern for an auto-generated {@code ExtendedTooltip} label
     * hanging off the given parent item.
     */
    public static String extendedTooltipNameFor(String parentItemName) {
        return safe(parentItemName) + EXTENDED_TOOLTIP_SUFFIX;
    }

    /**
     * Name patterns for the three Addition helpers a {@code Table}
     * carries by default. Configurator emits
     * {@code <tableName>SearchString}, {@code <tableName>ViewStatus},
     * {@code <tableName>SearchControl}.
     */
    public enum AdditionKind {
        SEARCH_STRING("SearchString"), //$NON-NLS-1$
        VIEW_STATUS("ViewStatus"), //$NON-NLS-1$
        SEARCH_CONTROL("SearchControl"); //$NON-NLS-1$

        private final String suffix;

        AdditionKind(String suffix) {
            this.suffix = suffix;
        }

        public String suffix() {
            return suffix;
        }

        public String nameFor(String tableName) {
            return safe(tableName) + suffix;
        }
    }

    // ---- UsualGroup layout parsers (category #7) -----------------------

    /**
     * Parse the {@code group} property of an {@code add_group} /
     * {@code set_item} call into a {@code FormChildrenGroup} enum
     * literal string (the value EMF's {@code FormChildrenGroup.getByName}
     * accepts).
     *
     * <p>Accepts the canonical EMF literal ("AlwaysHorizontal"),
     * snake_case ("always_horizontal"), kebab-case
     * ("always-horizontal"), and case-insensitive matching. Returns
     * {@code null} for unknown / blank input so the caller can leave
     * the EMF default in place.</p>
     */
    public static String parseFormChildrenGroupLiteral(Object raw) {
        String n = normalizeEnumToken(raw);
        if (n == null) {
            return null;
        }
        return switch (n) {
            case "horizontal" -> "Horizontal"; //$NON-NLS-1$ //$NON-NLS-2$
            case "vertical" -> "Vertical"; //$NON-NLS-1$ //$NON-NLS-2$
            case "auto" -> "Auto"; //$NON-NLS-1$ //$NON-NLS-2$
            case "alwayshorizontal" -> "AlwaysHorizontal"; //$NON-NLS-1$ //$NON-NLS-2$
            case "horizontalifpossible" -> "HorizontalIfPossible"; //$NON-NLS-1$ //$NON-NLS-2$
            case "autoscreentypesensitive" -> "AutoScreenTypeSensitive"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> null;
        };
    }

    /**
     * Parse the {@code behavior} property into a {@code UsualGroupBehavior}
     * literal. Enum literals (EDT 2025.1.x): {@code Usual}, {@code Collapsible},
     * {@code PopUp}, {@code Auto}.
     */
    public static String parseUsualGroupBehaviorLiteral(Object raw) {
        String n = normalizeEnumToken(raw);
        if (n == null) {
            return null;
        }
        return switch (n) {
            case "usual", "normal" -> "Usual"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "collapsible" -> "Collapsible"; //$NON-NLS-1$ //$NON-NLS-2$
            case "popup" -> "PopUp"; //$NON-NLS-1$ //$NON-NLS-2$
            case "auto" -> "Auto"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> null;
        };
    }

    /**
     * Parse the {@code representation} property into a
     * {@code UsualGroupRepresentation} literal. Enum literals (EDT 2025.1.x):
     * {@code None}, {@code StrongSeparation}, {@code WeakSeparation},
     * {@code NormalSeparation}, {@code Auto}.
     */
    public static String parseUsualGroupRepresentationLiteral(Object raw) {
        String n = normalizeEnumToken(raw);
        if (n == null) {
            return null;
        }
        return switch (n) {
            case "none" -> "None"; //$NON-NLS-1$ //$NON-NLS-2$
            case "weakseparation" -> "WeakSeparation"; //$NON-NLS-1$ //$NON-NLS-2$
            case "normalseparation" -> "NormalSeparation"; //$NON-NLS-1$ //$NON-NLS-2$
            case "strongseparation" -> "StrongSeparation"; //$NON-NLS-1$ //$NON-NLS-2$
            case "auto" -> "Auto"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> null;
        };
    }

    /**
     * Parse the {@code through_align} property into a
     * {@code UsualGroupThroughAlign} literal.
     */
    public static String parseUsualGroupThroughAlignLiteral(Object raw) {
        String n = normalizeEnumToken(raw);
        if (n == null) {
            return null;
        }
        return switch (n) {
            case "auto" -> "Auto"; //$NON-NLS-1$ //$NON-NLS-2$
            case "use" -> "Use"; //$NON-NLS-1$ //$NON-NLS-2$
            case "dontuse", "donotuse" -> "DontUse"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> null;
        };
    }

    /**
     * Parse the {@code current_row_use} property into a
     * {@code CurrentRowUse} literal.
     */
    public static String parseCurrentRowUseLiteral(Object raw) {
        String n = normalizeEnumToken(raw);
        if (n == null) {
            return null;
        }
        return switch (n) {
            case "auto" -> "Auto"; //$NON-NLS-1$ //$NON-NLS-2$
            case "use" -> "Use"; //$NON-NLS-1$ //$NON-NLS-2$
            case "dontuse", "donotuse" -> "DontUse"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> null;
        };
    }

    // ---- helpers --------------------------------------------------------

    private static String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    private static String normalizeEnumToken(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.toLowerCase(Locale.ROOT)
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
