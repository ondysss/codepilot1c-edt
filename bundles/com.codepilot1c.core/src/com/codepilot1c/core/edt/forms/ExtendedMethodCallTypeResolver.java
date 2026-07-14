package com.codepilot1c.core.edt.forms;

import java.util.stream.Collectors;

import com._1c.g5.v8.dt.form.model.ExtendedMethodCallType;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Pure resolution of the {@code call_type} operation field (EXT-01) to an
 * {@link ExtendedMethodCallType} literal.
 *
 * <p>No EMF-transaction/BM dependency — this class only reads the platform-bundled
 * {@link ExtendedMethodCallType} enum, so it is unit-testable without a live workspace,
 * mirroring the {@link EventHandlerTargetResolver} seam split.</p>
 */
public class ExtendedMethodCallTypeResolver {

    /**
     * Resolves the requested {@code call_type} string to an {@link ExtendedMethodCallType}.
     *
     * <p>An omitted or blank {@code requested} value resolves explicitly to
     * {@link ExtendedMethodCallType#BEFORE} — the bytecode-verified platform default for
     * EXT-01 (per D-RESEARCH-02: {@code FormPackageImpl}'s {@code initEAttribute} call for
     * {@code callType} passes a null {@code defaultValueLiteral}, and EMF resolves an unset
     * {@code EEnum}-typed attribute with no explicit default to its first declared literal,
     * confirmed as {@code BEFORE(0,"Before")} by the enum's static initializer). Resolving it
     * explicitly here — rather than relying on the EMF-unset implicit value — keeps the value
     * observable and greppable.</p>
     *
     * <p>A non-blank {@code requested} value is matched case-insensitively against BOTH the
     * Java constant name ({@code BEFORE}/{@code AFTER}/{@code OVERRIDE}/
     * {@code CHANGE_AND_VALIDATE}) and the EMF literal ({@code Before}/{@code After}/
     * {@code Override}/{@code ChangeAndValidate}) of every candidate in
     * {@link ExtendedMethodCallType#VALUES}. This closed-set scan is the ONLY permitted match
     * path — the enum's exact-match static lookup factory method MUST NOT be used, since it
     * throws an uncaught {@link IllegalArgumentException} on any non-exact match (Security V5 /
     * DoS concern in the Phase 8 threat model).</p>
     *
     * @param requested the requested {@code call_type} string, or {@code null}/blank for the
     *                  default
     * @return the resolved {@link ExtendedMethodCallType} literal
     * @throws MetadataOperationException with code {@code METADATA_NOT_FOUND} if {@code requested}
     *         is non-blank and matches no allowed literal; the message lists all four allowed
     *         EMF literals, mirroring {@link EventHandlerTargetResolver#resolveConcreteEvent}'s
     *         unknown-event error shape
     */
    public ExtendedMethodCallType resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return ExtendedMethodCallType.BEFORE;
        }
        for (ExtendedMethodCallType candidate : ExtendedMethodCallType.VALUES) {
            if (requested.equalsIgnoreCase(candidate.getName())
                    || requested.equalsIgnoreCase(candidate.getLiteral())) {
                return candidate;
            }
        }
        throw new MetadataOperationException(
                MetadataOperationCode.METADATA_NOT_FOUND,
                "Unknown call_type '" + requested + "'. Allowed values: " //$NON-NLS-1$ //$NON-NLS-2$
                        + ExtendedMethodCallType.VALUES.stream()
                                .map(ExtendedMethodCallType::getLiteral)
                                .collect(Collectors.joining(", ")), //$NON-NLS-1$
                false);
    }
}
