package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Квалификаторы типа из {@code properties} у {@code add_metadata_child}.
 *
 * <p>Дефект: {@code properties={"type":"String","length":50}} проходил валидацию, мутация отвечала успехом, а в
 * .mdo ложилось {@code <length>150</length>}. {@code applyDefaultTypeIfNeeded} отдавал в разбор типа только
 * значение ключа {@code type}; соседний {@code length} не читал никто, и срабатывал дефолт 150. Тем же путём
 * молча терялся любой ключ, которого код не знает.</p>
 *
 * <p>Тест бьёт рефлексией по настоящему {@code applyDefaultTypeIfNeeded} с реквизитом документа из фабрики EMF,
 * транзакцией-заглушкой и заранее разрешённым типом. На классы фикса он не ссылается — поэтому один бинарник идёт
 * против сборки до фикса (обязан покраснеть поимённо) и после.</p>
 *
 * <p>Разрешение типа идёт через {@code TypeProviderService}, а вне платформы OSGi (обычный прогон модуля тестов) он
 * падает в статической инициализации. Поэтому тесты, которым нужно разрешение типа, без платформы пропускаются
 * ({@link Assume}); полностью набор гоняет {@code tools/run-child-type-properties-eval.sh} с заглушкой сервиса и
 * свойством {@value #REQUIRE_TYPE_RESOLUTION}, при котором пропуск превращается в ошибку. Логику фикса без разрешения
 * типа покрывает {@link EdtMetadataServiceChildTypeQualifiersTest}.</p>
 */
public class EdtMetadataServiceChildTypePropertiesTest {

    static final String REQUIRE_TYPE_RESOLUTION = "codepilot.eval.requireTypeResolution"; //$NON-NLS-1$

    // --- длина строки: сам симптом ------------------------------------------

    @Test
    public void flatLengthAsIntegerIsApplied() throws Exception {
        TypeDescription type = applyToNewAttribute(props("type", "String", "length", 50)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(50, type.getStringQualifiers().getLength());
    }

    @Test
    public void flatLengthAsDoubleFromJsonIsApplied() throws Exception {
        // Ровно то, что приходит после edt_validate_request: JSON-число стало Double 50.0.
        TypeDescription type = applyToNewAttribute(props("type", "String", "length", Double.valueOf(50.0))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(50, type.getStringQualifiers().getLength());
    }

    @Test
    public void flatLengthAsDecimalStringIsApplied() throws Exception {
        TypeDescription type = applyToNewAttribute(props("type", "String", "length", "50.0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(50, type.getStringQualifiers().getLength());
    }

    @Test
    public void flatFixedIsApplied() throws Exception {
        TypeDescription type = applyToNewAttribute(props("type", "String", "length", 10, "fixed", true)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(10, type.getStringQualifiers().getLength());
        assertTrue(type.getStringQualifiers().isFixed());
    }

    @Test
    public void topLevelStringQualifiersIsApplied() throws Exception {
        TypeDescription type = applyToNewAttribute(props(
                "type", "String", //$NON-NLS-1$ //$NON-NLS-2$
                "stringQualifiers", Map.of("length", 50))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(50, type.getStringQualifiers().getLength());
    }

    @Test
    public void lengthWithoutTypeKeepsDefaultStringType() throws Exception {
        TypeDescription type = applyToNewAttribute(props("length", 50)); //$NON-NLS-1$
        assertNotNull("ожидалась строка по умолчанию", type.getStringQualifiers()); //$NON-NLS-1$
        assertEquals(50, type.getStringQualifiers().getLength());
    }

    @Test
    public void flatNumberQualifiersAreApplied() throws Exception {
        TypeDescription type = applyToNewAttribute(props("type", "Number", "precision", 10, "scale", 3)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(10, type.getNumberQualifiers().getPrecision());
        assertEquals(3, type.getNumberQualifiers().getScale());
    }

    // --- что работало и обязано работать дальше ------------------------------

    @Test
    public void qualifiersInsideTypeMapStillWork() throws Exception {
        TypeDescription type = applyToNewAttribute(props(
                "type", Map.of("type", "String", "stringQualifiers", Map.of("length", 50)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals(50, type.getStringQualifiers().getLength());
    }

    @Test
    public void inlineStringLiteralStillWorks() throws Exception {
        TypeDescription type = applyToNewAttribute(props("type", "String(50)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(50, type.getStringQualifiers().getLength());
    }

    @Test
    public void typeWithoutLengthKeepsDefault150() throws Exception {
        TypeDescription type = applyToNewAttribute(props("type", "String")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(150, type.getStringQualifiers().getLength());
    }

    @Test
    public void knownCreatePropertiesAreAccepted() throws Exception {
        assumeTypeResolutionAvailable();
        BasicFeature feature = newAttribute();
        apply(feature, props("type", "String", "multiLine", true, "mask", "999", "fillChecking", "ShowError")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        assertTrue(feature.isMultiLine());
        assertEquals("999", feature.getMask()); //$NON-NLS-1$
    }

    // --- мусор: явная ошибка вместо молчаливого дефолта ----------------------

    @Test
    public void unknownKeyIsRefused() throws Exception {
        assertRefused(props("type", "String", "lenght", 50), "lenght"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void synonymInsidePropertiesIsRefused() throws Exception {
        // Синоним одиночного ребёнка — параметр верхнего уровня; внутри properties он тоже молча терялся.
        assertRefused(props("type", "String", "synonym", "Трек"), "synonym"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void fractionalLengthIsRefused() throws Exception {
        assertRefused(props("type", "String", "length", Double.valueOf(50.5)), "50.5"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void nonNumericLengthIsRefused() throws Exception {
        assertRefused(props("type", "String", "length", "abc"), "abc"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void negativeLengthIsRefused() throws Exception {
        assertRefused(props("type", "String", "length", -1), "-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void flatLengthOnCompositeTypeIsRefused() throws Exception {
        // Какому из типов относится плоская длина — не сказано; свёртка в один спек потеряла бы Number молча.
        assertRefused(props("type", List.of("String", "Number"), "length", 50), "composite"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    // --- обвязка -------------------------------------------------------------

    private static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static BasicFeature newAttribute() {
        BasicFeature feature = MdClassFactory.eINSTANCE.createDocumentAttribute();
        feature.setName("ТрекНомер"); //$NON-NLS-1$
        return feature;
    }

    /**
     * Пропускает тест, если {@code TypeProviderService} не инициализируется (нет платформы OSGi).
     *
     * <p>Со свойством {@value #REQUIRE_TYPE_RESOLUTION} (eval) проверки нет и пропуска нет: сломанная заглушка
     * всплывёт ошибкой теста, а не тихим зелёным. Пакет при этом не трогается нарочно: неподписанная заглушка
     * живёт в пакете подписанного jar EDT, и любой второй класс этого пакета, загруженный пробой, отбивается
     * {@code SecurityException} по несовпадению подписи — в каком порядке ни грузи.</p>
     */
    private static void assumeTypeResolutionAvailable() {
        if (Boolean.getBoolean(REQUIRE_TYPE_RESOLUTION)) {
            return;
        }
        boolean available;
        try {
            Class.forName("com._1c.g5.v8.dt.platform.core.typeinfo.TypeProviderService", true, //$NON-NLS-1$
                    EdtMetadataService.class.getClassLoader());
            available = true;
        } catch (ClassNotFoundException | LinkageError e) {
            available = false;
        }
        Assume.assumeTrue("TypeProviderService недоступен вне OSGi: полный прогон — tools/run-child-type-properties-eval.sh", //$NON-NLS-1$
                available);
    }

    private static TypeDescription applyToNewAttribute(Map<String, Object> properties) throws Exception {
        assumeTypeResolutionAvailable();
        BasicFeature feature = newAttribute();
        apply(feature, properties);
        TypeDescription type = feature.getType();
        assertNotNull("тип не назначен", type); //$NON-NLS-1$
        return type;
    }

    private static void assertRefused(Map<String, Object> properties, String expectedInMessage) throws Exception {
        try {
            apply(newAttribute(), properties);
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, e.getCode());
            assertTrue("в тексте отказа нет «" + expectedInMessage + "»: " + e.getMessage(), //$NON-NLS-1$ //$NON-NLS-2$
                    e.getMessage().contains(expectedInMessage));
            return;
        }
        fail("ожидался отказ INVALID_PROPERTY_VALUE, а реквизит создан молча: " + properties); //$NON-NLS-1$
    }

    private static void apply(BasicFeature feature, Map<String, Object> properties) throws Exception {
        Map<String, TypeItem> preResolved = new HashMap<>();
        preResolved.put("String", simpleType("String")); //$NON-NLS-1$ //$NON-NLS-2$
        preResolved.put("Number", simpleType("Number")); //$NON-NLS-1$ //$NON-NLS-2$
        IBmPlatformTransaction transaction = (IBmPlatformTransaction) Proxy.newProxyInstance(
                EdtMetadataServiceChildTypePropertiesTest.class.getClassLoader(),
                new Class<?>[] {IBmPlatformTransaction.class},
                (proxy, method, args) -> "toTransactionObject".equals(method.getName()) && args != null && args.length == 1 //$NON-NLS-1$
                        ? args[0]
                        : null);
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "applyDefaultTypeIfNeeded", //$NON-NLS-1$
                Configuration.class, MdObject.class, MetadataChildKind.class, Map.class, Map.class,
                IBmPlatformTransaction.class, String.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(new EdtMetadataService(), null, feature, MetadataChildKind.ATTRIBUTE, properties,
                    preResolved, transaction, "Document.Заказ", feature.getName()); //$NON-NLS-1$
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static TypeItem simpleType(String name) {
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(name);
        return type;
    }
}
