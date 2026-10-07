package com.codepilot1c.core.edt.rights;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.metadata.dbview.Table;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.Rls;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/** Native role restrictions. An empty field list addresses the other, unlisted fields. */
final class RlsRules {

    private RlsRules() { }

    static Map<String, DbViewFieldDef> fieldIndex(List<DbViewFieldDef> fields) {
        Map<String, DbViewFieldDef> result = new LinkedHashMap<>();
        collectFields(fields, "", "", result); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    private static void collectFields(List<DbViewFieldDef> fields, String prefix, String prefixRu,
            Map<String, DbViewFieldDef> result) {
        for (DbViewFieldDef field : fields) {
            String name = prefix + field.getName();
            String nameRu = prefixRu + (field.getNameRu() == null ? field.getName() : field.getNameRu());
            if (field instanceof Table table) {
                collectFields(table.getFields(), name + ".", nameRu + ".", result); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                putField(result, name, field);
                putField(result, nameRu, field);
            }
        }
    }

    private static void putField(Map<String, DbViewFieldDef> index, String name, DbViewFieldDef field) {
        String key = name.toLowerCase(Locale.ROOT);
        DbViewFieldDef previous = index.putIfAbsent(key, field);
        if (previous != null && previous != field) {
            throw invalid("Ambiguous RLS field: " + name); //$NON-NLS-1$
        }
    }

    static String fieldName(DbViewFieldDef field, boolean russian) {
        String name = russian && field.getNameRu() != null ? field.getNameRu() : field.getName();
        if (field.eContainer() instanceof DbViewFieldDef parent) {
            return fieldName(parent, russian) + "." + name; //$NON-NLS-1$
        }
        return name;
    }

    /** Resolve every rule before changing the existing model, including non-BM callers. */
    static void replace(ObjectRight right, Object rawRules, Map<String, DbViewFieldDef> fields) {
        if (right == null || right.getValue() != RightValue.SET) {
            throw invalid("RLS requires an explicitly granted right; use set_right first"); //$NON-NLS-1$
        }
        String rightName = right.getRight().getName();
        if (!Set.of("Read", "Insert", "Update", "Delete").contains(rightName)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            throw invalid("RLS is supported only for Read, Insert, Update and Delete"); //$NON-NLS-1$
        }
        if (!(rawRules instanceof List<?> rules) || rules.isEmpty()) {
            throw invalid("restrictions must be a nonempty array; use clear_rls to remove restrictions"); //$NON-NLS-1$
        }
        List<Rls> replacements = new ArrayList<>();
        Set<DbViewFieldDef> used = new LinkedHashSet<>();
        boolean otherFieldsUsed = false;
        for (Object rawRule : rules) {
            if (!(rawRule instanceof Map<?, ?> rule)) {
                throw invalid("Each RLS restriction must be an object"); //$NON-NLS-1$
            }
            if (!rule.keySet().equals(Set.of("fields", "condition"))) { //$NON-NLS-1$ //$NON-NLS-2$
                throw invalid("Each restriction must contain exactly fields and condition"); //$NON-NLS-1$
            }
            if (!(rule.get("condition") instanceof String condition) || condition.isBlank()) { //$NON-NLS-1$
                throw invalid("RLS condition must be a nonblank string"); //$NON-NLS-1$
            }
            if (!(rule.get("fields") instanceof List<?> names)) { //$NON-NLS-1$
                throw invalid("RLS fields must be an array; [] addresses the other fields"); //$NON-NLS-1$
            }
            if (!"Read".equals(rightName) && !names.isEmpty()) { //$NON-NLS-1$
                throw invalid("Field restrictions are supported only for Read"); //$NON-NLS-1$
            }
            Rls replacement = RightsFactory.eINSTANCE.createRls();
            replacement.setCondition(condition);
            if (names.isEmpty()) {
                if (otherFieldsUsed) {
                    throw invalid("Only one restriction may address the other fields"); //$NON-NLS-1$
                }
                otherFieldsUsed = true;
            }
            for (Object rawName : names) {
                if (!(rawName instanceof String name) || name.isBlank()) {
                    throw invalid("Every RLS field must be a nonblank string"); //$NON-NLS-1$
                }
                DbViewFieldDef field = fields.get(name.toLowerCase(Locale.ROOT));
                if (field == null) {
                    throw invalid("Unknown RLS field: " + name + "; inspect availableRlsFields first"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (!used.add(field)) {
                    throw invalid("An RLS field may occur in only one restriction: " + name); //$NON-NLS-1$
                }
                replacement.getFields().add(field);
            }
            replacements.add(replacement);
        }
        if (!otherFieldsUsed) {
            throw invalid("Include a restriction with fields:[] to define access to the other fields explicitly"); //$NON-NLS-1$
        }
        right.getRestrictionsByCondition().clear();
        right.getRestrictionsByCondition().addAll(replacements);
    }

    private static MetadataOperationException invalid(String message) {
        return new MetadataOperationException(MetadataOperationCode.INVALID_PROPERTY_VALUE, message, false);
    }
}
