package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import com._1c.g5.v8.dt.metadata.common.ApplicationUsePurpose;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.FormType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

public class CommonFormCreationTest {
    private final EdtMetadataService service = new EdtMetadataService(null, null, null);

    private MdObject create(MetadataKind kind) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("createTopLevelObject", MetadataKind.class);
        method.setAccessible(true);
        return (MdObject) method.invoke(service, kind);
    }

    @Test public void commonFormIsManaged() throws Exception {
        BasicForm form = (BasicForm) create(MetadataKind.COMMON_FORM);
        assertEquals(FormType.MANAGED, form.getFormType());
    }

    @Test public void commonFormHasBothPlatformPurposes() throws Exception {
        BasicForm form = (BasicForm) create(MetadataKind.COMMON_FORM);
        assertEquals(List.of(ApplicationUsePurpose.PERSONAL_COMPUTER,
                ApplicationUsePurpose.MOBILE_DEVICE), form.getUsePurposes());
    }

    @Test public void commonFormUsesGenericGeneratorWithoutObjectData() throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("inferAuxiliaryFormType", MdObject.class);
        method.setAccessible(true);
        assertEquals("GENERIC", method.invoke(service, create(MetadataKind.COMMON_FORM)));
    }

    @Test public void ordinaryOwnersKeepTheirGeneratorTypes() throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("inferAuxiliaryFormType", MdObject.class);
        method.setAccessible(true);
        assertEquals("OBJECT", method.invoke(service, MdClassFactory.eINSTANCE.createCatalog()));
        assertEquals("REPORT", method.invoke(service, MdClassFactory.eINSTANCE.createReport()));
        assertEquals("LIST", method.invoke(service, MdClassFactory.eINSTANCE.createInformationRegister()));
    }
}
