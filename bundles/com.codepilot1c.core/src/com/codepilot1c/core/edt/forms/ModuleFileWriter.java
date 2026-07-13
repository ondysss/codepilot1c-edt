package com.codepilot1c.core.edt.forms;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Injectable seam for reading/writing a BSL module's text content.
 *
 * <p>This is the seam that makes {@link BslHandlerStubWriter}'s rollback-on-write-failure
 * path (STUB-01's "both or neither" contract) unit-testable headlessly, without a live
 * EDT/BM/OSGi workspace — mirroring the injection-seam pattern already established by
 * Phase 6's {@code EventHandlerCatalog} constructor injection. Production code uses the
 * {@link IFileModuleFileWriter} default implementation; tests use an in-memory fake (see
 * {@code BslHandlerStubWriteTest}).</p>
 */
public interface ModuleFileWriter {

    /**
     * Reads the current text content of the module at the given workspace-relative path.
     *
     * @param workspacePath the workspace-relative path to the module file
     * @return the file's text content, or {@code ""} when the file does not exist
     */
    String read(String workspacePath);

    /**
     * Writes (creates or overwrites) the module at the given workspace-relative path with
     * the given content.
     *
     * @param workspacePath the workspace-relative path to the module file
     * @param content the full text content to write
     */
    void write(String workspacePath, String content);

    /**
     * Live, {@link IFile}-backed default implementation. Mirrors
     * {@code YaxunitAuthoringTool.resolveWorkspaceFile}/{@code readFile}/{@code writeFile}
     * exactly (same create-or-setContents(FORCE) + refreshLocal(DEPTH_ZERO) shape).
     */
    final class IFileModuleFileWriter implements ModuleFileWriter {

        @Override
        public String read(String workspacePath) {
            IFile file = resolveWorkspaceFile(workspacePath);
            if (file == null || !file.exists()) {
                return ""; //$NON-NLS-1$
            }
            try {
                return new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException | CoreException e) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Failed to read module file: " + e.getMessage(), true, e); //$NON-NLS-1$
            }
        }

        @Override
        public void write(String workspacePath, String content) {
            IFile file = resolveWorkspaceFile(workspacePath);
            if (file == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_NOT_FOUND,
                        "Cannot resolve module file: " + workspacePath, true); //$NON-NLS-1$
            }
            try (ByteArrayInputStream source = new ByteArrayInputStream(
                    content.getBytes(StandardCharsets.UTF_8))) {
                if (file.exists()) {
                    file.setContents(source, IResource.FORCE, null);
                } else {
                    file.create(source, IResource.FORCE, null);
                }
                file.refreshLocal(IResource.DEPTH_ZERO, null);
            } catch (IOException | CoreException e) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Failed to write module file: " + e.getMessage(), true, e); //$NON-NLS-1$
            }
        }

        private static IFile resolveWorkspaceFile(String workspacePath) {
            if (workspacePath == null || workspacePath.isBlank()) {
                return null;
            }
            String normalized = workspacePath.startsWith("/") ? workspacePath.substring(1) : workspacePath; //$NON-NLS-1$
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            return root.getFile(new Path(normalized));
        }
    }
}
