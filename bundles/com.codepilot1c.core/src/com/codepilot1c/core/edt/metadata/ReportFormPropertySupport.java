package com.codepilot1c.core.edt.metadata;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.ReportFormExtInfo;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.mcore.TypeItem;

/** Native result binding used by the standard Generate command of report forms. */
final class ReportFormPropertySupport {
    private ReportFormPropertySupport() {
    }

    static boolean apply(Form form, String key, Object value) {
        if (!"reportResult".equalsIgnoreCase(key)) {
            return false;
        }
        if (!(form.getExtInfo() instanceof ReportFormExtInfo)
                && (form.getMdForm() == null
                    || !(form.getMdForm().eContainer() instanceof Report
                        || form.getMdForm().eContainer() instanceof ExternalReport))) {
            throw invalid("reportResult requires a report form");
        }
        if (!(value instanceof String name)) {
            throw invalid("reportResult must be a form attribute name or an empty string");
        }
        FormAttribute result = null;
        if (!name.isEmpty()) {
            result = form.getAttributes().stream()
                    .filter(attribute -> name.equals(attribute.getName()))
                    .findFirst().orElseThrow(() -> invalid("Report result attribute not found: " + name));
            boolean spreadsheet = result.getValueType() != null
                    && result.getValueType().getTypes().stream().map(TypeItem::getName)
                        .anyMatch(type -> "SpreadsheetDocument".equals(type) || "ТабличныйДокумент".equals(type));
            if (!spreadsheet) {
                throw invalid("Report result attribute must have type SpreadsheetDocument: " + name);
            }
        }
        ReportFormExtInfo info;
        if (form.getExtInfo() instanceof ReportFormExtInfo existing) {
            info = existing;
        } else {
            info = FormFactory.eINSTANCE.createReportFormExtInfo();
            form.setExtInfo(info);
        }
        info.setReportResult(result);
        return true;
    }

    private static MetadataOperationException invalid(String message) {
        return new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE, message, false);
    }
}
