package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.DataPathReferredObject;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.DerivedField;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.metadata.common.ChoiceParameterLink;
import com._1c.g5.v8.dt.metadata.common.CommonFactory;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFactory;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldFieldDef;
import com._1c.g5.v8.dt.metadata.dbview.DbViewTableDef;
import com._1c.g5.v8.dt.metadata.dbview.DocumentDbViewDefs;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentForm;
import com._1c.g5.v8.dt.metadata.mdclass.FunctionalOption;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObjectReferenceTypeDescription;
import com._1c.g5.v8.dt.metadata.mdtype.DocumentTypes;
import com._1c.g5.v8.dt.metadata.mdtype.MdObjectType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory;

/**
 * Проверка входящих ссылок перед {@code delete_metadata} без {@code force}.
 *
 * <p>Дефект: только что созданный реквизит документа не удалялся — {@code METADATA_DELETE_CONFLICT}, шесть ссылок,
 * примеры {@code Document.X#source}, {@code #mdObject}, {@code #presentationSource}. Это производные данные самого
 * документа, которые EDT выводит из модели и не сериализует: поле объекта ({@code Document.fields} →
 * {@code DerivedField.source}), представление таблицы БД ({@code dbViewDefs} → {@code DbViewFieldDef.mdObject},
 * {@code presentationSource}, {@code MdObjectReferenceTypeDescription.mdObject}), свойство произведённого типа
 * ({@code MdType.type} → {@code DerivedProperty.source}). Воспроизведено на EDT 2025.2.3.</p>
 *
 * <p>Настоящие ссылки разработчика на реквизит часто идут не на него, а на его производное поле
 * ({@code inputByString}, {@code dataLockFields}, {@code ChoiceParameterLink.field}), поэтому производный объект
 * не выбрасывается, а проходится насквозь. Тест строит ту же структуру из фабрик EMF, перекрёстные ссылки считает
 * обходом графа и бьёт рефлексией по настоящему {@code collectIncomingReferences}; на классы фикса не ссылается —
 * один бинарник идёт против сборки до фикса (обязан покраснеть поимённо) и после.</p>
 */
public class EdtMetadataServiceDeleteReferencesTest {

    private static final String TARGET_FQN = "Document.ПроверкаФорм.Attribute.Зонд"; //$NON-NLS-1$

    // --- сам симптом --------------------------------------------------------

    @Test
    public void freshAttributeHasNoIncomingReferences() throws Exception {
        Fixture fixture = new Fixture();
        Result result = fixture.collect();
        assertEquals("ссылки на свежий реквизит: " + result.samples, 0, result.total); //$NON-NLS-1$
    }

    @Test
    public void attributeWithTwoDerivedFieldsHasNoIncomingReferences() throws Exception {
        // В живой модели производных копий больше одной (основная таблица, таблица изменений, типы) — все свои.
        Fixture fixture = new Fixture();
        fixture.addDerivedFieldOfTarget(fixture.document);
        Result result = fixture.collect();
        assertEquals("ссылки на реквизит: " + result.samples, 0, result.total); //$NON-NLS-1$
    }

    // --- настоящие ссылки обязаны остановить удаление -------------------------

    @Test
    public void directReferenceFromAnotherObjectStillRefuses() throws Exception {
        Fixture fixture = new Fixture();
        FunctionalOption option = MdClassFactory.eINSTANCE.createFunctionalOption();
        option.setName("ФО"); //$NON-NLS-1$
        option.getContent().add(fixture.target);
        fixture.addRoot(option);
        Result result = fixture.collect();
        assertEquals(1, result.total);
        assertSampleMentions(result, "#content"); //$NON-NLS-1$
    }

    @Test
    public void inputByStringThroughDerivedFieldStillRefuses() throws Exception {
        Fixture fixture = new Fixture();
        fixture.document.getInputByString().add(fixture.derivedField);
        assertTrue("ввод по строке через производное поле не остановил удаление", fixture.collect().total >= 1); //$NON-NLS-1$
    }

    @Test
    public void inputByStringThroughDerivedFieldIsNamed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.document.getInputByString().add(fixture.derivedField);
        Result result = fixture.collect();
        assertEquals("ссылки: " + result.samples, 1, result.total); //$NON-NLS-1$
        assertSampleMentions(result, "#inputByString"); //$NON-NLS-1$
    }

    @Test
    public void choiceParameterLinkOfSiblingThroughDerivedFieldIsNamed() throws Exception {
        Fixture fixture = new Fixture();
        DocumentAttribute sibling = MdClassFactory.eINSTANCE.createDocumentAttribute();
        sibling.setName("Сосед"); //$NON-NLS-1$
        ChoiceParameterLink link = CommonFactory.eINSTANCE.createChoiceParameterLink();
        link.setField(fixture.derivedField);
        sibling.getChoiceParameterLinks().add(link);
        fixture.document.getAttributes().add(sibling);
        Result result = fixture.collect();
        assertEquals("ссылки: " + result.samples, 1, result.total); //$NON-NLS-1$
        assertSampleMentions(result, "#field"); //$NON-NLS-1$
    }

    @Test
    public void derivedDataOfAnotherTopObjectStillCounts() throws Exception {
        // Производные данные чужого объекта — не наше дело судить: считаются, как до фикса.
        Fixture fixture = new Fixture();
        Document other = MdClassFactory.eINSTANCE.createDocument();
        other.setName("Другой"); //$NON-NLS-1$
        fixture.addDerivedFieldOfTarget(other);
        fixture.addRoot(other);
        Result result = fixture.collect();
        assertEquals("ссылки: " + result.samples, 1, result.total); //$NON-NLS-1$
    }

    @Test
    public void unreadableReferencesToDerivedObjectStillCount() throws Exception {
        // Не смогли прочитать, кто ссылается на производное поле, — считаем ссылку, а не молча пропускаем.
        Fixture fixture = new Fixture();
        fixture.unreadable.add(fixture.derivedField);
        Result result = fixture.collect();
        assertEquals("ссылки: " + result.samples, 1, result.total); //$NON-NLS-1$
    }

    // --- производные данные формы владельца ---------------------------------

    @Test
    public void derivedFieldOfOwnerFormHasNoIncomingReferences() throws Exception {
        // Живой симптом: после update_metadata по документу EDT пересчитывает производные данные формы, и её
        // производное поле давало «…Form.ФормаДокумента.Form#source» — отказ удалить свежий реквизит.
        Fixture fixture = new Fixture();
        Form form = fixture.addFormOfOwner(fixture.document);
        fixture.addFormDerivedField(form);
        Result result = fixture.collect();
        assertEquals("ссылки на свежий реквизит: " + result.samples, 0, result.total); //$NON-NLS-1$
    }

    @Test
    public void formContextOfOwnerFormHasNoIncomingReferences() throws Exception {
        // AbstractForm.formContext (transient) → ContextDef.properties → DerivedProperty.source — тоже вывод EDT.
        Fixture fixture = new Fixture();
        Form form = fixture.addFormOfOwner(fixture.document);
        ContextDef formContext = McoreFactory.eINSTANCE.createContextDef();
        DerivedProperty property = McoreFactory.eINSTANCE.createDerivedProperty();
        property.setSource(fixture.target);
        formContext.getProperties().add(property);
        form.setFormContext(formContext);
        Result result = fixture.collect();
        assertEquals("ссылки на свежий реквизит: " + result.samples, 0, result.total); //$NON-NLS-1$
    }

    @Test
    public void writtenFormFieldBindingThroughDerivedFieldStillRefuses() throws Exception {
        // Поле формы с путём данных «Объект.Зонд»: записаны сегменты, а разрешение пути (transient objects)
        // указывает на производное поле формы. Это настоящая привязка — удаление обязано остановиться.
        Fixture fixture = new Fixture();
        Form form = fixture.addFormOfOwner(fixture.document);
        DerivedField formField = fixture.addFormDerivedField(form);
        fixture.addBoundFormField(form, formField);
        Result result = fixture.collect();
        assertTrue("привязка поля формы не остановила удаление: " + result.samples, result.total >= 1); //$NON-NLS-1$
    }

    @Test
    public void writtenFormFieldBindingThroughDerivedFieldIsNamed() throws Exception {
        // Отказ обязан назвать саму привязку, а не производное поле формы.
        Fixture fixture = new Fixture();
        Form form = fixture.addFormOfOwner(fixture.document);
        DerivedField formField = fixture.addFormDerivedField(form);
        fixture.addBoundFormField(form, formField);
        Result result = fixture.collect();
        assertEquals("ссылки: " + result.samples, 1, result.total); //$NON-NLS-1$
        assertSampleMentions(result, "#object"); //$NON-NLS-1$
    }

    @Test
    public void writtenFormFieldBindingToAttributeStillRefuses() throws Exception {
        Fixture fixture = new Fixture();
        Form form = fixture.addFormOfOwner(fixture.document);
        fixture.addBoundFormField(form, fixture.target);
        Result result = fixture.collect();
        assertEquals("ссылки: " + result.samples, 1, result.total); //$NON-NLS-1$
        assertSampleMentions(result, "#object"); //$NON-NLS-1$
    }

    @Test
    public void derivedFieldOfAnotherDocumentFormStillCounts() throws Exception {
        Fixture fixture = new Fixture();
        Document other = MdClassFactory.eINSTANCE.createDocument();
        other.setName("Другой"); //$NON-NLS-1$
        fixture.addRoot(other);
        Form form = fixture.addFormOfOwner(other);
        fixture.addFormDerivedField(form);
        Result result = fixture.collect();
        assertEquals("ссылки: " + result.samples, 1, result.total); //$NON-NLS-1$
    }

    @Test
    public void derivedFieldOfFormWithoutMdFormStillCounts() throws Exception {
        // Чья это форма — не известно: не угадываем, считаем, как до фикса.
        Fixture fixture = new Fixture();
        Form form = FormFactory.eINSTANCE.createForm();
        fixture.addRoot(form);
        fixture.addFormDerivedField(form);
        Result result = fixture.collect();
        assertEquals("ссылки: " + result.samples, 1, result.total); //$NON-NLS-1$
    }

    // --- обвязка -------------------------------------------------------------

    private static void assertSampleMentions(Result result, String fragment) {
        for (String sample : result.samples) {
            if (sample.contains(fragment)) {
                return;
            }
        }
        fail("в примерах нет «" + fragment + "»: " + result.samples); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private record Result(int total, List<String> samples) {
    }

    /** Документ с реквизитом-зондом и всеми производными данными, что EDT строит для реквизита. */
    private static final class Fixture {
        final Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        final Document document = MdClassFactory.eINSTANCE.createDocument();
        final DocumentAttribute target = MdClassFactory.eINSTANCE.createDocumentAttribute();
        final DerivedField derivedField;
        final List<EObject> roots = new ArrayList<>();
        final Set<EObject> unreadable = new HashSet<>();

        Fixture() {
            configuration.setName("Конфигурация"); //$NON-NLS-1$
            document.setName("ПроверкаФорм"); //$NON-NLS-1$
            target.setName("Зонд"); //$NON-NLS-1$
            document.getAttributes().add(target);
            configuration.getDocuments().add(document);

            // Document.fields (transient) → DerivedField.source
            derivedField = addDerivedFieldOfTarget(document);

            // Document.dbViewDefs (transient) → DbViewFieldDef.mdObject / presentationSource / type.mdObject
            DocumentDbViewDefs dbViewDefs = DbViewFactory.eINSTANCE.createDocumentDbViewDefs();
            DbViewTableDef mainView = DbViewFactory.eINSTANCE.createDbViewTableDef();
            DbViewFieldFieldDef fieldDef = DbViewFactory.eINSTANCE.createDbViewFieldFieldDef();
            fieldDef.setMdObject(target);
            fieldDef.setPresentationSource(target);
            MdObjectReferenceTypeDescription typeDescription =
                    MdClassFactory.eINSTANCE.createMdObjectReferenceTypeDescription();
            typeDescription.setMdObject(target);
            fieldDef.setType(typeDescription);
            mainView.getFields().add(fieldDef);
            dbViewDefs.setMainView(mainView);
            document.setDbViewDefs(dbViewDefs);

            // producedTypes.objectType.type (transient) → contextDef.properties → DerivedProperty.source
            DocumentTypes producedTypes = MdTypeFactory.eINSTANCE.createDocumentTypes();
            MdObjectType objectType = MdTypeFactory.eINSTANCE.createMdObjectType();
            Type type = McoreFactory.eINSTANCE.createType();
            ContextDef contextDef = McoreFactory.eINSTANCE.createContextDef();
            DerivedProperty property = McoreFactory.eINSTANCE.createDerivedProperty();
            property.setSource(target);
            contextDef.getProperties().add(property);
            type.setContextDef(contextDef);
            objectType.setType(type);
            producedTypes.setObjectType(objectType);
            document.setProducedTypes(producedTypes);

            addRoot(configuration);
            addRoot(document);
        }

        DerivedField addDerivedFieldOfTarget(Document owner) {
            DerivedField field = McoreFactory.eINSTANCE.createDerivedField();
            field.setSource(target);
            owner.getFields().add(field);
            return field;
        }

        /** Форма документа: модель формы — отдельный верхний объект BM, связанный с формой метаданных через mdForm. */
        Form addFormOfOwner(Document owner) {
            DocumentForm mdForm = MdClassFactory.eINSTANCE.createDocumentForm();
            mdForm.setName("ФормаДокумента"); //$NON-NLS-1$
            owner.getForms().add(mdForm);
            Form form = FormFactory.eINSTANCE.createForm();
            form.setMdForm(mdForm);
            mdForm.setForm(form);
            addRoot(form);
            return form;
        }

        /** Form.fields (transient, inferred) → DerivedField.source. */
        DerivedField addFormDerivedField(Form form) {
            DerivedField field = McoreFactory.eINSTANCE.createDerivedField();
            field.setSource(target);
            form.getFields().add(field);
            return field;
        }

        /** Поле формы с записанным путём «Объект.Зонд»; разрешение пути (transient objects) указывает на resolved. */
        void addBoundFormField(Form form, EObject resolved) {
            DataPath dataPath = FormFactory.eINSTANCE.createDataPath();
            dataPath.getSegments().add("Объект"); //$NON-NLS-1$
            dataPath.getSegments().add("Зонд"); //$NON-NLS-1$
            DataPathReferredObject referred = FormFactory.eINSTANCE.createDataPathReferredObject();
            referred.setObject(resolved);
            referred.setSegmentIdx(1);
            dataPath.getObjects().add(referred);
            FormField field = FormFactory.eINSTANCE.createFormField();
            field.setName("Зонд"); //$NON-NLS-1$
            field.setDataPath(dataPath);
            form.getItems().add(field);
        }

        void addRoot(EObject root) {
            // Своя ресурсная обёртка у каждого верхнего объекта — иначе URI фрагментов у разных корней совпадут.
            ResourceImpl resource = new ResourceImpl(URI.createURI("test:/" + roots.size())); //$NON-NLS-1$
            resource.getContents().add(root);
            roots.add(root);
        }

        Result collect() throws Exception {
            Map<String, EObject> byUri = new HashMap<>();
            Map<EObject, List<IBmCrossReference>> incoming = new IdentityHashMap<>();
            for (EObject root : roots) {
                List<EObject> all = new ArrayList<>();
                all.add(root);
                for (TreeIterator<EObject> it = root.eAllContents(); it.hasNext();) {
                    all.add(it.next());
                }
                for (EObject object : all) {
                    String uri = EcoreUtil.getURI(object).toString();
                    EObject previous = byUri.put(uri, object);
                    if (previous != null && previous != object) {
                        throw new AssertionError("обвязка: совпали URI двух объектов " + uri); //$NON-NLS-1$
                    }
                    for (EReference reference : object.eClass().getEAllReferences()) {
                        if (reference.isContainment() || reference.isContainer() || reference.isDerived()) {
                            continue;
                        }
                        Object value = object.eGet(reference, false);
                        Collection<?> values = value instanceof Collection<?> many ? many
                                : value == null ? List.of() : List.of(value);
                        for (Object referenced : values) {
                            if (referenced instanceof EObject referencedObject) {
                                incoming.computeIfAbsent(referencedObject, key -> new ArrayList<>())
                                        .add(crossReference((IBmObject) object, reference));
                            }
                        }
                    }
                }
            }

            IBmTransaction transaction = (IBmTransaction) Proxy.newProxyInstance(
                    EdtMetadataServiceDeleteReferencesTest.class.getClassLoader(),
                    new Class<?>[] {IBmTransaction.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "toTransactionObject": //$NON-NLS-1$
                                return args[0];
                            case "getReferences": { //$NON-NLS-1$
                                EObject referenced = byUri.get(String.valueOf(args[0]));
                                if (referenced == null) {
                                    throw new AssertionError("обвязка: неизвестный URI " + args[0]); //$NON-NLS-1$
                                }
                                if (unreadable.contains(referenced)) {
                                    throw new IllegalStateException("references are not readable"); //$NON-NLS-1$
                                }
                                return new ArrayList<>(incoming.getOrDefault(referenced, List.of()));
                            }
                            default:
                                return null;
                        }
                    });
            IBmModelManager modelManager = (IBmModelManager) Proxy.newProxyInstance(
                    EdtMetadataServiceDeleteReferencesTest.class.getClassLoader(),
                    new Class<?>[] {IBmModelManager.class},
                    (proxy, method, args) -> {
                        if ("executeReadOnlyTask".equals(method.getName()) && args != null && args.length == 2 //$NON-NLS-1$
                                && args[1] instanceof IBmSingleNamespaceTask<?> task) {
                            return task.execute(transaction);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            EdtMetadataGateway gateway = new EdtMetadataGateway() {
                @Override
                public IBmModelManager getBmModelManager() {
                    return modelManager;
                }
            };
            IProject project = (IProject) Proxy.newProxyInstance(
                    EdtMetadataServiceDeleteReferencesTest.class.getClassLoader(),
                    new Class<?>[] {IProject.class},
                    (proxy, method, args) -> "getName".equals(method.getName()) ? "TestProject" : null); //$NON-NLS-1$ //$NON-NLS-2$

            Method collect = EdtMetadataService.class.getDeclaredMethod(
                    "collectIncomingReferences", IProject.class, Configuration.class, String.class, int.class); //$NON-NLS-1$
            collect.setAccessible(true);
            Object references;
            try {
                references = collect.invoke(new EdtMetadataService(gateway), project, configuration, TARGET_FQN, 20);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw e;
            }
            Method total = references.getClass().getDeclaredMethod("total"); //$NON-NLS-1$
            Method samples = references.getClass().getDeclaredMethod("samples"); //$NON-NLS-1$
            total.setAccessible(true);
            samples.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> sampleList = (List<String>) samples.invoke(references);
            return new Result((Integer) total.invoke(references), sampleList);
        }

        private static IBmCrossReference crossReference(IBmObject source, EStructuralFeature feature) {
            return (IBmCrossReference) Proxy.newProxyInstance(
                    EdtMetadataServiceDeleteReferencesTest.class.getClassLoader(),
                    new Class<?>[] {IBmCrossReference.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getObject" -> source; //$NON-NLS-1$
                        case "getFeature" -> feature; //$NON-NLS-1$
                        case "getObjectId" -> Long.valueOf(0L); //$NON-NLS-1$
                        case "toString" -> source.eClass().getName() + "#" + feature.getName(); //$NON-NLS-1$ //$NON-NLS-2$
                        default -> null;
                    });
        }
    }
}
