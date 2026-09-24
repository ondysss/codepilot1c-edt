package com.codepilot1c.core.edt.rights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.EObject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.rights.IRightInfosService;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com._1c.g5.v8.dt.rights.model.util.RightsModelUtil;

/**
 * Report of dependent rights in {@code mutate_role_rights}.
 *
 * <p>Measured 24.09.2026 on a constant: {@code unset Read} also revoked Update/View/Edit, and {@code set Read}
 * brought back only Read. The cascade itself is the EDT rights model ({@code RightsModelUtil.getUncheckDependeces},
 * the same rule the EDT role editor applies) and must stay. The defect was the report: every dependency the object
 * supports was listed as {@code +dep X=Unset}, whether it had been granted or not, and without the previous value,
 * so the caller could not tell what the cascade had actually revoked and could not undo it.</p>
 *
 * <p>The test calls the real private {@code setRightOnObject} by reflection on plain EMF objects (a role, a
 * constant, rights from {@link RightsFactory}) and does not reference any class of the fix, so the same binary runs
 * against the installed build and against the fixed one.</p>
 */
public class EdtRoleRightsServiceCascadeReportTest {

    /** Rights EDT offers for a constant (the object of the 24.09.2026 measurement). */
    private static final List<String> CONSTANT_RIGHTS = List.of(
            "Read", "Update", "View", "Edit", "InputByString", "ReadDataHistory", "ViewDataHistory", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "UpdateDataHistory"); //$NON-NLS-1$

    private static final Pattern WAS_SET = Pattern.compile("^\\s*\\+dep (\\w+)=Unset \\(was Set\\)$"); //$NON-NLS-1$

    private EdtRoleRightsService service;
    private Role role;
    private RoleDescription description;
    private Constant constant;
    private Map<String, Right> rights;
    private IRightInfosService rightInfos;

    @Before
    public void setUp() {
        service = new EdtRoleRightsService();
        role = MdClassFactory.eINSTANCE.createRole();
        role.setName("Зонд"); //$NON-NLS-1$
        description = RightsFactory.eINSTANCE.createRoleDescription();
        description.setSetForNewObjects(false);
        role.setRights(description);
        constant = MdClassFactory.eINSTANCE.createConstant();
        constant.setName("ЗондКонстанта"); //$NON-NLS-1$
        rights = new LinkedHashMap<>();
        for (String name : CONSTANT_RIGHTS) {
            Right right = RightsFactory.eINSTANCE.createRight();
            right.setName(name);
            rights.put(name, right);
        }
        Set<Right> available = new LinkedHashSet<>(rights.values());
        rightInfos = (IRightInfosService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IRightInfosService.class }, (proxy, method, args) -> {
                    if ("getEClassRights".equals(method.getName()) || "getRights".equals(method.getName())) { //$NON-NLS-1$ //$NON-NLS-2$
                        return available;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    public void unsetReadReportsOnlyDependentsItActuallyRevoked() throws Exception {
        grant("Read", "Update", "View"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<String> details = apply("Read", "unset"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Set.of(
                "set Read=Unset (was Set)", //$NON-NLS-1$
                "  +dep Update=Unset (was Set)", //$NON-NLS-1$
                "  +dep View=Unset (was Set)"), new TreeSet<>(details)); //$NON-NLS-1$
        assertEquals(3, details.size());
    }

    @Test
    public void setUpdateDoesNotReportReadThatWasAlreadyGranted() throws Exception {
        grant("Read"); //$NON-NLS-1$

        List<String> details = apply("Update", "set"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("set Update=Set (was Unset)"), details); //$NON-NLS-1$
    }

    @Test
    public void setUpdateReportsReadItGranted() throws Exception {
        List<String> details = apply("Update", "set"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Set.of(
                "set Update=Set (was Unset)", //$NON-NLS-1$
                "  +dep Read=Set (was Unset)"), new TreeSet<>(details)); //$NON-NLS-1$
    }

    @Test
    public void reportOfUnsetReadIsEnoughToUndoIt() throws Exception {
        grant("Read", "Update", "View", "Edit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map<String, RightValue> original = state();

        List<String> details = apply("Read", "unset"); //$NON-NLS-1$ //$NON-NLS-2$
        apply("Read", "set"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String line : details) {
            Matcher matcher = WAS_SET.matcher(line);
            if (matcher.matches()) {
                apply(matcher.group(1), "set"); //$NON-NLS-1$
            }
        }

        assertEquals(original, state());
    }

    /** By design, pinned: the cascade follows the EDT model and set does not restore it. */
    @Test
    public void unsetReadRevokesDependentsAndSetReadRestoresOnlyRead() throws Exception {
        grant("Read", "Update", "View", "Edit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        apply("Read", "unset"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Set.of(), granted());

        apply("Read", "set"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Set.of("Read"), granted()); //$NON-NLS-1$
    }

    /** The report changed, the model did not: a redundant explicit UNSET of a dependency is still dropped. */
    @Test
    public void redundantExplicitUnsetOfDependencyIsStillNormalized() throws Exception {
        grant("Read"); //$NON-NLS-1$
        ObjectRights objectRights = RightsModelUtil.getOrCreateObjectRights(constant, description);
        ObjectRight explicitUnset = RightsFactory.eINSTANCE.createObjectRight();
        explicitUnset.setRight(rights.get("Edit")); //$NON-NLS-1$
        explicitUnset.setValue(RightValue.UNSET);
        objectRights.getRights().add(explicitUnset);

        apply("Read", "unset"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(RightsModelUtil.filterObjectRightByRight(rights.get("Edit"), objectRights.getRights())); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------------------------------------

    private void grant(String... names) throws Exception {
        ObjectRights objectRights = RightsModelUtil.getOrCreateObjectRights(constant, description);
        for (String name : names) {
            ObjectRight objectRight = RightsFactory.eINSTANCE.createObjectRight();
            objectRight.setRight(rights.get(name));
            objectRight.setValue(RightValue.SET);
            objectRights.getRights().add(objectRight);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> apply(String rightName, String value) throws Exception {
        Method method = EdtRoleRightsService.class.getDeclaredMethod("setRightOnObject", //$NON-NLS-1$
                String.class, String.class, RoleDescription.class, Role.class, EObject.class,
                IRightInfosService.class, String.class, Object.class, List.class);
        method.setAccessible(true);
        List<String> applied = new ArrayList<>();
        try {
            method.invoke(service, "Проект", "Role.Зонд", description, role, constant, rightInfos, //$NON-NLS-1$ //$NON-NLS-2$
                    rightName, value, applied);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
        return applied;
    }

    private Map<String, RightValue> state() {
        ObjectRights objectRights = RightsModelUtil.getOrCreateObjectRights(constant, description);
        RightValue defaultValue = RightsModelUtil.getDefaultRightValue(constant, role);
        Map<String, RightValue> result = new TreeMap<>();
        for (Map.Entry<String, Right> entry : rights.entrySet()) {
            ObjectRight explicit = RightsModelUtil.filterObjectRightByRight(entry.getValue(), objectRights.getRights());
            result.put(entry.getKey(), explicit != null && explicit.getValue() != null ? explicit.getValue() : defaultValue);
        }
        return result;
    }

    private Set<String> granted() {
        Set<String> result = new TreeSet<>();
        state().forEach((name, value) -> {
            if (value == RightValue.SET) {
                result.add(name);
            }
        });
        return result;
    }
}
