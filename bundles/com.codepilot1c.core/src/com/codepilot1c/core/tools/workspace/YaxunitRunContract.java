package com.codepilot1c.core.tools.workspace;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Input and result invariants shared by the MCP runner and its negative tests. */
final class YaxunitRunContract {
    private static final String IDENTIFIER = "[\\p{L}_][\\p{L}\\p{N}_]*";
    private static final Pattern CASE = Pattern.compile(IDENTIFIER + "\\." + IDENTIFIER + "(?:\\([^\\r\\n]*\\))?");

    record Selection(JsonObject filter, Set<String> expected, Map<String,String> contexts, Integer count) { }

    static Selection selection(String filters, Object tests, Object contexts, Object count) {
        Set<String> modules = new LinkedHashSet<>();
        if (filters != null && !filters.isBlank()) {
            for (String part : filters.split(",", -1)) {
                String name = part.trim();
                require(name.matches(IDENTIFIER), "Invalid module filter: " + name);
                require(modules.add(name), "Duplicate module filter: " + name);
            }
        }
        Set<String> expected = new LinkedHashSet<>();
        Set<String> methods = new LinkedHashSet<>();
        if (tests != null) {
            require(tests instanceof List<?> && !((List<?>) tests).isEmpty(), "tests must be a nonempty array");
            Set<String> derivedModules = new LinkedHashSet<>();
            for (Object value : (List<?>) tests) {
                require(value instanceof String && CASE.matcher((String)value).matches(), "Invalid test case: " + value);
                String name = (String)value;
                require(expected.add(name), "Duplicate expected test: " + name);
                derivedModules.add(name.substring(0, name.indexOf('.')));
                methods.add(name.contains("(") ? name.substring(0, name.indexOf('(')) : name);
            }
            require(modules.isEmpty() || modules.equals(derivedModules), "Module filters differ from exact tests");
            modules = derivedModules;
        }
        Map<String,String> expectedContexts = new LinkedHashMap<>();
        if (contexts != null) {
            require(contexts instanceof Map<?,?> && !expected.isEmpty(), "expected_contexts requires exact tests");
            for (Map.Entry<?,?> entry : ((Map<?,?>)contexts).entrySet()) {
                require(entry.getKey() instanceof String && entry.getValue() instanceof String, "Invalid expected context");
                String value = (String)entry.getValue();
                require(value.equals("Сервер") || value.equals("КлиентУправляемоеПриложение"), "Unsupported test context");
                expectedContexts.put((String)entry.getKey(), value);
            }
            require(expectedContexts.keySet().equals(expected), "Contexts must cover every expected case exactly");
        }
        Integer expectedCount = null;
        if (count != null) {
            require(count instanceof Number, "expected_count must be an integer");
            double number = ((Number)count).doubleValue();
            require(number > 0 && number <= Integer.MAX_VALUE && number == Math.rint(number), "Invalid expected_count");
            expectedCount = (int)number;
            require(expected.isEmpty() || expectedCount == expected.size(), "Expected count differs from exact tests");
        }
        JsonObject filter = new JsonObject();
        JsonArray moduleArray = new JsonArray();
        modules.forEach(moduleArray::add);
        if (!modules.isEmpty()) filter.add("modules", moduleArray);
        if (!methods.isEmpty()) {
            JsonArray methodArray = new JsonArray();
            methods.forEach(methodArray::add);
            filter.add("tests", methodArray);
        }
        return new Selection(filter, expected, expectedContexts, expectedCount);
    }

    static void validateCommand(List<String> command, File configFile) {
        List<String> folded = command.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
        require(folded.contains("/testmanager"), "EDT command is missing /TESTMANAGER");
        require(folded.contains("/runmodemanagedapplication") && !folded.contains("/runmodeordinaryapplication"),
                "YAxUnit requires managed application mode");
        require(folded.stream().filter("/c"::equals).count() == 1, "Expected exactly one /C startup command");
        int position = folded.indexOf("/c");
        require(position + 1 < command.size() && command.get(position + 1).equals("RunUnitTests=" + configFile.getAbsolutePath()),
                "EDT changed the YAxUnit startup command");
        require(isAscii(configFile.toPath()), "YAxUnit runtime configuration path must be ASCII");
    }

    static boolean isAscii(Path path) {
        return path.toAbsolutePath().toString().chars().allMatch(value -> value < 128);
    }

    static List<String> verifyReport(File file, Selection selection, int aggregateCount) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        NodeList cases = factory.newDocumentBuilder().parse(file).getElementsByTagName("testcase");
        Set<String> observed = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        if (cases.getLength() == 0) problems.add("No tests executed");
        if (cases.getLength() != aggregateCount) problems.add("JUnit totals differ from actual testcase elements");
        if (selection.count() != null && cases.getLength() != selection.count()) problems.add("Unexpected test count");
        for (int index = 0; index < cases.getLength(); index++) {
            Element test = (Element)cases.item(index);
            String owner = test.getAttribute("classname");
            String module = owner.contains(".") ? owner.substring(0, owner.indexOf('.')) : owner;
            String name = module + "." + test.getAttribute("name");
            if (selection.filter().has("modules")) {
                boolean allowed = false;
                for (var value : selection.filter().getAsJsonArray("modules")) {
                    if (value.getAsString().equals(module)) allowed = true;
                }
                if (!allowed) problems.add("Unexpected module: " + module);
            }
            if (!observed.add(name)) problems.add("Duplicate test execution: " + name);
            if (test.getElementsByTagName("failure").getLength() + test.getElementsByTagName("error").getLength()
                    + test.getElementsByTagName("skipped").getLength() > 0) problems.add("Failed or skipped test: " + name);
            if (selection.contexts().containsKey(name)
                    && !selection.contexts().get(name).equals(test.getAttribute("context"))) problems.add("Unexpected context: " + name);
        }
        if (!selection.expected().isEmpty()) {
            for (String name : selection.expected()) if (!observed.contains(name)) problems.add("Missing test: " + name);
            for (String name : observed) if (!selection.expected().contains(name)) problems.add("Unexpected test: " + name);
        }
        return problems;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
