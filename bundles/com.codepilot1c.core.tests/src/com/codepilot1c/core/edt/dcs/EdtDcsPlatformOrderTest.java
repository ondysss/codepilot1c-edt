package com.codepilot1c.core.edt.dcs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IFile;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;

/** Exercises complete DOM mutations because helper-only tests miss write order and source selection. */
public class EdtDcsPlatformOrderTest {
    private static final String PLATFORM = "http://v8.1c.ru/8.1/data-composition-system/schema"; //$NON-NLS-1$
    private static final String EDT_DT = "http://g5.1c.ru/v8/dt/data-composition-system/schema"; //$NON-NLS-1$
    private static final String TITLE = "<title><v8:item><v8:lang>ru</v8:lang>"
            + "<v8:content>Заголовок</v8:content></v8:item></title>"; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String VALUE_TYPE = "<valueType><v8:Type>xs:string</v8:Type></valueType>"; //$NON-NLS-1$
    private final EdtDcsService service = new EdtDcsService((EdtMetadataGateway) null);

    @Test
    public void newDatasetUsesPlatformOrderAndFalseFlags() throws Exception {
        MemoryFile file = platform(""); //$NON-NLS-1$
        DcsUpsertQueryDatasetResult result = upsert(file, "ИсточникДанных", false, false); //$NON-NLS-1$
        sample(file, "01-new-false"); //$NON-NLS-1$
        assertNames(file.root(), "dataSource", "dataSet"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(child(file.root(), "dataSet"), "name", "dataSource", "query", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "autoFillFields", "useQueryGroupIfPossible"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.autoFillAvailableFields());
        assertFalse(result.useQueryGroupIfPossible());
        assertTrue(result.created());
        assertFlags(file, false, false);
    }

    @Test
    public void trueFlagsAreOmitted() throws Exception {
        MemoryFile file = platform(""); //$NON-NLS-1$
        DcsUpsertQueryDatasetResult result = upsert(file, null, true, true);
        sample(file, "02-new-true"); //$NON-NLS-1$
        Element dataset = child(file.root(), "dataSet"); //$NON-NLS-1$
        assertNull(child(dataset, "autoFillAvailableFields")); //$NON-NLS-1$
        assertNull(child(dataset, "autoFillFields")); //$NON-NLS-1$
        assertNull(child(dataset, "useQueryGroupIfPossible")); //$NON-NLS-1$
        assertTrue(result.autoFillAvailableFields());
        assertTrue(result.useQueryGroupIfPossible());
        assertFlags(file, true, true);
    }

    @Test
    public void queryOnlyUpsertRepairsLegacyOrderAndFlag() throws Exception {
        MemoryFile file = platform(source("ИсточникДанных") + dataset("\n<name>Набор</name>\n"
                + "<query>SELECT 0</query>\n<dataSource>ИсточникДанных</dataSource>\n"
                + "<autoFillAvailableFields>false</autoFillAvailableFields>\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        DcsUpsertQueryDatasetResult result = upsert(file, null, null, null);
        sample(file, "03-repaired-legacy"); //$NON-NLS-1$
        assertNames(child(file.root(), "dataSet"), "name", "dataSource", "query", "autoFillFields"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertFalse(result.autoFillAvailableFields());
        assertFlags(file, false, true);
    }

    @Test
    public void requestedSourceIsCreatedInsteadOfUsingFirst() throws Exception {
        MemoryFile file = platform(source("ИсточникДанных1")); //$NON-NLS-1$
        DcsUpsertQueryDatasetResult result = upsert(file, "ВнешнийИсточник", null, null); //$NON-NLS-1$
        sample(file, "04-requested-source"); //$NON-NLS-1$
        assertEquals("ВнешнийИсточник", result.dataSource()); //$NON-NLS-1$
        assertNames(file.root(), "dataSource", "dataSource", "dataSet"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<Element> sources = children(file.root(), "dataSource"); //$NON-NLS-1$
        assertEquals("ИсточникДанных1", text(sources.get(0), "name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Local", text(sources.get(0), "dataSourceType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(sources.get(1), "name", "dataSourceType"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ВнешнийИсточник", text(sources.get(1), "name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Local", text(sources.get(1), "dataSourceType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(result.dataSource(), text(child(file.root(), "dataSet"), "dataSource")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Object and union data sets have their own content model: the query order must leave them as they are. */
    @Test
    public void nonQueryDataSetKeepsItsChildrenInPlace() throws Exception {
        MemoryFile file = platform(source("ИсточникДанных") + "<dataSet xsi:type=\"DataSetObject\">" //$NON-NLS-1$ //$NON-NLS-2$
                + "<objectName>Таблица</objectName><name>НаборОбъект</name>" //$NON-NLS-1$
                + "<dataSource>ИсточникДанных</dataSource></dataSet>"); //$NON-NLS-1$
        Element dataSet = child(file.root(), "dataSet"); //$NON-NLS-1$
        Class<?> dialectType = nested("DcsDialect"); //$NON-NLS-1$
        Object dialect = invoke(null, dialectType, "of", new Class<?>[] {Element.class}, file.root()); //$NON-NLS-1$
        invoke(service, "normalizeChildOrder", new Class<?>[] {Element.class, dialectType}, dataSet, dialect); //$NON-NLS-1$
        assertNames(dataSet, "objectName", "name", "dataSource"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void omittedSourceKeepsExistingDatasetBinding() throws Exception {
        MemoryFile file = platform(source("Источник1") + source("Источник2") + boundDataset("Источник2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DcsUpsertQueryDatasetResult result = upsert(file, null, null, null);
        sample(file, "05-preserved-source"); //$NON-NLS-1$
        assertEquals("Источник2", result.dataSource()); //$NON-NLS-1$
        assertEquals("Источник2", text(child(file.root(), "dataSet"), "dataSource")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(2, children(file.root(), "dataSource").size()); //$NON-NLS-1$
    }

    @Test
    public void parameterPropertiesFollowPlatformOrder() throws Exception {
        MemoryFile file = platform("<parameter><name>Параметр</name>" + TITLE + VALUE_TYPE + "</parameter>"); //$NON-NLS-1$ //$NON-NLS-2$
        mutate(file, "upsertExternalParameter", new DcsUpsertParameterRequest( //$NON-NLS-1$
                "P", "Report.R", "Параметр", "1", true, true, true, false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        sample(file, "06-parameter"); //$NON-NLS-1$
        assertNames(child(file.root(), "parameter"), "name", "title", "valueType", "useRestriction", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "expression", "valueListAllowed", "availableAsField", "denyIncompleteValues"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void calculatedFieldPropertiesFollowPlatformOrder() throws Exception {
        MemoryFile file = platform("<calculatedField><dataPath>Поле</dataPath>" + TITLE + "</calculatedField>"); //$NON-NLS-1$ //$NON-NLS-2$
        mutate(file, "upsertExternalCalculatedField", new DcsUpsertCalculatedFieldRequest( //$NON-NLS-1$
                "P", "Report.R", "Поле", "1", "2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        sample(file, "07-calculated-field"); //$NON-NLS-1$
        assertNames(child(file.root(), "calculatedField"), "dataPath", "expression", "title", "presentationExpression"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void designTimeDatasetStillUsesAttributes() throws Exception {
        MemoryFile file = new MemoryFile(schema(EDT_DT, "")); //$NON-NLS-1$
        upsert(file, "LegacySource", false, false); //$NON-NLS-1$
        Element dataset = child(file.root(), "dataSets"); //$NON-NLS-1$
        assertNames(file.root(), "dataSets"); //$NON-NLS-1$
        assertNames(dataset);
        assertEquals("Набор", dataset.getAttribute("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("SELECT 1", dataset.getAttribute("query")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("LegacySource", dataset.getAttribute("dataSource")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("false", dataset.getAttribute("autoFillAvailableFields")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("false", dataset.getAttribute("useQueryGroupIfPossible")); //$NON-NLS-1$ //$NON-NLS-2$
        upsert(file, null, null, null);
        assertEquals("false", child(file.root(), "dataSets").getAttribute("autoFillAvailableFields")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        upsert(file, null, true, true);
        assertEquals("true", child(file.root(), "dataSets").getAttribute("autoFillAvailableFields")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void platformProjectionUsesCanonicalFlagsAndDefaults() throws Exception {
        MemoryFile file = platform(source("S") + dataset("<name>Набор</name><dataSource>S</dataSource>"
                + "<query>SELECT 1</query><autoFillFields>false</autoFillFields>")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFlags(file, false, true);
        assertFlags(platform(boundDataset("S")), true, true); //$NON-NLS-1$
    }

    @Test
    public void nullFlagsPreserveCanonicalFalseAndExplicitTrueRemovesThem() throws Exception {
        MemoryFile file = platform(source("S") + dataset("<name>Набор</name><dataSource>S</dataSource>"
                + "<query>SELECT 1</query><autoFillFields>false</autoFillFields>"
                + "<useQueryGroupIfPossible>false</useQueryGroupIfPossible>")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        DcsUpsertQueryDatasetResult unchanged = upsert(file, null, null, null);
        assertFalse(unchanged.autoFillAvailableFields());
        assertFalse(unchanged.useQueryGroupIfPossible());
        upsert(file, null, true, true);
        assertNames(child(file.root(), "dataSet"), "name", "dataSource", "query"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFlags(file, true, true);
    }

    @Test
    public void explicitFlagOverridesLegacyFalse() throws Exception {
        MemoryFile file = platform(source("S") + dataset("<name>Набор</name><dataSource>S</dataSource>"
                + "<query>SELECT 1</query><autoFillAvailableFields>false</autoFillAvailableFields>")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        upsert(file, null, true, null);
        assertNames(child(file.root(), "dataSet"), "name", "dataSource", "query"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFlags(file, true, true);
    }

    @Test
    public void requestedSourceMatchesCaseInsensitivelyAndRepairsItsOrder() throws Exception {
        MemoryFile file = platform(source("First") + "<dataSource><connectionString>keep</connectionString>"
                + "<dataSourceType>Local</dataSourceType><name>Источник</name></dataSource>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DcsUpsertQueryDatasetResult result = upsert(file, "иСТОЧНИК", null, null); //$NON-NLS-1$
        assertEquals("Источник", result.dataSource()); //$NON-NLS-1$
        List<Element> sources = children(file.root(), "dataSource"); //$NON-NLS-1$
        assertEquals(2, sources.size());
        assertNames(sources.get(1), "name", "dataSourceType", "connectionString"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("keep", text(sources.get(1), "connectionString")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void missingCurrentSourceIsCreatedAndNewDatasetUsesFirstOrDefault() throws Exception {
        MemoryFile missing = platform(source("First") + boundDataset("Missing")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Missing", upsert(missing, null, null, null).dataSource()); //$NON-NLS-1$
        assertNames(missing.root(), "dataSource", "dataSource", "dataSet"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Missing", text(children(missing.root(), "dataSource").get(1), "name")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("First", upsert(platform(source("First") + source("Second")), null, null, null).dataSource()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("ИсточникДанных", upsert(platform(""), null, null, null).dataSource()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void datasetSortIsStableAndRepeatedWritesDoNotAddBlankLines() throws Exception {
        MemoryFile file = platform("\n" + source("S") + "\n" + dataset("\n<unknownB>B</unknownB>\n<query>SELECT 0</query>\n"
                + "<field><dataPath>Second</dataPath></field>\n<dataSource>S</dataSource>\n<name>Набор</name>\n"
                + "<field><dataPath>First</dataPath></field>\n<unknownA>A</unknownA>\n") + "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        upsert(file, null, null, null);
        Element dataset = child(file.root(), "dataSet"); //$NON-NLS-1$
        assertNames(dataset, "name", "field", "field", "dataSource", "query", "unknownB", "unknownA"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        assertEquals(List.of("Second", "First"), children(dataset, "field").stream() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .map(field -> text(field, "dataPath")).toList()); //$NON-NLS-1$
        String firstWrite = file.xml();
        assertFalse("indentation must not create blank lines", firstWrite.matches("(?s).*\\n[\\t ]*\\n.*")); //$NON-NLS-1$ //$NON-NLS-2$
        upsert(file, null, null, null);
        assertEquals(firstWrite, file.xml());
    }

    @Test
    public void parameterAndCalculatedNoOpRepairsAllKnownChildrenStably() throws Exception {
        MemoryFile parameter = platform("<parameter><unknownB>B</unknownB><use>Auto</use>"
                + "<denyIncompleteValues>false</denyIncompleteValues><inputParameters/>"
                + "<functionalOptionsParameter/><availableAsField>true</availableAsField><valueListAllowed>false</valueListAllowed>"
                + "<availableValue>second</availableValue><expression>1</expression><useRestriction>false</useRestriction>"
                + "<value/><valueType/><title/><availableValue>first</availableValue><name>Параметр</name><unknownA>A</unknownA></parameter>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        mutate(parameter, "upsertExternalParameter", new DcsUpsertParameterRequest( //$NON-NLS-1$
                "P", "Report.R", "Параметр", null, null, null, null, null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Element node = child(parameter.root(), "parameter"); //$NON-NLS-1$
        assertNames(node, "name", "title", "valueType", "value", "useRestriction", "expression", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "availableValue", "availableValue", "valueListAllowed", "availableAsField", "functionalOptionsParameter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "inputParameters", "denyIncompleteValues", "use", "unknownB", "unknownA"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals(List.of("second", "first"), children(node, "availableValue").stream().map(Element::getTextContent).toList()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        MemoryFile calculated = platform("<calculatedField><inputParameters/><valueType/><unknownB/>"
                + "<availableValue>second</availableValue><appearance/><orderExpression>1</orderExpression>"
                + "<presentationExpression>1</presentationExpression><useRestriction/><title/><expression>1</expression>"
                + "<availableValue>first</availableValue><dataPath>Поле</dataPath><unknownA/></calculatedField>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        mutate(calculated, "upsertExternalCalculatedField", new DcsUpsertCalculatedFieldRequest( //$NON-NLS-1$
                "P", "Report.R", "Поле", null, null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        node = child(calculated.root(), "calculatedField"); //$NON-NLS-1$
        assertNames(node, "dataPath", "expression", "title", "useRestriction", "presentationExpression", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "orderExpression", "appearance", "availableValue", "availableValue", "valueType", "inputParameters", "unknownB", "unknownA"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        assertEquals(List.of("second", "first"), children(node, "availableValue").stream().map(Element::getTextContent).toList()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private DcsUpsertQueryDatasetResult upsert(MemoryFile file, String source, Boolean autoFill, Boolean group)
            throws Exception {
        return (DcsUpsertQueryDatasetResult) mutate(file, "upsertExternalQueryDataset", //$NON-NLS-1$
                new DcsUpsertQueryDatasetRequest("P", "Report.R", "Набор", "SELECT 1", source, autoFill, group)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private Object mutate(MemoryFile file, String method, Object request) throws Exception {
        return invoke(service, method, new Class<?>[] {request.getClass(), nested("ExternalDcsSchema")}, //$NON-NLS-1$
                request, externalSchema(file));
    }

    private Object externalSchema(MemoryFile file) throws Exception {
        Element root = file.root();
        Class<?> dialectType = nested("DcsDialect"); //$NON-NLS-1$
        Object dialect = invoke(null, dialectType, "of", new Class<?>[] {Element.class}, root); //$NON-NLS-1$
        Constructor<?> constructor = nested("ExternalDcsSchema").getDeclaredConstructors()[0]; //$NON-NLS-1$
        constructor.setAccessible(true);
        List<Object> lists = new ArrayList<>();
        Class<?> kindType = nested("DcsNodeKind"); //$NON-NLS-1$
        for (Object kind : kindType.getEnumConstants()) {
            lists.add(invoke(service, "nodesFrom", new Class<?>[] {Element.class, kindType, dialectType}, root, kind, dialect)); //$NON-NLS-1$
        }
        // Settings variants are carried either as a list of nodes or as their count, depending on the
        // revision of the projection; the order tests do not depend on them.
        Object variants = constructor.getParameterTypes()[6] == int.class
                ? Integer.valueOf(((List<?>) lists.get(3)).size())
                : lists.get(3);
        return constructor.newInstance(file.file, "T", dialect, lists.get(0), lists.get(1), lists.get(2), variants); //$NON-NLS-1$
    }

    private void assertFlags(MemoryFile file, boolean autoFill, boolean group) throws Exception {
        Object schema = externalSchema(file);
        List<?> datasets = (List<?>) invoke(schema, "dataSets", new Class<?>[0]); //$NON-NLS-1$
        Object dataset = datasets.get(0);
        assertEquals(Boolean.toString(autoFill), invoke(dataset, "autoFillAvailableFields", new Class<?>[0])); //$NON-NLS-1$
        assertEquals(Boolean.toString(group), invoke(dataset, "useQueryGroupIfPossible", new Class<?>[0])); //$NON-NLS-1$
    }

    private static Class<?> nested(String name) throws Exception {
        return Class.forName(EdtDcsService.class.getName() + "$" + name); //$NON-NLS-1$
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return invoke(target, target.getClass(), name, types, args);
    }

    private static Object invoke(Object target, Class<?> type, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static MemoryFile platform(String body) {
        return new MemoryFile(schema(PLATFORM, body));
    }

    private static String schema(String namespace, String body) {
        return "<DataCompositionSchema xmlns=\"" + namespace + "\" xmlns:xsi=\"" //$NON-NLS-1$ //$NON-NLS-2$
                + XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI + "\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"" //$NON-NLS-1$
                + " xmlns:v8=\"http://v8.1c.ru/8.1/data/core\">" + body + "</DataCompositionSchema>"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String source(String name) {
        return "<dataSource><name>" + name + "</name><dataSourceType>Local</dataSourceType></dataSource>"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String dataset(String body) {
        return "<dataSet xsi:type=\"DataSetQuery\">" + body + "</dataSet>"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String boundDataset(String source) {
        return dataset("<name>Набор</name><dataSource>" + source + "</dataSource><query>SELECT 0</query>"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && (name == null || name.equals(element.getLocalName()))) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element child(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String text(Element parent, String name) {
        return child(parent, name).getTextContent();
    }

    private static void assertNames(Element parent, String... names) {
        assertEquals(List.of(names), children(parent, null).stream().map(Element::getLocalName).toList());
    }

    private static void sample(MemoryFile file, String name) throws Exception {
        String directory = System.getProperty("dcs.samples.dir"); //$NON-NLS-1$
        if (directory != null) {
            Path output = Path.of(directory);
            Files.createDirectories(output);
            Files.write(output.resolve(name + ".xml"), file.bytes); //$NON-NLS-1$
        }
    }

    private static final class MemoryFile {
        private byte[] bytes;
        private final IFile file;

        private MemoryFile(String xml) {
            bytes = xml.getBytes(StandardCharsets.UTF_8);
            file = (IFile) Proxy.newProxyInstance(IFile.class.getClassLoader(), new Class<?>[] {IFile.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getContents" -> new ByteArrayInputStream(bytes); //$NON-NLS-1$
                        case "setContents", "create" -> { //$NON-NLS-1$ //$NON-NLS-2$
                            bytes = ((InputStream) args[0]).readAllBytes();
                            yield null;
                        }
                        case "exists" -> true; //$NON-NLS-1$
                        case "refreshLocal", "getParent" -> null; //$NON-NLS-1$ //$NON-NLS-2$
                        case "getProjectRelativePath" -> org.eclipse.core.runtime.Path.fromOSString( //$NON-NLS-1$
                                "src/Reports/R/Templates/T/Template.dcs"); //$NON-NLS-1$
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) {
                return null;
            }
            return java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(type, 1), 0);
        }

        private String xml() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private Element root() throws Exception {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        }
    }
}
