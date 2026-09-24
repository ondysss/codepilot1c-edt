package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Логика фикса квалификаторов {@code add_metadata_child} без разрешения типа: свёртка плоских ключей
 * {@code properties} в {@code type}, строгий разбор целых и отказ на незнакомом ключе. Работает в обычном прогоне
 * модуля тестов, без платформы OSGi. Поведение на EMF-реквизите целиком —
 * {@link EdtMetadataServiceChildTypePropertiesTest}.
 */
public class EdtMetadataServiceChildTypeQualifiersTest {

    // --- свёртка + разбор типа: то, что дойдёт до setAttributeType ----------

    @Test
    public void flatLengthReachesTypeSpecInEveryJsonShape() throws Exception {
        for (Object length : new Object[] {50, Double.valueOf(50.0), "50", "50.0", Long.valueOf(50)}) { //$NON-NLS-1$ //$NON-NLS-2$
            Object spec = typeSpecOf(props("type", "String", "length", length)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals("length=" + length, Integer.valueOf(50), accessor(spec, "stringLength")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void flatFixedAndNumberQualifiersReachTypeSpec() throws Exception {
        Object string = typeSpecOf(props("type", "String", "length", 10, "fixed", true)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(Boolean.TRUE, accessor(string, "stringFixed")); //$NON-NLS-1$
        Object number = typeSpecOf(props("type", "Number", "precision", 10, "scale", 3)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(Integer.valueOf(10), accessor(number, "numberPrecision")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), accessor(number, "numberScale")); //$NON-NLS-1$
    }

    @Test
    public void topLevelStringQualifiersReachTypeSpec() throws Exception {
        Object spec = typeSpecOf(props("type", "String", "stringQualifiers", Map.of("length", 50))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(Integer.valueOf(50), accessor(spec, "stringLength")); //$NON-NLS-1$
    }

    @Test
    public void typeWithoutQualifiersIsLeftUntouched() throws Exception {
        Map<String, Object> folded = fold(props("type", "String", "multiLine", true)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("String", folded.get("type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, folded.get("multiLine")); //$NON-NLS-1$
    }

    @Test
    public void compositeTypeWithoutFlatQualifiersIsLeftUntouched() throws Exception {
        List<String> composite = List.of("String(10)", "Number(5,0)"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(composite, fold(props("type", composite)).get("type")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- строгий разбор целых ------------------------------------------------

    @Test
    public void qualifierIntegerRefusesGarbage() throws Exception {
        for (Object bad : new Object[] {Double.valueOf(50.5), "abc", -1, "", Double.valueOf(Double.NaN)}) { //$NON-NLS-1$ //$NON-NLS-2$
            try {
                parseQualifierInteger("length", bad); //$NON-NLS-1$
                fail("ожидался отказ для " + bad); //$NON-NLS-1$
            } catch (MetadataOperationException e) {
                assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, e.getCode());
                assertTrue(e.getMessage(), e.getMessage().contains("length")); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void qualifierIntegerAcceptsZeroAsUnlimited() throws Exception {
        assertEquals(Integer.valueOf(0), parseQualifierInteger("length", 0)); //$NON-NLS-1$
        assertEquals(Integer.valueOf(0), parseQualifierInteger("length", Map.of("value", "0.0"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // --- незнакомые ключи ----------------------------------------------------

    @Test
    public void unknownKeyIsRefusedWithItsName() throws Exception {
        try {
            createProperties(props("type", "String", "lenght", 50)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("незнакомый ключ принят молча"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, e.getCode());
            assertTrue(e.getMessage(), e.getMessage().contains("lenght")); //$NON-NLS-1$
        }
    }

    @Test
    public void everyKeyReadByCreatePropertiesIsAccepted() throws Exception {
        Map<String, Object> all = props(
                "type", "String", "length", 20, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "multiLine", true, "multi_line", true, //$NON-NLS-1$ //$NON-NLS-2$
                "passwordMode", false, "password_mode", false, //$NON-NLS-1$ //$NON-NLS-2$
                "markNegatives", false, "mark_negatives", false, //$NON-NLS-1$ //$NON-NLS-2$
                "mask", "999", "fillChecking", "ShowError", "fill_checking", "ShowError", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "dataHistory", "Use", "data_history", "Use", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "fullTextSearch", "Use", "full_text_search", "Use", "indexing", "Index"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        Map<String, Object> accepted = createProperties(all);
        assertFalse("length должен уйти в type", accepted.containsKey("length")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("999", accepted.get("mask")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- обвязка -------------------------------------------------------------

    private static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fold(Map<String, Object> properties) throws Exception {
        return (Map<String, Object>) invoke("foldFlatTypeQualifierKeys", new Class<?>[] {Map.class}, properties); //$NON-NLS-1$
    }

    private static Object typeSpecOf(Map<String, Object> properties) throws Exception {
        Object type = createProperties(properties).get("type"); //$NON-NLS-1$
        return invoke("normalizeTypeSpec", new Class<?>[] {Object.class}, type); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> createProperties(Map<String, Object> properties) throws Exception {
        return (Map<String, Object>) invoke("normalizeBasicFeatureCreateProperties", //$NON-NLS-1$
                new Class<?>[] {Map.class, MetadataChildKind.class, String.class},
                properties, MetadataChildKind.ATTRIBUTE, "ТрекНомер"); //$NON-NLS-1$
    }

    private static Integer parseQualifierInteger(String qualifier, Object value) throws Exception {
        return (Integer) invoke("parseQualifierInteger", new Class<?>[] {String.class, Object.class}, qualifier, value); //$NON-NLS-1$
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(new EdtMetadataService(), args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static Object accessor(Object instance, String name) throws Exception {
        Method method = instance.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(instance);
    }
}
