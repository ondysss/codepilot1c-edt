package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link FormGroupTypeIntent}.
 *
 * <p>Covers the BF-8908 Gap 3 hardening: distinguish requests that should
 * proceed via the existing {@code add_group} dispatcher (PAGES, PAGE,
 * COLUMN_GROUP, ...) from requests that ask for {@code Table} — which is
 * a distinct EMF model class and must be rejected up-front to prevent
 * silent downgrade to UsualGroup.</p>
 */
public class FormGroupTypeIntentTest {

    // --- classify -----------------------------------------------------------

    @Test
    public void classifyTable_recognizesAllSeparatorVariants() {
        assertEquals(FormGroupTypeIntent.Verdict.TABLE_NOT_A_GROUP,
                FormGroupTypeIntent.classify("TABLE")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.TABLE_NOT_A_GROUP,
                FormGroupTypeIntent.classify("Table")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.TABLE_NOT_A_GROUP,
                FormGroupTypeIntent.classify("table")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.TABLE_NOT_A_GROUP,
                FormGroupTypeIntent.classify("FormTable")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.TABLE_NOT_A_GROUP,
                FormGroupTypeIntent.classify("data-table")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.TABLE_NOT_A_GROUP,
                FormGroupTypeIntent.classify(" TABLE ")); //$NON-NLS-1$
    }

    @Test
    public void classify_recognizesPagesAndPageAsValidGroupTypes() {
        // PAGES and PAGE *are* genuine ManagedFormGroupType enum values
        // (FormGroup variants); they should NOT be rejected like Table.
        assertEquals(FormGroupTypeIntent.Verdict.RECOGNIZED_GROUP_TYPE,
                FormGroupTypeIntent.classify("PAGES")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.RECOGNIZED_GROUP_TYPE,
                FormGroupTypeIntent.classify("Pages")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.RECOGNIZED_GROUP_TYPE,
                FormGroupTypeIntent.classify("PAGE")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.RECOGNIZED_GROUP_TYPE,
                FormGroupTypeIntent.classify("page")); //$NON-NLS-1$
    }

    @Test
    public void classify_recognizesOtherFormGroupTypes() {
        assertEquals(FormGroupTypeIntent.Verdict.RECOGNIZED_GROUP_TYPE,
                FormGroupTypeIntent.classify("USUAL_GROUP")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.RECOGNIZED_GROUP_TYPE,
                FormGroupTypeIntent.classify("UsualGroup")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.RECOGNIZED_GROUP_TYPE,
                FormGroupTypeIntent.classify("COLUMN_GROUP")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.RECOGNIZED_GROUP_TYPE,
                FormGroupTypeIntent.classify("BUTTON_GROUP")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.RECOGNIZED_GROUP_TYPE,
                FormGroupTypeIntent.classify("COMMAND_BAR")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.RECOGNIZED_GROUP_TYPE,
                FormGroupTypeIntent.classify("AUTO_COMMAND_BAR")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.RECOGNIZED_GROUP_TYPE,
                FormGroupTypeIntent.classify("POPUP")); //$NON-NLS-1$
    }

    @Test
    public void classify_unspecifiedForNullOrBlank() {
        assertEquals(FormGroupTypeIntent.Verdict.UNSPECIFIED,
                FormGroupTypeIntent.classify(null));
        assertEquals(FormGroupTypeIntent.Verdict.UNSPECIFIED,
                FormGroupTypeIntent.classify("")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.UNSPECIFIED,
                FormGroupTypeIntent.classify("   ")); //$NON-NLS-1$
    }

    @Test
    public void classify_unrecognizedForRandomString() {
        assertEquals(FormGroupTypeIntent.Verdict.UNRECOGNIZED,
                FormGroupTypeIntent.classify("WIDGET")); //$NON-NLS-1$
        assertEquals(FormGroupTypeIntent.Verdict.UNRECOGNIZED,
                FormGroupTypeIntent.classify("SOMETHING_ELSE")); //$NON-NLS-1$
    }

    // --- extractRawType -----------------------------------------------------

    @Test
    public void extractRawType_priority_groupTypeBeatsTopLevelTypeBeatsSetType() {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("group_type", "PAGES"); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("type", "PAGE"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> set = Map.of("type", "USUAL_GROUP"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("PAGES", FormGroupTypeIntent.extractRawType(operation, set)); //$NON-NLS-1$
    }

    @Test
    public void extractRawType_topLevelTypeUsedWhenGroupTypeMissing() {
        // BF-8908 repro: agent sent {"op":"add_group","type":"PAGES"} with no
        // group_type and no nested set.  Until this fix the resolver only
        // checked group_type and set.type, so PAGES silently fell back to
        // USUAL_GROUP.  Verify extractRawType now picks it up.
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "PAGES"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("PAGES", FormGroupTypeIntent.extractRawType(operation, Map.of())); //$NON-NLS-1$
    }

    @Test
    public void extractRawType_setTypeUsedAsFinalFallback() {
        Map<String, Object> set = Map.of("type", "PAGE"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("PAGE", FormGroupTypeIntent.extractRawType(Map.of(), set)); //$NON-NLS-1$
    }

    @Test
    public void extractRawType_nullWhenAllPositionsEmpty() {
        assertNull(FormGroupTypeIntent.extractRawType(Map.of(), Map.of()));
        assertNull(FormGroupTypeIntent.extractRawType(null, null));
    }

    @Test
    public void extractRawType_caseInsensitiveKeys() {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("Group_Type", "PAGES"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("PAGES", FormGroupTypeIntent.extractRawType(operation, Map.of())); //$NON-NLS-1$
    }

    @Test
    public void extractRawType_blankValuesIgnored() {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("group_type", "  "); //$NON-NLS-1$ //$NON-NLS-2$
        operation.put("type", "PAGES"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("PAGES", FormGroupTypeIntent.extractRawType(operation, Map.of())); //$NON-NLS-1$
    }

    // --- tableNotAGroupMessage ----------------------------------------------

    @Test
    public void tableMessage_echoesValueAndPointsToFormXmlEdit() {
        String msg = FormGroupTypeIntent.tableNotAGroupMessage("TABLE", "PricingTable"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must echo raw type:\n" + msg, msg.contains("'TABLE'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must echo group name:\n" + msg, msg.contains("PricingTable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must mention xsi:type=\"form:Table\":\n" + msg, //$NON-NLS-1$
                msg.contains("xsi:type=\"form:Table\"")); //$NON-NLS-1$
        assertTrue("must mention silent downgrade to UsualGroup:\n" + msg, //$NON-NLS-1$
                msg.contains("UsualGroup")); //$NON-NLS-1$
        assertTrue("must mention add_table op as the future path:\n" + msg, //$NON-NLS-1$
                msg.contains("add_table")); //$NON-NLS-1$
        assertTrue("must mention .form XML / Edit/Write workaround:\n" + msg, //$NON-NLS-1$
                msg.contains(".form") && msg.contains("Edit/Write")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must mention inspect_form_layout for verification:\n" + msg, //$NON-NLS-1$
                msg.contains("inspect_form_layout")); //$NON-NLS-1$
    }

    @Test
    public void tableMessage_omitsGroupNameWhenBlank() {
        String msg = FormGroupTypeIntent.tableNotAGroupMessage("TABLE", null); //$NON-NLS-1$
        assertFalse("must not include name='' marker when name is null:\n" + msg, //$NON-NLS-1$
                msg.contains("name=''")); //$NON-NLS-1$
        msg = FormGroupTypeIntent.tableNotAGroupMessage("TABLE", " "); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must not include name='' marker when name is blank:\n" + msg, //$NON-NLS-1$
                msg.contains("name=''")); //$NON-NLS-1$
    }
}
