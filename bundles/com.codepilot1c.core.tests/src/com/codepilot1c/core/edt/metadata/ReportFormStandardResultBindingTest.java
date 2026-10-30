package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertSame;

import java.lang.reflect.Method;
import java.util.Map;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.ReportFormExtInfo;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;

/** This test also runs against the previous core classes: unknown reportResult must fail there. */
public class ReportFormStandardResultBindingTest {
    @Test public void setFormPropsConnectsStandardGenerateToResultAttribute() throws Exception {
        Form form = FormFactory.eINSTANCE.createForm();
        var owner = MdClassFactory.eINSTANCE.createExternalReport();
        var metadataForm = MdClassFactory.eINSTANCE.createReportForm();
        owner.getForms().add(metadataForm);
        form.setMdForm(metadataForm);
        var attribute = FormFactory.eINSTANCE.createFormAttribute();
        attribute.setName("Result");
        var type = McoreFactory.eINSTANCE.createType();
        type.setName("SpreadsheetDocument");
        var description = McoreFactory.eINSTANCE.createTypeDescription();
        description.getTypes().add(type);
        attribute.setValueType(description);
        form.getAttributes().add(attribute);
        Method method = EdtMetadataService.class.getDeclaredMethod("applyFormPropertySet", EObject.class, Map.class);
        method.setAccessible(true);
        method.invoke(new EdtMetadataService(null, null, null), form, Map.of("reportResult", "Result"));
        assertSame(attribute, ((ReportFormExtInfo) form.getExtInfo()).getReportResult());
    }
}
