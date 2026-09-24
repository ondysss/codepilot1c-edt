package com._1c.g5.v8.dt.platform.core.typeinfo;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.xtext.scoping.IScope;

/**
 * Заглушка для eval вне OSGi (tools/run-child-type-properties-eval.sh), в сборку не входит.
 *
 * <p>Настоящий сервис падает в статической инициализации без платформы EDT ({@code ExceptionInInitializerError}
 * — это {@code Error}, и {@code EdtMetadataService} его не ловит). Заглушка с той же сигнатурой отвечает
 * «не нашёл» исключением времени выполнения, которое код ловит сам, — и разрешение типа уходит в кэш заранее
 * разрешённых типов, как на живом пути после {@code preResolveChildTypes}.</p>
 */
public class TypeProviderService {

    public static final TypeProviderService INSTANCE = new TypeProviderService();

    public TypeDescriptionInfoWithTypeInfo getTypeDescriptionInfoWithTypeInfo(
            EObject context, EReference reference, IScope scope) {
        throw new IllegalStateException("TypeProviderService недоступен вне EDT (заглушка eval)"); //$NON-NLS-1$
    }

    public TypeDescriptionInfoWithTypeInfo getTypeDescriptionInfoWithTypeInfo(
            EObject context, EObject parentContext, EReference reference, IScope scope) {
        throw new IllegalStateException("TypeProviderService недоступен вне EDT (заглушка eval)"); //$NON-NLS-1$
    }
}
