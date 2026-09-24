package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.codepilot1c.core.edt.forms.FormUsage;

/**
 * Роль формы записи регистра сведений.
 *
 * <p>Дефект: {@code add_metadata_child} с {@code form_usage:OBJECT} на {@code InformationRegister.X} ответил
 * {@code Form role=LIST}, записал {@code defaultListForm} и сгенерировал форму списка. У регистра сведений нет формы
 * объекта: роль в модели EDT — {@code defaultRecordForm}, тип генератора форм — {@code FormType.RECORD}, а роли
 * записи у инструмента не было вовсе. Воспроизведено на EDT 2025.2.3.</p>
 *
 * <p>Тест бьёт рефлексией по настоящим {@code resolveEffectiveFormUsage}, {@code resolveEffectiveFormName},
 * {@code resolveFormGeneratorType} и {@code bindDefaultForm} (владельцы и формы из фабрики EMF). Роль RECORD берётся
 * строкой, а не константой: против сборки до фикса тест обязан падать на симптоме, а не {@code NoSuchFieldError}.
 * Полный прогон с контролем саботажа — {@code tools/run-register-record-form-eval.sh}.</p>
 */
public class EdtMetadataServiceRecordFormTest {

    private static final MdClassFactory MD = MdClassFactory.eINSTANCE;
    private static final String REGISTER = "InformationRegister.Настройки"; //$NON-NLS-1$

    // --- сам симптом ------------------------------------------------------------

    @Test
    public void informationRegisterFormNamedRecordWithoutUsageIsRecordForm() throws Exception {
        // Сравнение по имени роли: сборка до фикса обязана показать сам симптом (было LIST).
        assertEquals("RECORD", effectiveUsage(REGISTER, "ФормаЗаписи", null).name()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void informationRegisterObjectUsageResolvesToRecord() throws Exception {
        assertEquals("RECORD", effectiveUsage(REGISTER, "ФормаЗаписи", FormUsage.OBJECT).name()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void recordUsageIsParsedFromItsNames() throws Exception {
        for (String name : new String[] {"RECORD", "record", "ФормаЗаписи", "запись"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertEquals(name, "RECORD", FormUsage.fromString(name).name()); //$NON-NLS-1$
        }
    }

    @Test
    public void recordUsageBindsDefaultRecordForm() throws Exception {
        InformationRegister owner = MD.createInformationRegister();
        BasicForm form = MD.createInformationRegisterForm();
        bind(owner, form, record());
        assertSame(form, owner.getDefaultRecordForm());
        assertNull(owner.getDefaultListForm());
    }

    @Test
    public void recordUsageGeneratesRecordForm() throws Exception {
        // Содержимое формы строит генератор EDT: для записи регистра нужен его тип RECORD, не OBJECT и не LIST.
        Class<?> formType = Class.forName("com._1c.g5.v8.dt.form.generator.FormType"); //$NON-NLS-1$
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "resolveFormGeneratorType", MdObject.class, FormUsage.class, Class.class); //$NON-NLS-1$
        method.setAccessible(true);
        Object generatorType = invoke(method, MD.createInformationRegister(), record(), formType);
        assertEquals("RECORD", ((Enum<?>) generatorType).name()); //$NON-NLS-1$
    }

    @Test
    public void recordFormGetsRecordFormNameByDefault() throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "resolveEffectiveFormName", String.class, String.class, FormUsage.class); //$NON-NLS-1$
        method.setAccessible(true);
        assertEquals("ФормаЗаписи", invoke(method, REGISTER, "", record())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- роль, которой у владельца нет ---------------------------------------------

    @Test
    public void recordUsageOfCatalogIsRefusedWithExplanation() throws Exception {
        assertUsageRefused("Catalog.Контрагенты", record(), "только у регистра сведений"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void objectUsageOfAccumulationRegisterIsRefusedWithExplanation() throws Exception {
        assertUsageRefused("AccumulationRegister.Остатки", FormUsage.OBJECT, "только формы списка"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void objectUsageOfEnumIsRefusedWithChoiceHint() throws Exception {
        assertUsageRefused("Enum.Статусы", FormUsage.OBJECT, "CHOICE"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void objectRoleGuessedFromNameOfAccumulationRegisterFallsBackToList() throws Exception {
        // Роль, угаданная по имени, не отказывает, а уступает обычной роли владельца.
        assertEquals(FormUsage.LIST, effectiveUsage("AccumulationRegister.Остатки", "ФормаОбъекта", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- что работало и обязано работать дальше ------------------------------------

    @Test
    public void recordRoleGuessedFromNameOfCatalogFallsBackToObject() throws Exception {
        assertEquals(FormUsage.OBJECT, effectiveUsage("Catalog.Журнал", "ФормаЗаписиЖурнала", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void informationRegisterListUsageStaysList() throws Exception {
        assertEquals(FormUsage.LIST, effectiveUsage(REGISTER, "ФормаСписка", null)); //$NON-NLS-1$
        assertEquals(FormUsage.LIST, effectiveUsage(REGISTER, "Форма", FormUsage.LIST)); //$NON-NLS-1$
    }

    @Test
    public void informationRegisterListFormStillBinds() throws Exception {
        InformationRegister owner = MD.createInformationRegister();
        BasicForm form = MD.createInformationRegisterForm();
        bind(owner, form, FormUsage.LIST);
        assertSame(form, owner.getDefaultListForm());
        assertNull(owner.getDefaultRecordForm());
    }

    @Test
    public void accumulationRegisterListFormStillBinds() throws Exception {
        AccumulationRegister owner = MD.createAccumulationRegister();
        BasicForm form = MD.createAccumulationRegisterForm();
        assertEquals(FormUsage.LIST, effectiveUsage("AccumulationRegister.Остатки", "ФормаСписка", null)); //$NON-NLS-1$ //$NON-NLS-2$
        bind(owner, form, FormUsage.LIST);
        assertSame(form, owner.getDefaultListForm());
    }

    @Test
    public void catalogObjectFormStillBindsDefaultObjectForm() throws Exception {
        Catalog owner = MD.createCatalog();
        BasicForm form = MD.createCatalogForm();
        assertEquals(FormUsage.OBJECT, effectiveUsage("Catalog.Контрагенты", "ФормаЭлемента", FormUsage.OBJECT)); //$NON-NLS-1$ //$NON-NLS-2$
        bind(owner, form, FormUsage.OBJECT);
        assertSame(form, owner.getDefaultObjectForm());
    }

    // --- обвязка -------------------------------------------------------------------

    private static FormUsage record() {
        return FormUsage.fromString("RECORD"); //$NON-NLS-1$
    }

    private static void bind(MdObject owner, BasicForm form, FormUsage usage) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "bindDefaultForm", MdObject.class, MdObject.class, FormUsage.class, String.class); //$NON-NLS-1$
        method.setAccessible(true);
        invoke(method, owner, form, usage, "eval"); //$NON-NLS-1$
    }

    private static FormUsage effectiveUsage(String ownerFqn, String name, FormUsage requested) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "resolveEffectiveFormUsage", String.class, String.class, FormUsage.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (FormUsage) invoke(method, ownerFqn, name, requested);
    }

    private static void assertUsageRefused(String ownerFqn, FormUsage usage, String explanation) throws Exception {
        try {
            FormUsage resolved = effectiveUsage(ownerFqn, "Форма", usage); //$NON-NLS-1$
            fail("ожидался отказ INVALID_FORM_USAGE для " + ownerFqn + " / " + usage + ", получено " + resolved); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_FORM_USAGE, e.getCode());
            assertTrue("в отказе нет «" + explanation + "»: " + e.getMessage(), //$NON-NLS-1$ //$NON-NLS-2$
                    e.getMessage().contains(explanation));
        }
    }

    private static Object invoke(Method method, Object... args) throws Exception {
        try {
            return method.invoke(new EdtMetadataService(), args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
