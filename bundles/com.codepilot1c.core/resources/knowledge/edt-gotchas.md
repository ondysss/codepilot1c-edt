EDT development pitfalls:
- BM sync: after metadata mutation, wait for derived data recalculation before reading results
- Export timing: forceExport() is asynchronous; do not assume files exist immediately after call
- UUID handling: always null-check UUIDs; use bmGetTopObject() before bmGetFqn()
- Form materialization: newly created forms need disk file creation before they can be opened
- Configuration comparison: EDT comparison mode treats extension-adopted objects differently
- Workspace refresh: IProject.refreshLocal() may be needed after external file changes
- Derived data: validation results and cross-references update asynchronously after metadata changes
- Module editing: BSL modules are XText resources; use proper resource set for editing
- Platform version: Version.LATEST is a fallback, not an actual detected version
