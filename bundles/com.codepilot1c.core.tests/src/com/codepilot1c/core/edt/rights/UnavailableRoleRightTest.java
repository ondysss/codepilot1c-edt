package com.codepilot1c.core.edt.rights;

import static org.junit.Assert.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.rights.IRightInfosService;
import com._1c.g5.v8.dt.rights.model.*;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class UnavailableRoleRightTest {
    private final EdtRoleRightsService service = new EdtRoleRightsService();
    private final Role role = MdClassFactory.eINSTANCE.createRole();
    private final Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
    private final RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
    private final ObjectRights object = RightsFactory.eINSTANCE.createObjectRights();
    private final List<String> applied = new ArrayList<>();

    public UnavailableRoleRightTest() {
        role.setRights(description);
        configuration.setNamePrefix("ex_");
        object.setObject(configuration);
        description.getRights().add(object);
    }

    @Test public void canRevokePersistedRightAbsentFromAvailableRights() throws Exception {
        entry("ThinClient", "ТонкийКлиент", RightValue.SET);
        mutate("ThinClient", "unset");
        assertTrue(object.getRights().isEmpty());
        assertEquals(List.of("remove unavailable ThinClient (was Set)"), applied);
    }

    @Test public void acceptsCaseInsensitiveRussianAlias() throws Exception {
        entry("ThinClient", "ТонкийКлиент", RightValue.SET);
        mutate("тонкийклиент", "deny");
        assertTrue(object.getRights().isEmpty());
    }

    @Test public void refusesToGrantUnsupportedRightEvenIfPersisted() throws Exception {
        ObjectRight original = entry("ThinClient", "ТонкийКлиент", RightValue.SET);
        rejected("ThinClient", "set");
        assertEquals(List.of(original), object.getRights());
        assertTrue(applied.isEmpty());
    }

    @Test public void refusesUnknownRevocationWithoutCreatingEntries() throws Exception {
        ObjectRight original = entry("ThinClient", "ТонкийКлиент", RightValue.SET);
        rejected("UnknownRight", "unset");
        assertEquals(List.of(original), object.getRights());
        assertTrue(applied.isEmpty());
    }

    @Test public void keepsOtherRightsRestrictionsAndRoleFlags() throws Exception {
        description.setSetForNewObjects(true);
        description.setSetForAttributesByDefault(true);
        description.setIndependentRightsOfChildObjects(true);
        ObjectRight other = entry("MobileClient", "МобильныйКлиент", RightValue.SET);
        Rls restriction = RightsFactory.eINSTANCE.createRls();
        other.getRestrictionsByCondition().add(restriction);
        entry("ThinClient", "ТонкийКлиент", RightValue.SET);
        mutate("ThinClient", "unset");
        assertEquals(List.of(other), object.getRights());
        assertSame(restriction, other.getRestrictionsByCondition().get(0));
        assertTrue(description.isSetForNewObjects());
        assertTrue(description.isSetForAttributesByDefault());
        assertTrue(description.isIndependentRightsOfChildObjects());
    }

    @Test public void removesAllDuplicatePersistedEntries() throws Exception {
        entry("ThinClient", "ТонкийКлиент", RightValue.SET);
        entry("ThinClient", "ТонкийКлиент", RightValue.UNSET);
        mutate("ThinClient", "unset");
        assertTrue(object.getRights().isEmpty());
        assertEquals(2, applied.size());
    }

    @Test public void supportedRightStillUsesExistingDependencyPath() throws Exception {
        Right read = right("Read", "Чтение");
        mutate("Read", "set", Set.of(read));
        assertEquals(1, object.getRights().size());
        assertEquals(RightValue.SET, object.getRights().get(0).getValue());
    }

    private ObjectRight entry(String name, String ru, RightValue value) {
        ObjectRight entry = RightsFactory.eINSTANCE.createObjectRight();
        entry.setRight(right(name, ru));
        entry.setValue(value);
        object.getRights().add(entry);
        return entry;
    }
    private Right right(String name, String ru) {
        Right right = RightsFactory.eINSTANCE.createRight();
        right.setName(name);right.setNameRu(ru);return right;
    }
    private void rejected(String name, String value) throws Exception {
        try {mutate(name, value);fail("Unsupported operation must be refused");}
        catch (MetadataOperationException expected) {assertFalse(expected.isRecoverable());}
    }
    private void mutate(String name, String value) throws Exception {mutate(name,value,Set.of());}
    private void mutate(String name, String value, Set<Right> available) throws Exception {
        IRightInfosService infos = (IRightInfosService) Proxy.newProxyInstance(
            IRightInfosService.class.getClassLoader(),new Class<?>[]{IRightInfosService.class},
            (proxy, method, args) -> method.getName().equals("getEClassRights") ? available : null);
        java.lang.reflect.Method method = EdtRoleRightsService.class.getDeclaredMethod("setRightOnObject",String.class,String.class,
            RoleDescription.class,Role.class,org.eclipse.emf.ecore.EObject.class,IRightInfosService.class,
            String.class,Object.class,List.class);
        method.setAccessible(true);
        try {method.invoke(service,"Extension","Role.ex_Basic",description,role,configuration,infos,name,value,applied);}
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }
}
