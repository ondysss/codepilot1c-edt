package com.codepilot1c.core.edt.forms;

import java.util.LinkedHashMap;
import java.util.Map;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Owner strategy matrix for form creation and default form binding.
 */
public final class FormOwnerStrategy {

    private final Map<String, String> ownerToFactoryMethod;

    private FormOwnerStrategy(Map<String, String> ownerToFactoryMethod) {
        this.ownerToFactoryMethod = ownerToFactoryMethod;
    }

    public static FormOwnerStrategy defaultStrategy() {
        Map<String, String> matrix = new LinkedHashMap<>();
        matrix.put("Catalog", "createCatalogForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("Document", "createDocumentForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("InformationRegister", "createInformationRegisterForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("AccumulationRegister", "createAccumulationRegisterForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("Report", "createReportForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("DataProcessor", "createDataProcessorForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("Enum", "createEnumForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("Task", "createTaskForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("BusinessProcess", "createBusinessProcessForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("ChartOfAccounts", "createChartOfAccountsForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("ChartOfCalculationTypes", "createChartOfCalculationTypesForm"); //$NON-NLS-1$ //$NON-NLS-2$
        matrix.put("ChartOfCharacteristicTypes", "createChartOfCharacteristicTypesForm"); //$NON-NLS-1$ //$NON-NLS-2$
        return new FormOwnerStrategy(matrix);
    }

    public String resolveFactoryMethod(String ownerClass) {
        String method = ownerToFactoryMethod.get(ownerClass);
        if (method == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Unsupported parent for Form: " + ownerClass, false); //$NON-NLS-1$
        }
        return method;
    }

    public String resolveDefaultSetter(FormUsage usage) {
        if (usage == null || usage == FormUsage.AUXILIARY) {
            return null;
        }
        return switch (usage) {
            case OBJECT -> "setDefaultObjectForm"; //$NON-NLS-1$
            case LIST -> "setDefaultListForm"; //$NON-NLS-1$
            case CHOICE -> "setDefaultChoiceForm"; //$NON-NLS-1$
            case AUXILIARY -> null;
        };
    }
}
