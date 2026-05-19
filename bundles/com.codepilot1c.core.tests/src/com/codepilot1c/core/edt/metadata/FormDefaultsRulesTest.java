package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link FormDefaultsRules}.
 *
 * <p>Pins the decision tables that drive the post-mutation "materialize
 * platform defaults" pass. See {@code 2026-05-18-bm-serialization-lossy.md}
 * in the AM-side feedback notes for the incident report that motivated
 * these rules.</p>
 */
public class FormDefaultsRulesTest {

    // --- preferExtInfoForInputField -----------------------------------------

    @Test
    public void inputFieldEventsRouteToExtInfo() {
        // Category #2 of the lossy-serialization feedback: Configurator
        // rewrites these handlers into <extInfo xsi:type="form:InputFieldExtInfo">
        // on round-trip. The normalize pass must do the same.
        assertTrue(FormDefaultsRules.preferExtInfoForInputField("Clearing")); //$NON-NLS-1$
        assertTrue(FormDefaultsRules.preferExtInfoForInputField("TextEditEnd")); //$NON-NLS-1$
        assertTrue(FormDefaultsRules.preferExtInfoForInputField("AutoComplete")); //$NON-NLS-1$
        assertTrue(FormDefaultsRules.preferExtInfoForInputField("StartChoice")); //$NON-NLS-1$
        assertTrue(FormDefaultsRules.preferExtInfoForInputField("ChoiceProcessing")); //$NON-NLS-1$
    }

    @Test
    public void onChangeStaysAtFormFieldTopLevel() {
        // OnChange is declared on both FormField and InputFieldExtInfo —
        // Configurator emits it top-level. Pinning this prevents a future
        // refactor of the allowlist from accidentally moving OnChange.
        assertFalse(FormDefaultsRules.preferExtInfoForInputField("OnChange")); //$NON-NLS-1$
        assertFalse(FormDefaultsRules.preferExtInfoForInputField("OnReceiveHandler")); //$NON-NLS-1$
    }

    @Test
    public void unknownEventsDoNotPreferExtInfo() {
        // Unknown event names default to staying at top-level so the
        // EDT FormItemInformationService routing remains the source of
        // truth for events outside the allowlist.
        assertFalse(FormDefaultsRules.preferExtInfoForInputField("WidgetMadeUp")); //$NON-NLS-1$
        assertFalse(FormDefaultsRules.preferExtInfoForInputField(null));
        assertFalse(FormDefaultsRules.preferExtInfoForInputField("")); //$NON-NLS-1$
        assertFalse(FormDefaultsRules.preferExtInfoForInputField("   ")); //$NON-NLS-1$
    }

    // --- shouldMaterializeAttributeViewEdit ---------------------------------

    @Test
    public void objectAttributeIsExemptFromImplicitViewEdit() {
        // Category #4: 'Object' is the data-bound primary attribute and the
        // platform handles view/edit implicitly — explicit blocks would be
        // wrong. Every other named attribute gets <view><common>true</common></view>.
        assertFalse(FormDefaultsRules.shouldMaterializeAttributeViewEdit("Object")); //$NON-NLS-1$
    }

    @Test
    public void regularAttributesGetImplicitViewEdit() {
        assertTrue(FormDefaultsRules.shouldMaterializeAttributeViewEdit("TextField")); //$NON-NLS-1$
        assertTrue(FormDefaultsRules.shouldMaterializeAttributeViewEdit("Quantity")); //$NON-NLS-1$
        assertTrue(FormDefaultsRules.shouldMaterializeAttributeViewEdit("Counterparty")); //$NON-NLS-1$
        assertTrue(FormDefaultsRules.shouldMaterializeAttributeViewEdit("CompaniesList")); //$NON-NLS-1$
    }

    @Test
    public void nullOrBlankAttributeNameDoesNotMaterialize() {
        assertFalse(FormDefaultsRules.shouldMaterializeAttributeViewEdit(null));
        assertFalse(FormDefaultsRules.shouldMaterializeAttributeViewEdit("")); //$NON-NLS-1$
        assertFalse(FormDefaultsRules.shouldMaterializeAttributeViewEdit("   ")); //$NON-NLS-1$
    }

    // --- auto-generated sub-element names -----------------------------------

    @Test
    public void contextMenuNamePattern() {
        // Configurator emits <parentName>ContextMenu on round-trip.
        assertEquals("LinesTableContextMenu", FormDefaultsRules.contextMenuNameFor("LinesTable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("TitledGroupLabelContextMenu", //$NON-NLS-1$
                FormDefaultsRules.contextMenuNameFor("TitledGroupLabel")); //$NON-NLS-1$
        assertEquals("CompaniesListDescriptionContextMenu", //$NON-NLS-1$
                FormDefaultsRules.contextMenuNameFor("CompaniesListDescription")); //$NON-NLS-1$
    }

    @Test
    public void extendedTooltipNamePattern() {
        assertEquals("RetestLabelExtendedTooltip", //$NON-NLS-1$
                FormDefaultsRules.extendedTooltipNameFor("RetestLabel")); //$NON-NLS-1$
    }

    @Test
    public void additionKindNamePatterns() {
        // Per the Configurator-fixed Form.form: each Table carries three
        // Addition helpers whose names are <tableName><kind>.
        assertEquals("LinesTableSearchString", //$NON-NLS-1$
                FormDefaultsRules.AdditionKind.SEARCH_STRING.nameFor("LinesTable")); //$NON-NLS-1$
        assertEquals("LinesTableViewStatus", //$NON-NLS-1$
                FormDefaultsRules.AdditionKind.VIEW_STATUS.nameFor("LinesTable")); //$NON-NLS-1$
        assertEquals("LinesTableSearchControl", //$NON-NLS-1$
                FormDefaultsRules.AdditionKind.SEARCH_CONTROL.nameFor("LinesTable")); //$NON-NLS-1$
    }

    // --- UsualGroup layout enum parsers (category #7) -----------------------

    @Test
    public void formChildrenGroupAcceptsCanonicalAndSnakeAndKebabAndCase() {
        // Category #7: add_group must accept layout properties so the
        // resulting UsualGroupExtInfo doesn't serialize as self-closing.
        assertEquals("AlwaysHorizontal", FormDefaultsRules.parseFormChildrenGroupLiteral("AlwaysHorizontal")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("AlwaysHorizontal", FormDefaultsRules.parseFormChildrenGroupLiteral("always_horizontal")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("AlwaysHorizontal", FormDefaultsRules.parseFormChildrenGroupLiteral("always-horizontal")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("AlwaysHorizontal", FormDefaultsRules.parseFormChildrenGroupLiteral("ALWAYSHORIZONTAL")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Vertical", FormDefaultsRules.parseFormChildrenGroupLiteral("vertical")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Horizontal", FormDefaultsRules.parseFormChildrenGroupLiteral("Horizontal")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Auto", FormDefaultsRules.parseFormChildrenGroupLiteral(" auto ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("HorizontalIfPossible", //$NON-NLS-1$
                FormDefaultsRules.parseFormChildrenGroupLiteral("horizontal_if_possible")); //$NON-NLS-1$
        assertEquals("AutoScreenTypeSensitive", //$NON-NLS-1$
                FormDefaultsRules.parseFormChildrenGroupLiteral("AutoScreenTypeSensitive")); //$NON-NLS-1$
    }

    @Test
    public void formChildrenGroupReturnsNullForUnknownOrBlank() {
        assertNull(FormDefaultsRules.parseFormChildrenGroupLiteral(null));
        assertNull(FormDefaultsRules.parseFormChildrenGroupLiteral("")); //$NON-NLS-1$
        assertNull(FormDefaultsRules.parseFormChildrenGroupLiteral("   ")); //$NON-NLS-1$
        assertNull(FormDefaultsRules.parseFormChildrenGroupLiteral("not-a-real-value")); //$NON-NLS-1$
    }

    @Test
    public void usualGroupBehaviorLiterals() {
        // EDT 2025.1.x enum literals: Usual, Collapsible, PopUp, Auto.
        assertEquals("Usual", FormDefaultsRules.parseUsualGroupBehaviorLiteral("usual")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Usual", FormDefaultsRules.parseUsualGroupBehaviorLiteral("normal")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Collapsible", FormDefaultsRules.parseUsualGroupBehaviorLiteral("collapsible")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("PopUp", FormDefaultsRules.parseUsualGroupBehaviorLiteral("Popup")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("PopUp", FormDefaultsRules.parseUsualGroupBehaviorLiteral("pop_up")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Auto", FormDefaultsRules.parseUsualGroupBehaviorLiteral("auto")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(FormDefaultsRules.parseUsualGroupBehaviorLiteral("widget")); //$NON-NLS-1$
    }

    @Test
    public void usualGroupRepresentationLiterals() {
        // EDT 2025.1.x enum literals: None, StrongSeparation, WeakSeparation,
        // NormalSeparation, Auto.
        assertEquals("None", FormDefaultsRules.parseUsualGroupRepresentationLiteral("none")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("WeakSeparation", //$NON-NLS-1$
                FormDefaultsRules.parseUsualGroupRepresentationLiteral("weak_separation")); //$NON-NLS-1$
        assertEquals("StrongSeparation", //$NON-NLS-1$
                FormDefaultsRules.parseUsualGroupRepresentationLiteral("STRONG-SEPARATION")); //$NON-NLS-1$
        assertEquals("Auto", FormDefaultsRules.parseUsualGroupRepresentationLiteral("auto")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(FormDefaultsRules.parseUsualGroupRepresentationLiteral("LinesAtSides")); //$NON-NLS-1$
        assertNull(FormDefaultsRules.parseUsualGroupRepresentationLiteral("widget")); //$NON-NLS-1$
    }

    @Test
    public void throughAlignAndCurrentRowUseLiterals() {
        assertEquals("Auto", FormDefaultsRules.parseUsualGroupThroughAlignLiteral("auto")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Use", FormDefaultsRules.parseUsualGroupThroughAlignLiteral("USE")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("DontUse", FormDefaultsRules.parseUsualGroupThroughAlignLiteral("dont_use")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("DontUse", FormDefaultsRules.parseUsualGroupThroughAlignLiteral("DoNotUse")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Auto", FormDefaultsRules.parseCurrentRowUseLiteral("auto")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Use", FormDefaultsRules.parseCurrentRowUseLiteral("use")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("DontUse", FormDefaultsRules.parseCurrentRowUseLiteral("dont-use")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
