package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.List;
import org.junit.Test;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;

public class EdtExtensionUpdateServiceTest {
    public static IProject project(String name, boolean open) {
        return proxy(IProject.class, Map.of("getName",name,"exists",true,"isOpen",open));
    }
    public static InfobaseReference infobase(String connection) {
        return proxy(InfobaseReference.class, Map.of("getConnectionString",
                proxy(IConnectionString.class,Map.of("asConnectionString",connection))));
    }
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Map<String,Object> values) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
            if (m.getName().equals("equals")) return p==a[0];
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("toString")) return type.getSimpleName();
            return values.get(m.getName());
        });
    }
    private static final class Fixture {
        IProject extension=project("unrelated-extension-name",true);
        IProject base=project("ActualParent",true);
        InfobaseReference infobase=infobase("File=\"test\";Pwd=\"private\";");
        boolean extensionModel=true;
        boolean directModelAbsent=false;
        String resolvedBase;
        IProject appliedProject;
        final EdtRuntimeService runtime=new EdtRuntimeService(){
            @Override public InfobaseReference resolveDefaultInfobase(String name){resolvedBase=name;return infobase;}
            @Override public boolean reloadExtension(IProject p,InfobaseReference ib,boolean keep,boolean override,IProgressMonitor monitor){
                appliedProject=p;assertSame(infobase,ib);return true;
            }
        };
        final EdtMetadataGateway gateway=new EdtMetadataGateway(){
            @Override public IProject resolveProject(String name){return extension;}
            @Override public IV8ProjectManager getV8ProjectManager(){
                IV8Project model=extensionModel?proxy(IExtensionProject.class,Map.of("getParentProject",base,"getProject",extension)):
                        proxy(IV8Project.class,Map.of("getProject",extension));
                Map<String,Object> values=new java.util.HashMap<>();
                if(!directModelAbsent)values.put("getProject",model);
                values.put("getProjects",extensionModel?List.of(model):List.of());
                return proxy(IV8ProjectManager.class,values);
            }
        };
        EdtExtensionUpdateService service(){return new EdtExtensionUpdateService(gateway,runtime);}
    }
    @Test public void actualParentResolvesInfobaseWithoutInferringNamePrefix() throws Exception {
        Fixture f=new Fixture();var target=f.service().resolve(f.extension.getName(),null);
        assertEquals("ActualParent",f.resolvedBase);assertSame(f.base,target.base());
        assertEquals(64,target.binding().length());assertFalse(target.binding().contains("private"));
        assertTrue(f.service().update(target,false,false,new NullProgressMonitor()));
        assertSame(f.extension,f.appliedProject);
    }
    @Test public void extensionRegistryResolvesWhenDirectProjectLookupIsAbsent() {
        Fixture f=new Fixture();f.directModelAbsent=true;
        var target=f.service().resolve(f.extension.getName(),null);
        assertSame(f.extension,target.extension());assertSame(f.base,target.base());assertEquals("ActualParent",f.resolvedBase);
    }
    @Test public void refusesBaseProjectAsExtension(){
        Fixture f=new Fixture();f.extensionModel=false;expectCode(EdtToolErrorCode.INVALID_ARGUMENT,()->f.service().resolve("ActualParent",null));
        assertNull(f.resolvedBase);
    }
    @Test public void refusesOtherRequestedParent(){
        Fixture f=new Fixture();expectCode(EdtToolErrorCode.INVALID_ARGUMENT,()->f.service().resolve("Extension","Other"));assertNull(f.resolvedBase);
    }
    @Test public void refusesMissingProject(){
        Fixture f=new Fixture();f.extension=null;expectCode(EdtToolErrorCode.PROJECT_NOT_FOUND,()->f.service().resolve("Missing",null));
    }
    @Test public void refusesClosedExtension(){
        Fixture f=new Fixture();f.extension=project("Extension",false);expectCode(EdtToolErrorCode.EDT_NOT_READY,()->f.service().resolve("Extension",null));
    }
    @Test public void refusesClosedParent(){
        Fixture f=new Fixture();f.base=project("Base",false);expectCode(EdtToolErrorCode.EDT_NOT_READY,()->f.service().resolve("Extension",null));
    }
    @Test public void refusesEmptyArgument(){
        expectCode(EdtToolErrorCode.INVALID_ARGUMENT,()->new Fixture().service().resolve(" ",null));
    }
    @Test public void missingInfobaseCannotUpdate(){
        Fixture f=new Fixture();f.infobase=null;expectCode(EdtToolErrorCode.INFOBASE_NOT_FOUND,()->f.service().resolve("Extension",null));assertNull(f.appliedProject);
    }
    @Test public void queuedTargetCannotFollowChangedInfobase() throws Exception {
        Fixture f=new Fixture();var target=f.service().resolve("Extension",null);
        f.infobase=infobase("File=\"different\";");
        expectCode(EdtToolErrorCode.INVALID_ARGUMENT,()->f.service().update(target,false,false,new NullProgressMonitor()));assertNull(f.appliedProject);
    }
    @Test public void nativeProviderExcludesWrapperRegardlessOfRanking(){
        Bundle nativeBundle=proxy(Bundle.class,Map.of("getSymbolicName","com._1c.g5.v8.dt.platform.services.core"));
        Bundle wrapper=proxy(Bundle.class,Map.of("getSymbolicName","ru.xelgo.edt.contextlinks.ui"));
        assertTrue(EdtRuntimeGateway.isNativeSynchronizationProvider(proxy(ServiceReference.class,Map.of("getBundle",nativeBundle))));
        assertFalse(EdtRuntimeGateway.isNativeSynchronizationProvider(proxy(ServiceReference.class,Map.of("getBundle",wrapper))));
    }
    public static class Manager {
        Object result=true;Exception error;IProject project;boolean keep;
        public Object reloadInfobase(IProject p,InfobaseReference ib,Object cb,boolean k,IProgressMonitor m) throws Exception {
            project=p;keep=k;if(error!=null)throw error;return result;
        }
        public boolean updateInfobase(IProject p,InfobaseReference ib,Object cb,boolean k,IProgressMonitor m){fail("Must never fall back to base update");return true;}
    }
    private static boolean invoke(Manager manager) throws Exception {
        return EdtExtensionReloadInvoker.reload(manager,project("Extension",true),infobase("File=test;"),new Object(),false,new NullProgressMonitor());
    }
    @Test public void reloadPassesExtensionAndKeepConnected() throws Exception {
        Manager m=new Manager();assertTrue(invoke(m));assertEquals("Extension",m.project.getName());assertFalse(m.keep);
    }
    @Test public void falseReloadIsNotSuccess() throws Exception {Manager m=new Manager();m.result=false;assertFalse(invoke(m));}
    @Test public void statusErrorIsNotSuccess() throws Exception {Manager m=new Manager();m.result=Status.CANCEL_STATUS;assertFalse(invoke(m));}
    @Test public void statusOkIsSuccess() throws Exception {Manager m=new Manager();m.result=Status.OK_STATUS;assertTrue(invoke(m));}
    @Test public void nullReloadIsNotSuccess(){Manager m=new Manager();m.result=null;expectCode(EdtToolErrorCode.UPDATE_FAILED,()->invoke(m));}
    @Test public void unrelatedOverloadCannotFallBackToUpdate(){
        Object manager=new Object(){public boolean updateInfobase(Object a,Object b,Object c,boolean d,Object e){fail();return true;}};
        expectCode(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,()->EdtExtensionReloadInvoker.reload(manager,project("Extension",true),infobase("File=test;"),new Object(),false,new NullProgressMonitor()));
    }
    @Test public void reloadExceptionIsUnwrapped(){
        Manager m=new Manager();m.error=new EdtToolException(EdtToolErrorCode.UPDATE_FAILED,"native failure");expectCode(EdtToolErrorCode.UPDATE_FAILED,()->invoke(m));
    }
    public enum ConflictResult {OVERRIDDEN,DEFERRED}
    public interface Callback {ConflictResult resolveInfobaseChanges(Object resolver);boolean onConfirm();}
    public static class ConflictResolver {
        int calls;boolean broken;
        public ConflictResult overrideConflict(){calls++;if(broken)throw new IllegalStateException("resolver failure");return ConflictResult.OVERRIDDEN;}
    }
    private static Object conflict(Object resolver,boolean allowed) throws Exception {
        return EdtRuntimeService.handleExtensionUpdateCallback(new Object(),
                Callback.class.getMethod("resolveInfobaseChanges",Object.class),new Object[]{resolver},allowed);
    }
    @Test public void conflictingInfobaseIsNotOverwrittenByDefault(){
        ConflictResolver r=new ConflictResolver();expectCode(EdtToolErrorCode.UPDATE_FAILED,()->conflict(r,false));assertEquals(0,r.calls);
    }
    @Test public void explicitOverrideActuallyCallsResolver() throws Exception {
        ConflictResolver r=new ConflictResolver();assertEquals(ConflictResult.OVERRIDDEN,conflict(r,true));assertEquals(1,r.calls);
    }
    @Test public void missingResolverCannotReportSuccessfulOverride(){
        expectCode(EdtToolErrorCode.UPDATE_FAILED,()->conflict(new Object(),true));
    }
    @Test public void failingResolverCannotReportSuccessfulOverride(){
        ConflictResolver r=new ConflictResolver();r.broken=true;expectCode(EdtToolErrorCode.UPDATE_FAILED,()->conflict(r,true));assertEquals(1,r.calls);
    }
    @Test public void ordinaryConfirmationRemainsHeadless() throws Exception {
        assertEquals(Boolean.TRUE,EdtRuntimeService.handleExtensionUpdateCallback(new Object(),Callback.class.getMethod("onConfirm"),null,false));
    }
    interface Throwing {void run() throws Exception;}
    static void expectCode(EdtToolErrorCode code,Throwing action){
        try{action.run();fail("Expected "+code);}catch(EdtToolException e){assertEquals(code,e.getCode());}catch(Exception e){throw new AssertionError(e);}
    }
}
