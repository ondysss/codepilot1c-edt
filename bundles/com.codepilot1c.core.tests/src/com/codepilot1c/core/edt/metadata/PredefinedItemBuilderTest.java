package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefined;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * Paired eval for {@link PredefinedItemBuilder}.
 *
 * <p>Every positive assertion here is matched by a negative one: a checker that cannot refuse is
 * indistinguishable from a checker that was never run. The fixture mirrors
 * a real event catalog of a working configuration (string codes, zero-padded to nine, thirteen
 * items) because the auto-numbering rule is only meaningful against a real numbering scheme.</p>
 *
 * <p>Plain JUnit over the EMF model: no OSGi, no infobase, no workspace - the builder is pure
 * model code, so the whole contract is checkable without a running EDT.</p>
 */
public class PredefinedItemBuilderTest {

    private static final String[] EXISTING = {
            "Подтверждение", "ПодборПозиции", "ЗавершениеПодбора", "НачалоПодбора", "НачалоУпаковки",
            "ЗавершениеУпаковки", "ДобавлениеМеста", "Создан", "Отгрузка", "ПриемкаТовара",
            "ПеремещениеТовара", "ПеремещениеЗаказа", "ВозвратТовара"
    };

    // ------------------------------------------------------------------ positive

    @Test
    public void createsPredefinedItemWithNameDescriptionAndNextCode() {
        Catalog catalog = eventCatalog();

        EObject created = PredefinedItemBuilder.create(
                catalog,
                new PredefinedItemBuilder.Descriptor("РасформированиеМеста", "Расформирование места"));

        assertEquals("РасформированиеМеста", PredefinedItemBuilder.nameOf(created));
        assertEquals("000000014", PredefinedItemBuilder.codeOf(created));
        assertEquals("Расформирование места", ((CatalogPredefinedItem) created).getDescription());
        assertSame(created, PredefinedItemBuilder.findByName(catalog, "РасформированиеМеста"));
        assertEquals(EXISTING.length + 1, PredefinedItemBuilder.allItems(catalog).size());
    }

    @Test
    public void assignsOwnIdBecauseTheMetamodelRequiresIt() {
        Catalog catalog = eventCatalog();

        CatalogPredefinedItem created = (CatalogPredefinedItem) PredefinedItemBuilder.create(
                catalog, new PredefinedItemBuilder.Descriptor("РасформированиеМеста", "Расформирование места"));

        // id is required by the metamodel (lowerBound=1) and unset on a fresh item. The uuid_check
        // gate would NOT catch the omission - it matches empty values as uuid="" literally, and
        // this attribute is named id - so the defect would be silent. That is why it is asserted here.
        assertNotNull(created.getId());
    }

    @Test
    public void writesCatalogCodeAsStringValueForStringCodedCatalog() {
        Catalog catalog = eventCatalog();

        CatalogPredefinedItem created = (CatalogPredefinedItem) PredefinedItemBuilder.create(
                catalog, new PredefinedItemBuilder.Descriptor("РасформированиеМеста", "Расформирование места"));

        assertTrue("code must be a StringValue for codeType=String, was "
                + (created.getCode() == null ? null : created.getCode().getClass().getName()),
                created.getCode() instanceof StringValue);
    }

    @Test
    public void createsPredefinedContainerWhenTheCatalogHasNone() {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Пустой");
        catalog.setCodeLength(9);
        catalog.setCodeType(CatalogCodeType.STRING);
        assertNull(catalog.getPredefined());

        PredefinedItemBuilder.create(catalog, new PredefinedItemBuilder.Descriptor("Первый", "Первый"));

        assertNotNull(catalog.getPredefined());
        assertEquals(1, catalog.getPredefined().getItems().size());
    }

    @Test
    public void nestsUnderAnExistingPredefinedFolder() {
        Catalog catalog = eventCatalog();
        CatalogPredefinedItem folder = (CatalogPredefinedItem) PredefinedItemBuilder.create(
                catalog,
                new PredefinedItemBuilder.Descriptor("Упаковка", "Упаковка", null, false, Boolean.TRUE, null));

        EObject nested = PredefinedItemBuilder.create(
                catalog,
                new PredefinedItemBuilder.Descriptor(
                        "РасформированиеМеста", "Расформирование места", null, false, null, "Упаковка"));

        assertEquals(1, folder.getContent().size());
        assertSame(nested, folder.getContent().get(0));
        // Nested items must still be visible to the duplicate check and to the post-verify.
        assertSame(nested, PredefinedItemBuilder.findByName(catalog, "РасформированиеМеста"));
    }

    @Test
    public void honoursExplicitCodeIncludingAnEmptyOne() {
        Catalog withCode = eventCatalog();
        EObject explicit = PredefinedItemBuilder.create(
                withCode,
                new PredefinedItemBuilder.Descriptor("Ручной", "Ручной", "000000099", true, null, null));
        assertEquals("000000099", PredefinedItemBuilder.codeOf(explicit));

        Catalog blank = eventCatalog();
        EObject empty = PredefinedItemBuilder.create(
                blank, new PredefinedItemBuilder.Descriptor("БезКода", "Без кода", "", true, null, null));
        assertNull(PredefinedItemBuilder.codeOf(empty));
    }

    @Test
    public void kindIsReachableFromTheToolAlias() {
        assertEquals(MetadataChildKind.PREDEFINED_ITEM, MetadataChildKind.fromString("PredefinedItem"));
        assertEquals(MetadataChildKind.PREDEFINED_ITEM, MetadataChildKind.fromString("predefined_item"));
        assertEquals(MetadataChildKind.PREDEFINED_ITEM, MetadataChildKind.fromString("предопределенныйЭлемент"));
        assertEquals("PredefinedItem", MetadataChildKind.PREDEFINED_ITEM.getDisplayName());
    }

    // ------------------------------------------------------------------ negative

    @Test
    public void refusesADuplicateName() {
        Catalog catalog = eventCatalog();
        expect(MetadataOperationCode.METADATA_ALREADY_EXISTS,
                () -> PredefinedItemBuilder.create(
                        catalog, new PredefinedItemBuilder.Descriptor("Отгрузка", "Отгрузка")));
    }

    @Test
    public void refusesADuplicateNameInAnotherCase() {
        // 1C names are case-insensitive: "отгрузка" and "Отгрузка" are the same predefined item,
        // and a case-sensitive check would let the configuration be loaded with a collision.
        Catalog catalog = eventCatalog();
        expect(MetadataOperationCode.METADATA_ALREADY_EXISTS,
                () -> PredefinedItemBuilder.create(
                        catalog, new PredefinedItemBuilder.Descriptor("отгрузка", "Отгрузка")));
    }

    @Test
    public void refusesAnInvalidName() {
        Catalog catalog = eventCatalog();
        expect(MetadataOperationCode.INVALID_METADATA_NAME,
                () -> PredefinedItemBuilder.create(
                        catalog, new PredefinedItemBuilder.Descriptor("Расформирование места", "x")));
    }

    @Test
    public void refusesAnOwnerThatHasNoPredefinedItems() {
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        register.setName("СведенияОСобытиях");
        expect(MetadataOperationCode.INVALID_METADATA_KIND,
                () -> PredefinedItemBuilder.create(register, new PredefinedItemBuilder.Descriptor("X", "X")));
        assertTrue(PredefinedItemBuilder.supports(MdClassFactory.eINSTANCE.createCatalog()));
        assertTrue(!PredefinedItemBuilder.supports(register));
    }

    @Test
    public void refusesAMissingNestingParent() {
        Catalog catalog = eventCatalog();
        expect(MetadataOperationCode.METADATA_NOT_FOUND,
                () -> PredefinedItemBuilder.create(
                        catalog,
                        new PredefinedItemBuilder.Descriptor("Новый", "Новый", null, false, null, "НетТакого")));
    }

    @Test
    public void refusesNestingUnderANonFolder() {
        Catalog catalog = eventCatalog();
        expect(MetadataOperationCode.INVALID_METADATA_CHANGE,
                () -> PredefinedItemBuilder.create(
                        catalog,
                        new PredefinedItemBuilder.Descriptor("Новый", "Новый", null, false, null, "Отгрузка")));
    }

    @Test
    public void leavesTheCodeEmptyRatherThanGuessWhenSiblingsAreNotNumbered() {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Буквенный");
        catalog.setCodeLength(9);
        catalog.setCodeType(CatalogCodeType.STRING);
        CatalogPredefined predefined = MdClassFactory.eINSTANCE.createCatalogPredefined();
        catalog.setPredefined(predefined);
        predefined.getItems().add(item("Первый", "Первый", "АБВ"));

        EObject created = PredefinedItemBuilder.create(
                catalog, new PredefinedItemBuilder.Descriptor("Второй", "Второй"));

        assertNull(PredefinedItemBuilder.codeOf(created));
    }

    @Test
    public void nextCodeSkipsPastTheHighestSiblingNotTheCount() {
        // A "count + 1" implementation looks right on a dense sequence and silently collides on a
        // sparse one, so the fixture is deliberately sparse.
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Разреженный");
        catalog.setCodeLength(9);
        catalog.setCodeType(CatalogCodeType.STRING);
        CatalogPredefined predefined = MdClassFactory.eINSTANCE.createCatalogPredefined();
        catalog.setPredefined(predefined);
        predefined.getItems().add(item("Первый", "Первый", "000000001"));
        predefined.getItems().add(item("Сотый", "Сотый", "000000100"));

        EObject created = PredefinedItemBuilder.create(
                catalog, new PredefinedItemBuilder.Descriptor("Новый", "Новый"));

        assertEquals("000000101", PredefinedItemBuilder.codeOf(created));
    }

    // ------------------------------------------------------------------ fixture

    private static Catalog eventCatalog() {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("СобытияОбработки");
        catalog.setCodeLength(9);
        catalog.setDescriptionLength(25);
        catalog.setCodeType(CatalogCodeType.STRING);
        catalog.setHierarchical(true);
        CatalogPredefined predefined = MdClassFactory.eINSTANCE.createCatalogPredefined();
        catalog.setPredefined(predefined);
        for (int i = 0; i < EXISTING.length; i++) {
            predefined.getItems().add(item(EXISTING[i], EXISTING[i], String.format("%09d", i + 1)));
        }
        return catalog;
    }

    private static CatalogPredefinedItem item(String name, String description, String code) {
        CatalogPredefinedItem item = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        item.setName(name);
        item.setDescription(description);
        StringValue value = McoreFactory.eINSTANCE.createStringValue();
        value.setValue(code);
        item.setCode(value);
        return item;
    }

    private static void expect(MetadataOperationCode expected, Runnable body) {
        try {
            body.run();
        } catch (MetadataOperationException e) {
            assertEquals("wrong failure code: " + e.getMessage(), expected, e.getCode());
            return;
        }
        fail("expected " + expected + ", but the call succeeded");
    }

    /** Keeps the unused-import checker honest about the list type used by the flattener. */
    @SuppressWarnings("unused")
    private static int size(List<EObject> items) {
        return items.size();
    }
}
