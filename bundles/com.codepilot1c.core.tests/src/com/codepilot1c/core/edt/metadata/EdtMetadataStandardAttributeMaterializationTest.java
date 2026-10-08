package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.SourceType;
import com._1c.g5.v8.dt.mcore.UndefinedValue;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataHistoryUse;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.StandardAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.impl.CatalogImpl;
import com._1c.g5.v8.dt.platform.version.Version;

/** Uses native EMF objects; only the OSGi-derived field list is supplied by the fixture. */
public class EdtMetadataStandardAttributeMaterializationTest {
    private final EdtMetadataService service = new EdtMetadataService();
    private final Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
    private final CatalogWithFields catalog = new CatalogWithFields();

    public EdtMetadataStandardAttributeMaterializationTest() {
        catalog.setName("Topics");
        catalog.setHierarchical(true);
        configuration.getCatalogs().add(catalog);
        Field parent = McoreFactory.eINSTANCE.createField();
        parent.setName("Parent");
        parent.getSourceTypes().add(SourceType.STANDARD_FIELDS);
        catalog.fields.add(parent);
    }

    @Test
    public void firstCustomizationMaterializesNativeDefaults() throws Exception {
        Object target = resolve("Parent", () -> Version.V8_3_27);
        assertNotNull(target);
        assertEquals(1, catalog.getStandardAttributes().size());
        StandardAttribute attribute = catalog.getStandardAttributes().get(0);
        assertEquals("Parent", attribute.getName());
        assertEquals(DataHistoryUse.USE, attribute.getDataHistory());
        assertTrue(attribute.getMinValue() instanceof UndefinedValue);
        assertTrue(attribute.getMaxValue() instanceof UndefinedValue);
        assertTrue(attribute.getFillValue() instanceof UndefinedValue);
        assertSame(catalog, attribute.eContainer());
    }

    @Test
    public void firstSynonymChangeUsesExistingUpdatePath() throws Exception {
        Object target = resolve("Parent", () -> Version.V8_3_27);
        Method apply = EdtMetadataService.class.getDeclaredMethod("applyStandardAttributeChanges",
                target.getClass(), Map.class);
        apply.setAccessible(true);
        apply.invoke(service, target, Map.of("set", Map.of("synonym", "Parent topic")));
        assertEquals("Parent topic", catalog.getStandardAttributes().get(0).getSynonym().get("ru"));
    }

    @Test
    public void olderPlatformKeepsItsNativeDefaults() throws Exception {
        resolve("Parent", () -> Version.V8_3_10);
        assertEquals(DataHistoryUse.DONT_USE, catalog.getStandardAttributes().get(0).getDataHistory());
    }

    @Test
    public void russianAliasUsesCanonicalNativeName() throws Exception {
        resolve("Родитель", () -> Version.V8_3_27);
        assertEquals("Parent", catalog.getStandardAttributes().get(0).getName());
    }

    @Test
    public void secondUpdateReusesExistingCustomization() throws Exception {
        resolve("Parent", () -> Version.V8_3_27);
        StandardAttribute first = catalog.getStandardAttributes().get(0);
        first.setComment("Existing customization");
        resolve("Родитель", () -> { throw new AssertionError("Version must stay lazy"); });
        assertEquals(1, catalog.getStandardAttributes().size());
        assertSame(first, catalog.getStandardAttributes().get(0));
        assertEquals("Existing customization", first.getComment());
    }

    @Test
    public void unknownNameDoesNotCreateAnAttribute() throws Exception {
        expectCode("NoSuchStandardField", () -> Version.V8_3_27, "METADATA_NOT_FOUND");
        assertTrue(catalog.getStandardAttributes().isEmpty());
    }

    @Test
    public void userFieldCannotBeMaterializedAsStandard() throws Exception {
        catalog.fields.get(0).getSourceTypes().clear();
        expectCode("Parent", () -> Version.V8_3_27, "METADATA_NOT_FOUND");
        assertTrue(catalog.getStandardAttributes().isEmpty());
    }

    @Test
    public void missingVersionDoesNotCreateAnAttribute() throws Exception {
        expectCode("Parent", () -> null, "EDT_SERVICE_UNAVAILABLE");
        assertTrue(catalog.getStandardAttributes().isEmpty());
    }

    @Test
    public void referenceResolutionRemainsReadOnly() throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("resolveStandardAttributeTarget",
                Configuration.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(service, configuration, "Catalog.Topics.StandardAttribute.Parent");
            fail("Uncustomized reference must not create metadata");
        } catch (InvocationTargetException exception) {
            assertTrue(exception.getCause().getMessage().contains("Standard attribute not found"));
        }
        assertTrue(catalog.getStandardAttributes().isEmpty());
    }

    private Object resolve(String name, Supplier<Version> version) throws Exception {
        Method method;
        try {
            method = EdtMetadataService.class.getDeclaredMethod("resolveStandardAttributeTarget",
                    Configuration.class, String.class, Supplier.class);
        } catch (NoSuchMethodException oldImplementation) {
            // Run the same regression against the old resolver, failing on its actual not-found error.
            method = EdtMetadataService.class.getDeclaredMethod("resolveStandardAttributeTarget",
                    Configuration.class, String.class);
            method.setAccessible(true);
            return method.invoke(service, configuration, "Catalog.Topics.StandardAttribute." + name);
        }
        method.setAccessible(true);
        return method.invoke(service, configuration, "Catalog.Topics.StandardAttribute." + name, version);
    }

    private void expectCode(String name, Supplier<Version> version, String code) throws Exception {
        try {
            resolve(name, version);
            fail("Expected " + code);
        } catch (InvocationTargetException exception) {
            Object cause = exception.getCause();
            Method accessor = cause.getClass().getMethod("getCode");
            assertEquals(code, accessor.invoke(cause).toString());
        }
    }

    private static final class CatalogWithFields extends CatalogImpl {
        private final EList<Field> fields = new BasicEList<>();

        @Override
        public EList<Field> getFields() {
            return fields;
        }
    }
}
