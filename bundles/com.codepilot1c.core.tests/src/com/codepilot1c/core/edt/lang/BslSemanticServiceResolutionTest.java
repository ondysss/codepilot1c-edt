package com.codepilot1c.core.edt.lang;

import static org.junit.Assert.assertSame;

import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Procedure;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.mcore.util.Environment;
import com._1c.g5.v8.dt.mcore.util.Environments;

public class BslSemanticServiceResolutionTest {

    private static final BslFactory FACTORY = BslFactory.eINSTANCE;

    @Test
    public void resolveSemanticTargetPrefersFeatureEntryMatchingCurrentEnvironment() {
        Procedure clientMethod = FACTORY.createProcedure();
        clientMethod.setName("ClientMethod"); //$NON-NLS-1$
        Procedure serverMethod = FACTORY.createProcedure();
        serverMethod.setName("ServerMethod"); //$NON-NLS-1$

        StaticFeatureAccess access = FACTORY.createStaticFeatureAccess();
        access.getFeatureEntries().add(featureEntry(clientMethod, new Environments(Environment.MNG_CLIENT)));
        access.getFeatureEntries().add(featureEntry(serverMethod, new Environments(Environment.SERVER)));

        assertSame(serverMethod, BslSemanticService.resolveSemanticTarget(access, Environments.SERVER));
    }

    @Test
    public void resolveSemanticTargetFollowsInvocationMethodAccess() {
        Procedure targetMethod = FACTORY.createProcedure();
        targetMethod.setName("ProcessOrder"); //$NON-NLS-1$

        StaticFeatureAccess access = FACTORY.createStaticFeatureAccess();
        access.getFeatureEntries().add(featureEntry(targetMethod, Environments.ALL));

        Invocation invocation = FACTORY.createInvocation();
        invocation.setMethodAccess(access);

        assertSame(targetMethod, BslSemanticService.resolveSemanticTarget(invocation, Environments.ALL));
    }

    @Test
    public void resolveSemanticTargetFallsBackToOriginalFeatureAccessWhenEntriesMissing() {
        StaticFeatureAccess access = FACTORY.createStaticFeatureAccess();

        assertSame(access, BslSemanticService.resolveSemanticTarget(access, Environments.ALL));
    }

    private static FeatureEntry featureEntry(org.eclipse.emf.ecore.EObject feature, Environments environments) {
        FeatureEntry entry = FACTORY.createFeatureEntry();
        entry.setFeature(feature);
        entry.setEnvironments(environments);
        return entry;
    }
}
