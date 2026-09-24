package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Квалификаторы внутри составного типа (issue androman/codepilot1c#106).
 *
 * <p>Дефект: {@code createTypeDescription} брал квалификаторы только у первого элемента списка. У
 * {@code {"types": ["CatalogRef.ДополнительныеУслуги", "String(10)", "CatalogRef.СобытияОбработки"]}} первым идёт
 * ссылка, и длина строки не писалась: в .mdo ложился голый {@code <types>String</types>} — неограниченная строка,
 * которую нельзя группировать в запросе. {@code add_metadata_child} создавал ресурс молча, {@code update_metadata}
 * children_ops отвечал «updated successfully». Так же терялись точность числа и состав даты не на первом месте, а
 * квалификаторы рядом с {@code types} выбрасывались целиком.</p>
 *
 * <p>Тест бьёт рефлексией по настоящим {@code applyDefaultTypeIfNeeded} (путь {@code add_metadata_child}) и
 * {@code setFeatureValue} (путь {@code update_metadata}) с реквизитом из фабрики EMF, транзакцией-заглушкой и заранее
 * разрешёнными типами; на классы фикса не ссылается — один бинарник идёт против сборки до фикса и после. Разрешение
 * простого типа по одиночному пути идёт через {@code TypeProviderService}, которого вне OSGi нет, поэтому этот
 * единственный тест вне OSGi пропускается; составной тип разрешается из заранее разрешённых типов и идёт везде.</p>
 */
public class EdtMetadataServiceCompositeTypeTest {

    static final String REQUIRE_TYPE_RESOLUTION = "codepilot.eval.requireTypeResolution"; //$NON-NLS-1$

    private static final String REF_SERVICES = "CatalogRef.ДополнительныеУслуги"; //$NON-NLS-1$
    private static final String REF_EVENTS = "CatalogRef.СобытияОбработки"; //$NON-NLS-1$

    // --- сам симптом: add_metadata_child ------------------------------------

    @Test
    public void issuePayloadKeepsStringLength() throws Exception {
        TypeDescription type = addChild(props("type", Map.of("types", List.of(REF_SERVICES, "String(10)", REF_EVENTS)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(3, type.getTypes().size());
        assertNotNull("длина строки потеряна: неограниченная строка", type.getStringQualifiers()); //$NON-NLS-1$
        assertEquals(10, type.getStringQualifiers().getLength());
    }

    @Test
    public void stringAfterNumberKeepsBothQualifiers() throws Exception {
        TypeDescription type = addChild(props("type", List.of("Number(10,2)", "String(10)"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("квалификаторы числа потеряны", type.getNumberQualifiers()); //$NON-NLS-1$
        assertEquals(10, type.getNumberQualifiers().getPrecision());
        assertEquals(2, type.getNumberQualifiers().getScale());
        assertNotNull("длина строки потеряна", type.getStringQualifiers()); //$NON-NLS-1$
        assertEquals(10, type.getStringQualifiers().getLength());
    }

    @Test
    public void numberAfterStringKeepsBothQualifiers() throws Exception {
        TypeDescription type = addChild(props("type", List.of("String(10)", "Number(10,2)"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("длина строки потеряна", type.getStringQualifiers()); //$NON-NLS-1$
        assertEquals(10, type.getStringQualifiers().getLength());
        assertNotNull("квалификаторы числа потеряны", type.getNumberQualifiers()); //$NON-NLS-1$
        assertEquals(10, type.getNumberQualifiers().getPrecision());
        assertEquals(2, type.getNumberQualifiers().getScale());
    }

    @Test
    public void dateNotFirstKeepsDateFractions() throws Exception {
        TypeDescription type = addChild(props("type", List.of(REF_SERVICES, //$NON-NLS-1$
                Map.of("type", "Date", "dateQualifiers", Map.of("dateFractions", "Date"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertNotNull("состав даты потерян", type.getDateQualifiers()); //$NON-NLS-1$
        assertEquals(DateFractions.DATE, type.getDateQualifiers().getDateFractions());
    }

    @Test
    public void bareStringNotFirstGetsDefaultLength() throws Exception {
        // Как у одиночного типа: строка без длины — 150, а не неограниченная.
        TypeDescription type = addChild(props("type", List.of(REF_SERVICES, "String"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("строка без длины стала неограниченной", type.getStringQualifiers()); //$NON-NLS-1$
        assertEquals(150, type.getStringQualifiers().getLength());
    }

    // --- честный отказ вместо молчаливой потери ------------------------------

    @Test
    public void sameKindTwiceIsRefused() throws Exception {
        assertRefused(props("type", List.of("String(10)", "String(20)")), "String"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void qualifiersNextToTypesAreRefused() throws Exception {
        // Какому элементу относится длина, не сказано; раньше она выбрасывалась молча.
        assertRefused(props("type", Map.of( //$NON-NLS-1$
                "types", List.of("String", "Number"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "stringQualifiers", Map.of("length", 10))), "composite"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // --- что работало и обязано работать дальше ------------------------------

    @Test
    public void singleInlineStringStillWorks() throws Exception {
        // Одиночный тип идёт другим путём — через TypeProviderService, которого вне OSGi нет.
        assumeTypeResolutionAvailable();
        TypeDescription type = addChild(props("type", "String(10)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(10, type.getStringQualifiers().getLength());
    }

    @Test
    public void referencesOnlyCompositeGetsNoQualifiers() throws Exception {
        TypeDescription type = addChild(props("type", List.of(REF_SERVICES, REF_EVENTS))); //$NON-NLS-1$
        assertEquals(2, type.getTypes().size());
        assertNull(type.getStringQualifiers());
        assertNull(type.getNumberQualifiers());
        assertNull(type.getDateQualifiers());
    }

    @Test
    public void stringFirstCompositeStillKeepsLength() throws Exception {
        TypeDescription type = addChild(props("type", List.of("String(10)", REF_SERVICES))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(10, type.getStringQualifiers().getLength());
    }

    // --- update_metadata children_ops set {type: {types: [...]}} ------------

    @Test
    public void updateIssuePayloadKeepsStringLength() throws Exception {
        BasicFeature feature = newResource();
        setType(feature, Map.of("types", List.of(REF_SERVICES, "String(10)", REF_EVENTS))); //$NON-NLS-1$ //$NON-NLS-2$
        TypeDescription type = feature.getType();
        assertNotNull("тип не назначен", type); //$NON-NLS-1$
        assertEquals(3, type.getTypes().size());
        assertNotNull("«updated successfully», а длина строки потеряна", type.getStringQualifiers()); //$NON-NLS-1$
        assertEquals(10, type.getStringQualifiers().getLength());
    }

    // --- обвязка -------------------------------------------------------------

    /**
     * Пропускает тест, если {@code TypeProviderService} не инициализируется (нет платформы OSGi). Со свойством
     * {@value #REQUIRE_TYPE_RESOLUTION} (eval с заглушкой сервиса) пропуска нет: сломанная заглушка всплывёт ошибкой
     * теста, а не тихим зелёным.
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
        Assume.assumeTrue("TypeProviderService недоступен вне OSGi", //$NON-NLS-1$
                available);
    }

    private static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static BasicFeature newResource() {
        BasicFeature feature = MdClassFactory.eINSTANCE.createAccumulationRegisterResource();
        feature.setName("Операция"); //$NON-NLS-1$
        return feature;
    }

    private static TypeDescription addChild(Map<String, Object> properties) throws Exception {
        BasicFeature feature = newResource();
        applyDefaultType(feature, properties);
        TypeDescription type = feature.getType();
        assertNotNull("тип не назначен", type); //$NON-NLS-1$
        return type;
    }

    private static void assertRefused(Map<String, Object> properties, String expectedInMessage) throws Exception {
        try {
            applyDefaultType(newResource(), properties);
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, e.getCode());
            assertTrue("в тексте отказа нет «" + expectedInMessage + "»: " + e.getMessage(), //$NON-NLS-1$ //$NON-NLS-2$
                    e.getMessage().contains(expectedInMessage));
            return;
        }
        fail("ожидался отказ INVALID_PROPERTY_VALUE, а ресурс создан молча: " + properties); //$NON-NLS-1$
    }

    private static void applyDefaultType(BasicFeature feature, Map<String, Object> properties) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "applyDefaultTypeIfNeeded", //$NON-NLS-1$
                Configuration.class, MdObject.class, MetadataChildKind.class, Map.class, Map.class,
                IBmPlatformTransaction.class, String.class, String.class);
        invoke(method, null, feature, MetadataChildKind.RESOURCE, properties, preResolvedTypes(), transaction(),
                "AccumulationRegister.ЖурналВыработки", feature.getName()); //$NON-NLS-1$
    }

    private static void setType(BasicFeature feature, Object value) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod(
                "setFeatureValue", //$NON-NLS-1$
                Configuration.class, MdObject.class, String.class, Object.class,
                IBmPlatformTransaction.class, Map.class, String.class);
        invoke(method, null, feature, "type", value, transaction(), preResolvedTypes(), null); //$NON-NLS-1$
    }

    private static void invoke(Method method, Object... args) throws Exception {
        method.setAccessible(true);
        try {
            method.invoke(new EdtMetadataService(), args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static Map<String, TypeItem> preResolvedTypes() {
        Map<String, TypeItem> preResolved = new HashMap<>();
        for (String name : List.of("String", "Number", "Date", REF_SERVICES, REF_EVENTS)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            preResolved.put(name, simpleType(name));
        }
        return preResolved;
    }

    private static IBmPlatformTransaction transaction() {
        return (IBmPlatformTransaction) Proxy.newProxyInstance(
                EdtMetadataServiceCompositeTypeTest.class.getClassLoader(),
                new Class<?>[] {IBmPlatformTransaction.class},
                (proxy, method, args) -> "toTransactionObject".equals(method.getName()) && args != null && args.length == 1 //$NON-NLS-1$
                        ? args[0]
                        : null);
    }

    private static TypeItem simpleType(String name) {
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(name);
        return type;
    }
}
