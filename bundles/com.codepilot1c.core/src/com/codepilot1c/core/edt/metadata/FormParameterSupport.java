package com.codepilot1c.core.edt.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormParameter;
import com._1c.g5.v8.dt.mcore.TypeDescription;

/** Formal input parameters, distinct from form data attributes and visual items. */
final class FormParameterSupport {
    private FormParameterSupport() { }

    static boolean supports(String op) {
        return List.of("addparameter", "setparameter", "removeparameter").contains(normalize(op));
    }

    /** Executed inside the caller's BM write transaction; batch failure rolls back all operations. */
    static void apply(Form form, Map<String, Object> operation,
            BiConsumer<FormParameter, Object> typeAssigner) {
        String op = normalize(String.valueOf(operation.get("op")));
        if (!supports(op)) {
            throw invalid("Unsupported parameter operation: " + op);
        }
        for (String key : operation.keySet()) {
            if (!List.of("op", "name", "set", "type", "keyParameter", "key_parameter", "comment").contains(key)) {
                throw invalid("Unsupported parameter operation field: " + key);
            }
        }
        Object nameValue = operation.get("name");
        if (!(nameValue instanceof String name) || !MetadataNameValidator.isValidName(name)) {
            throw invalid("Parameter operation requires a valid name");
        }
        FormParameter existing = form.getParameters().stream()
                .filter(p -> name.equalsIgnoreCase(p.getName())).findFirst().orElse(null);
        boolean adding = "addparameter".equals(op);
        if (adding && existing != null) {
            throw new MetadataOperationException(MetadataOperationCode.METADATA_ALREADY_EXISTS,
                    "Form parameter already exists: " + name, false);
        }
        if (!adding && existing == null) {
            throw new MetadataOperationException(MetadataOperationCode.METADATA_NOT_FOUND,
                    "Form parameter not found: " + name, false);
        }
        if ("removeparameter".equals(op)) {
            if (operation.size() != 2) {
                throw invalid("remove_parameter accepts only op and name");
            }
            form.getParameters().remove(existing);
            return;
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        if (operation.containsKey("set")) {
            if (!(operation.get("set") instanceof Map<?, ?> set)) {
                throw invalid("Parameter set must be an object");
            }
            for (var entry : set.entrySet()) {
                patch.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        for (String key : List.of("type", "keyParameter", "key_parameter", "comment")) {
            if (operation.containsKey(key)) {
                if (patch.containsKey(key)) {
                    throw invalid("Duplicate parameter property: " + key);
                }
                patch.put(key, operation.get(key));
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (var entry : patch.entrySet()) {
            String key = normalize(entry.getKey());
            if (!List.of("type", "keyparameter", "comment").contains(key)) {
                throw invalid("Unsupported parameter property: " + entry.getKey());
            }
            if (values.containsKey(key)) {
                throw invalid("Duplicate parameter property: " + entry.getKey());
            }
            Object value = entry.getValue();
            if (value == null || ("keyparameter".equals(key) && !(value instanceof Boolean))
                    || ("comment".equals(key) && !(value instanceof String))) {
                throw invalid("Invalid parameter property: " + entry.getKey());
            }
            if ("type".equals(key) && ((value instanceof String text && text.isBlank())
                    || (value instanceof List<?> list && list.isEmpty())
                    || (value instanceof Map<?, ?> map && map.isEmpty()))) {
                throw invalid("Parameter type must not be empty");
            }
            values.put(key, value);
        }
        if (!adding && values.isEmpty()) {
            throw invalid("set_parameter requires a non-empty set");
        }
        FormParameter parameter = adding ? FormFactory.eINSTANCE.createFormParameter() : existing;
        TypeDescription oldType = parameter.getValueType();
        if (adding) {
            parameter.setName(name);
            // TypeProviderService needs the real form containment context.
            form.getParameters().add(parameter);
        }
        try {
            if (values.containsKey("type")) {
                typeAssigner.accept(parameter, values.get("type"));
            }
            if (values.containsKey("keyparameter")) {
                parameter.setKeyParameter((Boolean) values.get("keyparameter"));
            }
            if (values.containsKey("comment")) {
                parameter.setComment((String) values.get("comment"));
            }
        } catch (RuntimeException e) {
            if (adding) {
                form.getParameters().remove(parameter);
            } else {
                parameter.setValueType(oldType);
            }
            throw e;
        }
    }

    static List<Map<String, Object>> describe(Form form) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (FormParameter parameter : form.getParameters()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", parameter.getName());
            entry.put("keyParameter", parameter.isKeyParameter());
            entry.put("comment", parameter.getComment());
            TypeDescription type = parameter.getValueType();
            entry.put("types", type == null ? List.of()
                    : type.getTypes().stream().map(t -> t.getName()).toList());
            if (type != null && type.getStringQualifiers() != null) {
                entry.put("stringLength", type.getStringQualifiers().getLength());
                entry.put("stringFixed", type.getStringQualifiers().isFixed());
            }
            if (type != null && type.getNumberQualifiers() != null) {
                entry.put("numberPrecision", type.getNumberQualifiers().getPrecision());
                entry.put("numberScale", type.getNumberQualifiers().getScale());
                entry.put("numberNonNegative", type.getNumberQualifiers().isNonNegative());
            }
            if (type != null && type.getDateQualifiers() != null) {
                entry.put("dateFractions", type.getDateQualifiers().getDateFractions().getLiteral());
            }
            result.add(entry);
        }
        return result;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace("_", "");
    }

    private static MetadataOperationException invalid(String message) {
        return new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE, message, false);
    }
}
