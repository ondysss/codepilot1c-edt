package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link FormFieldTypeValidator}.
 *
 * <p>Pins the deny-list of {@code field_type} values that the 1C platform
 * rejects inside a Table parent (SU107).  This is a pre-flight check so
 * the agent gets an actionable error before the BM transaction fires.</p>
 */
public class FormFieldTypeValidatorTest {

    // --- isIncompatibleWithTableParent --------------------------------------

    @Test
    public void acceptsCheckBoxFieldInAllSeparatorVariants() {
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("CHECK_BOX_FIELD")); //$NON-NLS-1$
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("CheckBoxField")); //$NON-NLS-1$
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("checkboxfield")); //$NON-NLS-1$
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("check-box-field")); //$NON-NLS-1$
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("CHECK BOX FIELD")); //$NON-NLS-1$
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent(" check_box_field ")); //$NON-NLS-1$
    }

    @Test
    public void rejectsOtherTableIncompatibleTypes() {
        assertTrue(FormFieldTypeValidator.isIncompatibleWithTableParent("RADIO_BUTTON_FIELD")); //$NON-NLS-1$
        assertTrue(FormFieldTypeValidator.isIncompatibleWithTableParent("RadioButtonField")); //$NON-NLS-1$
        assertTrue(FormFieldTypeValidator.isIncompatibleWithTableParent("PROGRESS_BAR_FIELD")); //$NON-NLS-1$
        assertTrue(FormFieldTypeValidator.isIncompatibleWithTableParent("TRACK_BAR_FIELD")); //$NON-NLS-1$
    }

    @Test
    public void acceptsTypesValidInsideTables() {
        // The platform accepts these inside a Table — agent must not be tripped up.
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("INPUT_FIELD")); //$NON-NLS-1$
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("LABEL_FIELD")); //$NON-NLS-1$
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("PICTURE_FIELD")); //$NON-NLS-1$
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("BUTTON_FIELD")); //$NON-NLS-1$
        // Lowercase variants of valid types should also pass.
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("input_field")); //$NON-NLS-1$
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("InputField")); //$NON-NLS-1$
    }

    @Test
    public void handlesNullAndEmpty() {
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent(null));
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("")); //$NON-NLS-1$
        assertFalse(FormFieldTypeValidator.isIncompatibleWithTableParent("   ")); //$NON-NLS-1$
    }

    // --- tableIncompatibleFieldTypeMessage ----------------------------------

    @Test
    public void messageEchoesRawFieldTypeAndPointsToSupportedControls() {
        String msg = FormFieldTypeValidator.tableIncompatibleFieldTypeMessage("RADIO_BUTTON_FIELD", "PricingTablePayment"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must echo raw field_type:\n" + msg, msg.contains("RADIO_BUTTON_FIELD")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must mention SU107:\n" + msg, msg.contains("SU107")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must point to INPUT_FIELD:\n" + msg, msg.contains("INPUT_FIELD")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must mention Table parent:\n" + msg, msg.contains("Table")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must echo field name when present:\n" + msg, //$NON-NLS-1$
                msg.contains("PricingTablePayment")); //$NON-NLS-1$
    }

    @Test
    public void messageOmitsFieldNameWhenBlank() {
        String msg = FormFieldTypeValidator.tableIncompatibleFieldTypeMessage("RADIO_BUTTON_FIELD", null); //$NON-NLS-1$
        assertFalse("must not include 'field name=' when field name is null:\n" + msg, //$NON-NLS-1$
                msg.contains("field name=")); //$NON-NLS-1$
        msg = FormFieldTypeValidator.tableIncompatibleFieldTypeMessage("RADIO_BUTTON_FIELD", "  "); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must not include 'field name=' when field name is blank:\n" + msg, //$NON-NLS-1$
                msg.contains("field name=")); //$NON-NLS-1$
    }

    @Test
    public void messageRoundTripIsStableForSnapshotsOfCommonCase() {
        // Pin the exact wording so a refactor cannot drift the agent-facing
        // language without an explicit test update.
        String msg = FormFieldTypeValidator.tableIncompatibleFieldTypeMessage("RADIO_BUTTON_FIELD", "Active"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("field_type 'RADIO_BUTTON_FIELD' is not allowed inside a Table parent (field name='Active'):" //$NON-NLS-1$
                + " the 1C platform rejects it with SU107 'Illegal extension type for field type'." //$NON-NLS-1$
                + " Use INPUT_FIELD, or CHECK_BOX_FIELD for a Boolean checkbox column.", //$NON-NLS-1$
                msg);
    }
}
