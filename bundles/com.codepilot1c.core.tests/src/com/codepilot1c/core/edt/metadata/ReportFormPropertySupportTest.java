package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.*;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.ReportFormExtInfo;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;

public class ReportFormPropertySupportTest {
    private Form reportForm() {
        var owner = MdClassFactory.eINSTANCE.createExternalReport();
        var metadataForm = MdClassFactory.eINSTANCE.createReportForm();
        owner.getForms().add(metadataForm);
        Form form = FormFactory.eINSTANCE.createForm();
        form.setMdForm(metadataForm);
        FormAttribute attribute = FormFactory.eINSTANCE.createFormAttribute();
        attribute.setName("Result");
        var type = McoreFactory.eINSTANCE.createType();
        type.setName("SpreadsheetDocument");
        var description = McoreFactory.eINSTANCE.createTypeDescription();
        description.getTypes().add(type);
        attribute.setValueType(description);
        form.getAttributes().add(attribute);
        return form;
    }

    @Test public void bindsStandardReportResult() {
        Form form = reportForm();
        assertTrue(ReportFormPropertySupport.apply(form, "reportResult", "Result"));
        assertSame(form.getAttributes().get(0), ((ReportFormExtInfo) form.getExtInfo()).getReportResult());
    }

    @Test public void preservesOtherReportExtensionProperties() {
        Form form = reportForm();
        ReportFormExtInfo info = FormFactory.eINSTANCE.createReportFormExtInfo();
        info.setDetailsInformation(form.getAttributes().get(0));
        form.setExtInfo(info);
        ReportFormPropertySupport.apply(form, "reportResult", "Result");
        assertSame(info, form.getExtInfo());
        assertSame(form.getAttributes().get(0), info.getDetailsInformation());
        ReportFormPropertySupport.apply(form, "reportResult", "");
        assertNull(info.getReportResult());
        assertNotNull(info.getDetailsInformation());
    }

    @Test public void rejectsMissingAttributeBeforeChangingExtension() {
        Form form = reportForm();
        assertThrows(MetadataOperationException.class,
                () -> ReportFormPropertySupport.apply(form, "reportResult", "Missing"));
        assertNull(form.getExtInfo());
    }

    @Test public void rejectsWrongTypeBeforeChangingExtension() {
        Form form = reportForm();
        form.getAttributes().get(0).getValueType().getTypes().get(0).setName("String");
        assertThrows(MetadataOperationException.class,
                () -> ReportFormPropertySupport.apply(form, "reportResult", "Result"));
        assertNull(form.getExtInfo());
    }

    @Test public void rejectsNonReportFormAndInvalidPayload() {
        Form form = FormFactory.eINSTANCE.createForm();
        assertThrows(MetadataOperationException.class,
                () -> ReportFormPropertySupport.apply(form, "reportResult", "Result"));
        assertThrows(MetadataOperationException.class,
                () -> ReportFormPropertySupport.apply(reportForm(), "reportResult", 1));
        assertFalse(ReportFormPropertySupport.apply(form, "unknownProperty", "Result"));
    }
}
