package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AutoCommandBar;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.ManagedFormButtonType;
import com._1c.g5.v8.dt.form.service.item.IFormItemManagementService;
import com._1c.g5.v8.dt.form.service.naming.IFormItemNamingService;
import com._1c.g5.v8.dt.mcore.Command;
import com._1c.g5.v8.dt.mcore.NamedElement;

import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;

/**
 * Two defects found in a running EDT on 2026-09-15 on the EDT service path, which the model-only
 * tests of {@link FormMutationGapsTest} never reach.
 *
 * <ol>
 * <li>EDT's FormItemManagementService.addButton names a command button after its command
 * ({@code ФормаОбновить}) and ignores the requested name, so a following move_item or set_item by the
 * requested name answered "Form item not found".</li>
 * <li>create_form polled the owner .mdo at a path taken from the object URI. For an object created in
 * the same EDT session the URI is a BM one, its "path" is the FQN, and the tool reported
 * FORM_MATERIALIZATION_TIMEOUT although the form was created.</li>
 * </ol>
 *
 * <p>The EDT services are substituted through the package-private resolvers; the fakes reproduce the
 * observed EDT behaviour, not an idealised one.</p>
 */
public class FormItemServicePathTest {

    @Test
    public void buttonCreatedByTheEdtServiceKeepsTheRequestedName() {
        boolean[] edtServiceUsed = new boolean[1];
        EdtMetadataService service = serviceWith(edtLikeItemService(edtServiceUsed), null);
        Form form = formWithAutoCommandBar();

        apply(service, form,
                op("add_command", "name", "Обновить", "action", "Обновить"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                op("add_button", "name", "КнопкаОбновить", "command_name", "Обновить")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertTrue("the EDT item service path must be exercised", edtServiceUsed[0]); //$NON-NLS-1$
        List<FormItem> buttons = form.getAutoCommandBar().getItems();
        assertEquals(1, buttons.size());
        assertEquals("КнопкаОбновить", buttons.get(0).getName()); //$NON-NLS-1$
    }

    @Test
    public void edtNamingServiceRenamesTheItemWhenAvailable() {
        boolean[] edtServiceUsed = new boolean[1];
        List<String> renames = new ArrayList<>();
        EdtMetadataService service = serviceWith(edtLikeItemService(edtServiceUsed), recordingNamingService(renames));
        Form form = formWithAutoCommandBar();

        apply(service, form,
                op("add_command", "name", "Обновить", "action", "Обновить"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                op("add_button", "name", "КнопкаОбновить", "command_name", "Обновить")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals(List.of("КнопкаОбновить"), renames); //$NON-NLS-1$
        assertEquals("КнопкаОбновить", form.getAutoCommandBar().getItems().get(0).getName()); //$NON-NLS-1$
    }

    @Test
    public void aNameAnotherItemAlreadyHasIsRefusedWithAndWithoutTheNamingService() {
        for (boolean withNamingService : new boolean[] {true, false}) {
            boolean[] edtServiceUsed = new boolean[1];
            EdtMetadataService service = serviceWith(edtLikeItemService(edtServiceUsed),
                    withNamingService ? recordingNamingService(new ArrayList<>()) : null);
            Form form = formWithAutoCommandBar();
            com._1c.g5.v8.dt.form.model.FormGroup taken = FormFactory.eINSTANCE.createFormGroup();
            taken.setId(5);
            taken.setName("КнопкаОбновить"); //$NON-NLS-1$
            form.getItems().add(taken);

            try {
                apply(service, form,
                        op("add_command", "name", "Обновить", "action", "Обновить"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                        op("add_button", "name", "КнопкаОбновить", "command_name", "Обновить")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                org.junit.Assert.fail("a duplicate item name must be refused, naming service: " + withNamingService); //$NON-NLS-1$
            } catch (MetadataOperationException e) {
                assertEquals(MetadataOperationCode.METADATA_ALREADY_EXISTS, e.getCode());
                assertTrue(e.getMessage(), e.getMessage().contains("КнопкаОбновить")); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void ownerMdoPathIsNotTakenFromABmUri() throws Exception {
        EdtMetadataService service = serviceWith(null, null);
        IProject project = project("Demo"); //$NON-NLS-1$

        assertNull(ownerMdoPath(service, project, URI.createURI("bm://Demo/Document.Заказ"))); //$NON-NLS-1$
        assertEquals("src/Documents/Заказ/Заказ.mdo", //$NON-NLS-1$
                ownerMdoPath(service, project, URI.createPlatformResourceURI(
                        "/Demo/src/Documents/Заказ/Заказ.mdo", true))); //$NON-NLS-1$
    }

    // ─── fakes reproducing the observed EDT behaviour ───────────────────────

    private static EdtMetadataService serviceWith(IFormItemManagementService itemService, IFormItemNamingService naming) {
        // No @Override on purpose: against a build where the resolvers are still private the suite has to
        // compile and fail on the "EDT item service path must be exercised" assertion instead.
        return new EdtMetadataService(new EdtMetadataGateway(), new EventHandlerTargetResolver(item -> List.of())) {
            IFormItemManagementService resolveOptionalFormItemManagementService() {
                return itemService;
            }

            IFormItemNamingService resolveOptionalFormItemNamingService() {
                return naming;
            }
        };
    }

    /** addButton the way EDT 2025.2.3 does it: the name comes from the command, the descriptor name is ignored. */
    private static IFormItemManagementService edtLikeItemService(boolean[] used) {
        return (IFormItemManagementService) Proxy.newProxyInstance(
                IFormItemManagementService.class.getClassLoader(),
                new Class<?>[] {IFormItemManagementService.class},
                (proxy, method, args) -> {
                    if (!"addButton".equals(method.getName())) { //$NON-NLS-1$
                        throw new UnsupportedOperationException(method.getName());
                    }
                    used[0] = true;
                    FormItemContainer container = (FormItemContainer) args[0];
                    Command command = (Command) args[args.length == 6 ? 2 : 1];
                    Button button = FormFactory.eINSTANCE.createButton();
                    button.setId(100);
                    button.setName("Форма" + ((NamedElement) command).getName()); //$NON-NLS-1$
                    button.setCommandName(command);
                    button.setType(ManagedFormButtonType.COMMAND_BAR_BUTTON);
                    container.getItems().add(button);
                    return button;
                });
    }

    /**
     * rename the way FormItemNamingService does it in EDT 2025.2.3: exactly the requested name, or
     * IllegalArgumentException "The new name is not unique." when another item of the form has it.
     * setUniqueNameWithChildren is deliberately not implemented: it prefixes buttons of the form command
     * bar (ФормаКнопкаОбновить), which is what a running EDT showed.
     */
    private static IFormItemNamingService recordingNamingService(List<String> renames) {
        return (IFormItemNamingService) Proxy.newProxyInstance(
                IFormItemNamingService.class.getClassLoader(),
                new Class<?>[] {IFormItemNamingService.class},
                (proxy, method, args) -> {
                    if ("rename".equals(method.getName()) && args.length == 2 && args[0] instanceof String name) { //$NON-NLS-1$
                        FormItem item = (FormItem) args[1];
                        for (java.util.Iterator<org.eclipse.emf.ecore.EObject> it = org.eclipse.emf.ecore.util.EcoreUtil
                                .getRootContainer(item).eAllContents(); it.hasNext();) {
                            if (it.next() instanceof FormItem other && other != item && name.equals(other.getName())) {
                                throw new IllegalArgumentException("The new name is not unique."); //$NON-NLS-1$
                            }
                        }
                        renames.add(name);
                        item.setName(name);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static IProject project(String name) {
        return (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(), new Class<?>[] {IProject.class},
                (proxy, method, args) -> "getName".equals(method.getName()) ? name : null); //$NON-NLS-1$
    }

    // ─── plumbing ───────────────────────────────────────────────────────────

    private static Form formWithAutoCommandBar() {
        Form form = FormFactory.eINSTANCE.createForm();
        AutoCommandBar bar = FormFactory.eINSTANCE.createAutoCommandBar();
        bar.setId(-1);
        bar.setName("ФормаКоманднаяПанель"); //$NON-NLS-1$
        form.setAutoCommandBar(bar);
        return form;
    }

    private static Map<String, Object> op(String name, Object... keyValues) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", name); //$NON-NLS-1$
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            operation.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return operation;
    }

    @SafeVarargs
    private static void apply(EdtMetadataService service, Form form, Map<String, Object>... operations) {
        try {
            Method method = EdtMetadataService.class.getDeclaredMethod(
                    "applyFormModelOperations", Form.class, List.class, List.class); //$NON-NLS-1$
            method.setAccessible(true);
            method.invoke(service, form, List.of(operations), new ArrayList<>());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reflection keeps the suite compilable against builds that predate the helper. */
    private static String ownerMdoPath(EdtMetadataService service, IProject project, URI uri) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("ownerMdoPathFromUri", IProject.class, URI.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (String) method.invoke(service, project, uri);
    }
}
