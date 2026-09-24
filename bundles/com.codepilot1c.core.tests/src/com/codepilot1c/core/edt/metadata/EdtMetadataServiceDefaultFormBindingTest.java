package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com.codepilot1c.core.edt.forms.FormUsage;

/**
 * Привязка основной формы владельца при {@code create_form} / {@code apply_form_recipe}.
 *
 * <p>Дефект: {@code create_form} без {@code usage} или с {@code usage:OBJECT} на обработке отвечал
 * {@code [INVALID_FORM_USAGE] Form usage OBJECT is not supported for owner DataProcessor}. Роль OBJECT
 * разрешалась в один сеттер {@code setDefaultObjectForm}, а основная форма объекта в модели EDT живёт в трёх
 * разных фичах: {@code defaultObjectForm} (справочник, документ…), {@code defaultRecordForm} (регистр сведений)
 * и {@code defaultForm} (обработка, отчёт, внешние обработка и отчёт). Внешним объектам привязку выключили
 * целиком — и форма, созданная инструментом, оставалась без роли основной. Воспроизведено на EDT 2025.2.3.</p>
 *
 * <p>Тест бьёт рефлексией по настоящим {@code bindDefaultForm}, {@code resolveDefaultBinding} и
 * {@code resolveEffectiveFormUsage} с владельцами и формами из фабрики EMF и не ссылается на классы фикса — один
 * бинарник идёт против сборки до фикса (обязан покраснеть поимённо) и после. Полный прогон с контролем саботажа —
 * {@code tools/run-form-default-binding-eval.sh}.</p>
 */
public class EdtMetadataServiceDefaultFormBindingTest {

    private static final MdClassFactory MD = MdClassFactory.eINSTANCE;

    // --- основная форма объекта: сам симптом ---------------------------------

    @Test
    public void dataProcessorObjectFormBecomesDefaultForm() throws Exception {
        DataProcessor owner = MD.createDataProcessor();
        BasicForm form = MD.createDataProcessorForm();
        bind(owner, form, FormUsage.OBJECT);
        assertSame(form, owner.getDefaultForm());
    }

    @Test
    public void dataProcessorFormWithoutUsageBecomesDefaultForm() throws Exception {
        // Путь create_form без usage: роль выводится из вида владельца, привязка включена по умолчанию.
        FormUsage usage = effectiveUsage("DataProcessor.Загрузка", "Форма", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(FormUsage.OBJECT, usage);
        assertTrue(defaultBinding(null, usage, "DataProcessor.Загрузка", false)); //$NON-NLS-1$
        DataProcessor owner = MD.createDataProcessor();
        BasicForm form = MD.createDataProcessorForm();
        bind(owner, form, usage);
        assertSame(form, owner.getDefaultForm());
    }

    @Test
    public void reportObjectFormBecomesDefaultForm() throws Exception {
        Report owner = MD.createReport();
        BasicForm form = MD.createReportForm();
        bind(owner, form, FormUsage.OBJECT);
        assertSame(form, owner.getDefaultForm());
        assertNull("форма настроек не задета", owner.getDefaultSettingsForm()); //$NON-NLS-1$
        assertNull("форма варианта не задета", owner.getDefaultVariantForm()); //$NON-NLS-1$
    }

    @Test
    public void externalDataProcessorObjectFormBecomesDefaultForm() throws Exception {
        ExternalDataProcessor owner = MD.createExternalDataProcessor();
        BasicForm form = MD.createDataProcessorForm();
        bind(owner, form, FormUsage.OBJECT);
        assertSame(form, owner.getDefaultForm());
    }

    @Test
    public void externalReportObjectFormBecomesDefaultForm() throws Exception {
        ExternalReport owner = MD.createExternalReport();
        BasicForm form = MD.createReportForm();
        bind(owner, form, FormUsage.OBJECT);
        assertSame(form, owner.getDefaultForm());
    }

    @Test
    public void informationRegisterObjectFormBecomesRecordForm() throws Exception {
        InformationRegister owner = MD.createInformationRegister();
        BasicForm form = MD.createInformationRegisterForm();
        bind(owner, form, FormUsage.OBJECT);
        assertSame(form, owner.getDefaultRecordForm());
        assertNull(owner.getDefaultListForm());
    }

    @Test
    public void objectRoleIsBoundInExternalProject() throws Exception {
        assertTrue(defaultBinding(null, FormUsage.OBJECT, "ExternalDataProcessor.Загрузка", true)); //$NON-NLS-1$
    }

    @Test
    public void objectRoleIsBoundForExternalReportOwner() throws Exception {
        assertTrue(defaultBinding(null, FormUsage.OBJECT, "ExternalReport.Сводка", false)); //$NON-NLS-1$
    }

    // --- что работало и обязано работать дальше ------------------------------

    @Test
    public void catalogObjectFormStillBindsDefaultObjectForm() throws Exception {
        Catalog owner = MD.createCatalog();
        BasicForm form = MD.createCatalogForm();
        bind(owner, form, FormUsage.OBJECT);
        assertSame(form, owner.getDefaultObjectForm());
        assertNull(owner.getDefaultListForm());
        assertNull(owner.getDefaultChoiceForm());
    }

    @Test
    public void documentListAndChoiceFormsStillBind() throws Exception {
        Document owner = MD.createDocument();
        BasicForm list = MD.createDocumentForm();
        BasicForm choice = MD.createDocumentForm();
        bind(owner, list, FormUsage.LIST);
        bind(owner, choice, FormUsage.CHOICE);
        assertSame(list, owner.getDefaultListForm());
        assertSame(choice, owner.getDefaultChoiceForm());
        assertNull(owner.getDefaultObjectForm());
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
    public void auxiliaryFormBindsNothing() throws Exception {
        DataProcessor owner = MD.createDataProcessor();
        bind(owner, MD.createDataProcessorForm(), FormUsage.AUXILIARY);
        assertNull(owner.getDefaultForm());
        assertFalse(defaultBinding(null, FormUsage.AUXILIARY, "DataProcessor.Загрузка", false)); //$NON-NLS-1$
    }

    @Test
    public void setAsDefaultFalseBindsNothing() throws Exception {
        assertFalse(defaultBinding(Boolean.FALSE, FormUsage.OBJECT, "DataProcessor.Загрузка", false)); //$NON-NLS-1$
    }

    @Test
    public void enumObjectRoleIsStillRefused() throws Exception {
        // У перечисления нет формы объекта — отказ, а не молчаливая привязка к чему-то другому.
        assertRefused(MD.createEnum(), MD.createEnumForm(), FormUsage.OBJECT);
    }

    @Test
    public void dataProcessorListRoleIsStillRefused() throws Exception {
        assertRefused(MD.createDataProcessor(), MD.createDataProcessorForm(), FormUsage.LIST);
    }

    @Test
    public void listRoleOfExternalOwnerBindsNothing() throws Exception {
        // У внешних объектов одна роль — основная форма. Имя вроде «ФормаСписка» даёт роль LIST, и она, как и
        // раньше, ничего не привязывает, а не валит создание формы.
        assertFalse(defaultBinding(null, FormUsage.LIST, "ExternalDataProcessor.Загрузка", true)); //$NON-NLS-1$
        assertFalse(defaultBinding(null, FormUsage.CHOICE, "ExternalReport.Сводка", false)); //$NON-NLS-1$
    }

    // --- обвязка -------------------------------------------------------------

    private static void bind(MdObject owner, BasicForm form, FormUsage usage) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "bindDefaultForm", MdObject.class, MdObject.class, FormUsage.class, String.class); //$NON-NLS-1$
        method.setAccessible(true);
        invoke(method, owner, form, usage, "eval"); //$NON-NLS-1$
    }

    private static boolean defaultBinding(Boolean setAsDefault, FormUsage usage, String ownerFqn, boolean externalProject)
            throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "resolveDefaultBinding", Boolean.class, FormUsage.class, String.class, boolean.class); //$NON-NLS-1$
        method.setAccessible(true);
        return ((Boolean) invoke(method, setAsDefault, usage, ownerFqn, Boolean.valueOf(externalProject))).booleanValue();
    }

    private static FormUsage effectiveUsage(String ownerFqn, String name, FormUsage requested) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "resolveEffectiveFormUsage", String.class, String.class, FormUsage.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (FormUsage) invoke(method, ownerFqn, name, requested);
    }

    private static void assertRefused(MdObject owner, BasicForm form, FormUsage usage) throws Exception {
        try {
            bind(owner, form, usage);
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_FORM_USAGE, e.getCode());
            return;
        }
        fail("ожидался отказ INVALID_FORM_USAGE для " + owner.eClass().getName() + " / " + usage); //$NON-NLS-1$ //$NON-NLS-2$
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
