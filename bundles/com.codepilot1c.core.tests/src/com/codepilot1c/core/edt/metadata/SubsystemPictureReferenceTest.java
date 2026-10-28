package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.eclipse.emf.ecore.*;
import org.junit.Test;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;

/** Drives the existing reference setter in both installed and modified builds. */
public class SubsystemPictureReferenceTest {
    private final EdtMetadataService service=new EdtMetadataService(new EdtMetadataGateway(),new EventHandlerTargetResolver(x->List.of()));
    private final Configuration configuration=MdClassFactory.eINSTANCE.createConfiguration();
    private final Subsystem subsystem=MdClassFactory.eINSTANCE.createSubsystem();
    private final CommonPicture first=picture("FirstPicture");
    private final CommonPicture second=picture("SecondPicture");
    private CommonPicture picture(String name) {
        CommonPicture p=MdClassFactory.eINSTANCE.createCommonPicture();p.setName(name);configuration.getCommonPictures().add(p);return p;
    }
    private void assign(Object value) throws Throwable {
        Method method=EdtMetadataService.class.getDeclaredMethod("applyReferenceValue",Configuration.class,MdObject.class,EReference.class,Object.class);
        method.setAccessible(true);
        try { method.invoke(service,configuration,subsystem,subsystem.eClass().getEStructuralFeature("picture"),value); }
        catch(InvocationTargetException e) {throw e.getCause();}
    }
    private Object selectedPicture() {
        EObject wrapper=(EObject)subsystem.eGet(subsystem.eClass().getEStructuralFeature("picture"));
        return wrapper==null?null:wrapper.eGet(wrapper.eClass().getEStructuralFeature("picture"));
    }
    @Test public void assignsExistingCommonPicture() throws Throwable {assign("CommonPicture.FirstPicture");assertSame(first,selectedPicture());}
    @Test public void replacesExistingPictureWithoutDeletingIt() throws Throwable {
        assign("CommonPicture.FirstPicture");assign("CommonPicture.SecondPicture");assertSame(second,selectedPicture());
        assertTrue(configuration.getCommonPictures().contains(first));
    }
    @Test public void missingPictureLeavesPreviousValueIntact() throws Throwable {
        assign("CommonPicture.FirstPicture");assertThrows(MetadataOperationException.class,()-> {try {assign("CommonPicture.Missing");}catch(Throwable t){if(t instanceof Exception e)throw e;throw new AssertionError(t);}});
        assertSame(first,selectedPicture());
    }
    @Test public void wrongMetadataTypeLeavesPreviousValueIntact() throws Throwable {
        assign("CommonPicture.FirstPicture");Catalog catalog=MdClassFactory.eINSTANCE.createCatalog();catalog.setName("NotAPicture");configuration.getCatalogs().add(catalog);
        try {assign("Catalog.NotAPicture");fail("Wrong type accepted");}catch(MetadataOperationException expected) {assertSame(first,selectedPicture());}
    }
    @Test public void unsetRemovesOnlyWrapperAndPreservesPicture() throws Throwable {
        assign("CommonPicture.FirstPicture");subsystem.eUnset(subsystem.eClass().getEStructuralFeature("picture"));
        assertNull(selectedPicture());assertTrue(configuration.getCommonPictures().contains(first));
    }
    @Test public void doesNotEnableArbitraryContainmentWrites() throws Throwable {
        Method method=EdtMetadataService.class.getDeclaredMethod("applyReferenceValue",Configuration.class,MdObject.class,EReference.class,Object.class);method.setAccessible(true);
        EReference other=EcoreFactory.eINSTANCE.createEReference();other.setName("unsupportedContainment");other.setContainment(true);other.setEType(first.eClass());
        try {method.invoke(service,configuration,subsystem,other,"CommonPicture.FirstPicture");fail("Invalid value accepted");}
        catch(InvocationTargetException expected) {assertTrue(expected.getCause() instanceof MetadataOperationException);}
    }
}
