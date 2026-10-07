package com.codepilot1c.core.edt.rights;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.dbview.DbViewFactory;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldTableDef;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.Rls;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class RlsRulesTest {
    private ObjectRight right;
    private DbViewFieldDef ref;
    private DbViewFieldDef participant;
    private DbViewFieldDef answer;
    private Map<String, DbViewFieldDef> fields;
    private Rls original;

    @Before
    public void setUp() {
        Right read = RightsFactory.eINSTANCE.createRight();
        read.setName("Read");
        right = RightsFactory.eINSTANCE.createObjectRight();
        right.setRight(read);
        right.setValue(RightValue.SET);
        ref = field("Ref", "Ссылка");
        participant = field("Participant", "Участник");
        answer = field("Answer", "Ответ");
        DbViewFieldTableDef tasks = DbViewFactory.eINSTANCE.createDbViewFieldTableDef();
        tasks.setName("Tasks");
        tasks.setNameRu("Задания");
        tasks.getFields().add(answer);
        fields = RlsRules.fieldIndex(List.of(ref, participant, tasks));
        original = RightsFactory.eINSTANCE.createRls();
        original.setCondition("WHERE FALSE");
        right.getRestrictionsByCondition().add(original);
    }

    @Test
    public void replacesExistingRulesWithoutChangingTheGrant() {
        String condition = "WHERE Participant = &CurrentUser\nAND NOT DeletionMark";
        RlsRules.replace(right, List.of(rule(List.of(), "WHERE FALSE"),
                rule(List.of("Ref", "Participant"), condition)), fields);
        assertEquals(2, right.getRestrictionsByCondition().size());
        assertEquals(condition, right.getRestrictionsByCondition().get(1).getCondition());
        assertEquals(List.of(ref, participant), right.getRestrictionsByCondition().get(1).getFields());
        assertTrue(right.getRestrictionsByCondition().get(0).getFields().isEmpty());
        assertEquals(RightValue.SET, right.getValue());
        assertFalse(right.getRestrictionsByCondition().contains(original));
    }

    @Test
    public void resolvesRussianAndCaseInsensitiveFieldNames() {
        RlsRules.replace(right, List.of(rule(List.of(), "ГДЕ ЛОЖЬ"),
                rule(List.of("ССЫЛКА", "Задания.Ответ"), "ГДЕ ИСТИНА")), fields);
        assertEquals(List.of(ref, answer), right.getRestrictionsByCondition().get(1).getFields());
        assertEquals("Tasks.Answer", RlsRules.fieldName(answer, false));
        assertEquals("Задания.Ответ", RlsRules.fieldName(answer, true));
    }

    @Test
    public void unknownFieldDoesNotClearAnyExistingRule() {
        rejects(List.of(rule(List.of(), "WHERE TRUE"), rule(List.of("NoSuchField"), "WHERE TRUE")));
    }

    @Test
    public void duplicateAliasesCannotAssignTwoConditionsToOneField() {
        rejects(List.of(rule(List.of(), "WHERE FALSE"), rule(List.of("Ref", "Ссылка"), "WHERE TRUE")));
    }

    @Test
    public void duplicateFieldAcrossRulesIsRejected() {
        rejects(List.of(rule(List.of(), "WHERE FALSE"), rule(List.of("Ref"), "WHERE TRUE"),
                rule(List.of("ref"), "WHERE FALSE")));
    }

    @Test
    public void duplicateOtherFieldsRuleIsRejected() {
        rejects(List.of(rule(List.of(), "WHERE FALSE"), rule(List.of(), "WHERE TRUE")));
    }

    @Test
    public void newlyAddedFieldsMustHaveAnExplicitDefaultCondition() {
        rejects(List.of(rule(List.of("Ref"), "WHERE TRUE")));
    }

    @Test
    public void emptyReplacementCannotSilentlyRemoveProtection() { rejects(List.of()); }

    @Test
    public void blankConditionIsRejected() { rejects(List.of(rule(List.of(), "  \n"))); }

    @Test
    public void omittedFieldListIsRejected() { rejects(List.of(Map.of("condition", "WHERE FALSE"))); }

    @Test
    public void misspelledPropertyIsRejected() {
        rejects(List.of(Map.of("fields", List.of(), "condition", "WHERE FALSE", "other_field", true)));
    }

    @Test
    public void stringInsteadOfFieldArrayIsRejected() {
        rejects(List.of(Map.of("fields", "Ref", "condition", "WHERE FALSE")));
    }

    @Test
    public void nonStringFieldIsRejected() {
        rejects(List.of(rule(List.of(), "WHERE FALSE"), rule(List.of(1), "WHERE TRUE")));
    }

    @Test
    public void ungrantedRightCannotBeGrantedByReplacingRestrictions() {
        right.setValue(RightValue.UNSET);
        rejects(List.of(rule(List.of(), "WHERE TRUE")));
        assertEquals(RightValue.UNSET, right.getValue());
    }

    @Test
    public void absentRightCannotBeCreatedByReplacingRestrictions() {
        assertThrows(MetadataOperationException.class, () ->
                RlsRules.replace(null, List.of(rule(List.of(), "WHERE TRUE")), fields));
    }

    @Test
    public void interactiveViewRightDoesNotSupportRls() {
        right.getRight().setName("View");
        rejects(List.of(rule(List.of(), "WHERE TRUE")));
    }

    @Test
    public void updateAcceptsOneRecordCondition() {
        right.getRight().setName("Update");
        RlsRules.replace(right, List.of(rule(List.of(), "WHERE Participant = &CurrentUser")), fields);
        assertEquals(1, right.getRestrictionsByCondition().size());
    }

    @Test
    public void updateRejectsFieldRestrictions() {
        right.getRight().setName("Update");
        rejects(List.of(rule(List.of(), "WHERE FALSE"), rule(List.of("Ref"), "WHERE TRUE")));
    }

    @Test
    public void fieldIndexDoesNotOfferTabularSectionAsAnEntireField() {
        assertFalse(fields.containsKey("tasks"));
        assertSame(answer, fields.get("tasks.answer"));
    }

    private void rejects(Object rules) {
        assertThrows(MetadataOperationException.class, () -> RlsRules.replace(right, rules, fields));
        assertEquals(List.of(original), right.getRestrictionsByCondition());
        assertEquals("WHERE FALSE", original.getCondition());
    }

    private static Map<String, Object> rule(List<?> names, String condition) {
        return Map.of("fields", names, "condition", condition);
    }

    private static DbViewFieldDef field(String name, String nameRu) {
        DbViewFieldDef field = DbViewFactory.eINSTANCE.createDbViewFieldFieldDef();
        field.setName(name);
        field.setNameRu(nameRu);
        return field;
    }
}
