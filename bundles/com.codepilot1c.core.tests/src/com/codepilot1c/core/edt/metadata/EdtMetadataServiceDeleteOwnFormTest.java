package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * Входящие ссылки перед {@code delete_metadata} формы без {@code force}.
 *
 * <p>Дефект: удаление формы отказывало {@code METADATA_DELETE_CONFLICT} с примером
 * {@code …Form.<Имя>.Form#mdForm} — ссылкой собственного содержимого формы на неё саму. Модель формы — отдельный
 * верхний объект BM, связанный с формой метаданных через {@code AbstractForm.mdForm}, а самоссылкой считался только
 * сам удаляемый объект. Воспроизведено на EDT 2025.2.3; удалить удавалось только {@code force=true}.</p>
 *
 * <p>Тест строит документ с формой из фабрик EMF, перекрёстные ссылки считает обходом графа и бьёт рефлексией по
 * настоящему {@code collectIncomingReferences} (BM-транзакция — прокси). Роль основной формы у владельца — настоящая
 * ссылка и обязана по-прежнему останавливать удаление.</p>
 */
public class EdtMetadataServiceDeleteOwnFormTest {

    private static final String FORM_FQN = "Document.Заказ.Form.ФормаДокумента"; //$NON-NLS-1$

    @Test
    public void ownFormContentDoesNotBlockDeletingTheForm() throws Exception {
        Fixture fixture = new Fixture();
        fixture.addBoundField(fixture.form, fixture.attribute);
        Result result = fixture.collect(FORM_FQN);
        assertEquals("ссылки на удаляемую форму: " + result.samples, 0, result.total); //$NON-NLS-1$
    }

    @Test
    public void defaultFormRoleOfOwnerStillBlocksDeletingTheForm() throws Exception {
        Fixture fixture = new Fixture();
        fixture.document.setDefaultObjectForm(fixture.mdForm);
        Result result = fixture.collect(FORM_FQN);
        assertEquals("ссылки: " + result.samples, 1, result.total); //$NON-NLS-1$
        assertSampleMentions(result, "#defaultObjectForm"); //$NON-NLS-1$
    }

    @Test
    public void anotherFormContentBoundToTheFormStillCounts() throws Exception {
        // Содержимое ДРУГОЙ формы своим не считается, даже если ссылается на удаляемую.
        Fixture fixture = new Fixture();
        DocumentForm otherMdForm = MdClassFactory.eINSTANCE.createDocumentForm();
        otherMdForm.setName("ФормаСписка"); //$NON-NLS-1$
        fixture.document.getForms().add(otherMdForm);
        Form other = FormFactory.eINSTANCE.createForm();
        other.setMdForm(otherMdForm);
        otherMdForm.setForm(other);
        fixture.addRoot(other);
        fixture.addBoundField(other, fixture.mdForm);
        Result result = fixture.collect(FORM_FQN);
        assertEquals("ссылки: " + result.samples, 1, result.total); //$NON-NLS-1$
        assertSampleMentions(result, "#object"); //$NON-NLS-1$
    }

    @Test
    public void attributeBoundOnTheFormStillBlocksDeletingTheAttribute() throws Exception {
        // Свою форму своей считает только сама форма: реквизит, выведенный на форму, по-прежнему не удаляется.
        Fixture fixture = new Fixture();
        fixture.addBoundField(fixture.form, fixture.attribute);
        Result result = fixture.collect("Document.Заказ.Attribute.Номенклатура"); //$NON-NLS-1$
        assertEquals("ссылки: " + result.samples, 1, result.total); //$NON-NLS-1$
        assertSampleMentions(result, "#object"); //$NON-NLS-1$
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

    /** Документ с реквизитом и формой; модель формы — отдельный верхний объект, связанный через mdForm. */
    private static final class Fixture {
        final Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        final Document document = MdClassFactory.eINSTANCE.createDocument();
        final DocumentAttribute attribute = MdClassFactory.eINSTANCE.createDocumentAttribute();
        final DocumentForm mdForm = MdClassFactory.eINSTANCE.createDocumentForm();
        final Form form = FormFactory.eINSTANCE.createForm();
        final List<EObject> roots = new ArrayList<>();

        Fixture() {
            configuration.setName("Конфигурация"); //$NON-NLS-1$
            document.setName("Заказ"); //$NON-NLS-1$
            attribute.setName("Номенклатура"); //$NON-NLS-1$
            document.getAttributes().add(attribute);
            mdForm.setName("ФормаДокумента"); //$NON-NLS-1$
            document.getForms().add(mdForm);
            configuration.getDocuments().add(document);
            form.setMdForm(mdForm);
            mdForm.setForm(form);
            addRoot(configuration);
            addRoot(document);
            addRoot(form);
        }

        /** Поле формы с записанным путём «Объект.<Имя>»; разрешение пути указывает на resolved. */
        void addBoundField(Form owner, EObject resolved) {
            DataPath dataPath = FormFactory.eINSTANCE.createDataPath();
            dataPath.getSegments().add("Объект"); //$NON-NLS-1$
            dataPath.getSegments().add("Номенклатура"); //$NON-NLS-1$
            DataPathReferredObject referred = FormFactory.eINSTANCE.createDataPathReferredObject();
            referred.setObject(resolved);
            referred.setSegmentIdx(1);
            dataPath.getObjects().add(referred);
            FormField field = FormFactory.eINSTANCE.createFormField();
            field.setName("Номенклатура"); //$NON-NLS-1$
            field.setDataPath(dataPath);
            owner.getItems().add(field);
        }

        void addRoot(EObject root) {
            // Своя ресурсная обёртка у каждого верхнего объекта — иначе URI фрагментов у разных корней совпадут.
            ResourceImpl resource = new ResourceImpl(URI.createURI("test:/" + roots.size())); //$NON-NLS-1$
            resource.getContents().add(root);
            roots.add(root);
        }

        Result collect(String targetFqn) throws Exception {
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
                    EdtMetadataServiceDeleteOwnFormTest.class.getClassLoader(),
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
                                return new ArrayList<>(incoming.getOrDefault(referenced, List.of()));
                            }
                            default:
                                return null;
                        }
                    });
            IBmModelManager modelManager = (IBmModelManager) Proxy.newProxyInstance(
                    EdtMetadataServiceDeleteOwnFormTest.class.getClassLoader(),
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
                    EdtMetadataServiceDeleteOwnFormTest.class.getClassLoader(),
                    new Class<?>[] {IProject.class},
                    (proxy, method, args) -> "getName".equals(method.getName()) ? "Scratch" : null); //$NON-NLS-1$ //$NON-NLS-2$

            Method collect = EdtMetadataService.class.getDeclaredMethod(
                    "collectIncomingReferences", IProject.class, Configuration.class, String.class, int.class); //$NON-NLS-1$
            collect.setAccessible(true);
            Object references;
            try {
                references = collect.invoke(new EdtMetadataService(gateway), project, configuration, targetFqn, 20);
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
                    EdtMetadataServiceDeleteOwnFormTest.class.getClassLoader(),
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
