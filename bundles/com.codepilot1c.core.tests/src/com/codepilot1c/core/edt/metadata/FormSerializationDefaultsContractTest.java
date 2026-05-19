package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-contract tests for the post-mutation "materialize platform
 * defaults" pass in {@code EdtMetadataService}.
 *
 * <p>The behavior cannot be exercised through plain Maven test bundles
 * because {@code Form}, {@code Table}, {@code FormFactory} etc. are only
 * resolvable inside the OSGi/EMF runtime. We pin the structure instead:
 * the contract test reads the source file as a string and asserts that
 * the required EMF API calls are present. This mirrors the pattern in
 * {@link EdtMetadataFollowupContractTest}.</p>
 *
 * <p>Each assertion corresponds to one of the seven lossy-serialization
 * categories from {@code 2026-05-18-bm-serialization-lossy.md}. The
 * comment cross-links to the category number for traceability.</p>
 */
public class FormSerializationDefaultsContractTest {

    private static final String SOURCE_PATH =
            "bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java"; //$NON-NLS-1$

    // --- Category #1: Table helper sub-elements (Addition + rowFilter) ------

    @Test
    public void normalizesTableSearchStringAddition() throws Exception {
        String source = readCoreSource();
        // Category #1 (HIGH): every Table must carry a SearchStringAddition.
        assertTrue("EdtMetadataService must call setSearchStringAddition during normalize", //$NON-NLS-1$
                source.contains("setSearchStringAddition(")); //$NON-NLS-1$
        assertTrue("EdtMetadataService must create SearchStringAdditionExtInfo via FormFactory", //$NON-NLS-1$
                source.contains("createSearchStringAdditionExtInfo(")); //$NON-NLS-1$
    }

    @Test
    public void normalizesTableViewStatusAddition() throws Exception {
        String source = readCoreSource();
        assertTrue("EdtMetadataService must call setViewStatusAddition during normalize", //$NON-NLS-1$
                source.contains("setViewStatusAddition(")); //$NON-NLS-1$
        assertTrue("EdtMetadataService must create ViewStatusAdditionExtInfo via FormFactory", //$NON-NLS-1$
                source.contains("createViewStatusAdditionExtInfo(")); //$NON-NLS-1$
    }

    @Test
    public void normalizesTableSearchControlAddition() throws Exception {
        String source = readCoreSource();
        assertTrue("EdtMetadataService must call setSearchControlAddition during normalize", //$NON-NLS-1$
                source.contains("setSearchControlAddition(")); //$NON-NLS-1$
        assertTrue("EdtMetadataService must create SearchControlAdditionExtInfo via FormFactory", //$NON-NLS-1$
                source.contains("createSearchControlAdditionExtInfo(")); //$NON-NLS-1$
    }

    @Test
    public void normalizesTableRowFilter() throws Exception {
        String source = readCoreSource();
        // Category #6 (LOW): <rowFilter xsi:type="core:UndefinedValue"/>.
        // Materialized via McoreFactory.createUndefinedValue() and
        // Table.setRowFilter(Value).
        assertTrue("EdtMetadataService must call setRowFilter on Table during normalize", //$NON-NLS-1$
                source.contains("setRowFilter(")); //$NON-NLS-1$
        assertTrue("EdtMetadataService must create UndefinedValue via McoreFactory", //$NON-NLS-1$
                source.contains("createUndefinedValue(")); //$NON-NLS-1$
    }

    // --- Category #3: ContextMenu on visual items ---------------------------

    @Test
    public void normalizesContextMenuBlock() throws Exception {
        String source = readCoreSource();
        // Category #3 (MEDIUM): every Decoration / FormField column / Table
        // gets <contextMenu>…<autoFill>true</autoFill></contextMenu>.
        assertTrue("EdtMetadataService must call setContextMenu during normalize", //$NON-NLS-1$
                source.contains("setContextMenu(")); //$NON-NLS-1$
        assertTrue("EdtMetadataService must create ContextMenu via FormFactory", //$NON-NLS-1$
                source.contains("createContextMenu(")); //$NON-NLS-1$
    }

    // --- Category #4: FormAttribute view/edit -------------------------------

    @Test
    public void normalizesAttributeViewEdit() throws Exception {
        String source = readCoreSource();
        // Category #4 (MEDIUM): every FormAttribute (except Object) gets
        // <view><common>true</common></view> + <edit><common>true</common></edit>
        // materialized as AdjustableBoolean instances.
        assertTrue("EdtMetadataService must call setView on FormAttribute during normalize", //$NON-NLS-1$
                source.contains(".setView(")); //$NON-NLS-1$
        assertTrue("EdtMetadataService must call setEdit on FormAttribute during normalize", //$NON-NLS-1$
                source.contains(".setEdit(")); //$NON-NLS-1$
    }

    // --- Category #5: FormCommand use ---------------------------------------

    @Test
    public void normalizesCommandUse() throws Exception {
        String source = readCoreSource();
        // Category #5 (MEDIUM): every FormCommand gets <use><common>true</common></use>.
        assertTrue("EdtMetadataService must call setUse on FormCommand during normalize", //$NON-NLS-1$
                source.contains(".setUse(")); //$NON-NLS-1$
    }

    // --- Category #2: Event-handler container re-bucketing ------------------

    @Test
    public void normalizesHandlerExtInfoBucketing() throws Exception {
        String source = readCoreSource();
        // Category #2 (HIGH): handlers for InputFieldExtInfo-owned events
        // (Clearing, TextEditEnd, AutoComplete, …) move from top-level
        // FormField into the extInfo container. The normalize pass must
        // consult the rule table in FormDefaultsRules.
        assertTrue("EdtMetadataService must consult FormDefaultsRules.preferExtInfoForInputField", //$NON-NLS-1$
                source.contains("FormDefaultsRules.preferExtInfoForInputField")); //$NON-NLS-1$
    }

    // --- Normalize pass plumbing --------------------------------------------

    @Test
    public void normalizePassDeclaredAndInvoked() throws Exception {
        String source = readCoreSource();
        // The normalize pass must exist and be invoked from both
        // applyFormModelOperations (updateFormModel entry point) and
        // applyFormRecipe (apply_form_recipe entry point). Pin the
        // method name so a future refactor cannot silently drop the
        // call site.
        assertTrue("EdtMetadataService must declare normalizeFormSerializationDefaults", //$NON-NLS-1$
                source.contains("normalizeFormSerializationDefaults(")); //$NON-NLS-1$
        // Two call sites: one at end of applyFormModelOperations, one
        // at end of the apply_form_recipe write transaction.
        int callCount = countOccurrences(source, "normalizeFormSerializationDefaults("); //$NON-NLS-1$
        assertTrue("normalizeFormSerializationDefaults must be invoked from both " //$NON-NLS-1$
                + "applyFormModelOperations and applyFormRecipe (got " + callCount + " mention(s))", //$NON-NLS-1$ //$NON-NLS-2$
                callCount >= 3);
        // (1 declaration + ≥2 call sites = ≥3 textual occurrences)
    }

    // --- Category #8: id reassignment after IFormItemManagementService ------

    @Test
    public void newFormItemsGetSafeIdAfterEdtApi() throws Exception {
        String source = readCoreSource();
        // Category #8 (HIGH): every add_* path must force-reassign the id
        // returned by IFormItemManagementService through the upgraded
        // nextFormItemId(Form) allocator (which walks eAllContents to see
        // FormItem ids inside Addition / ContextMenu / ExtendedTooltip
        // sub-element blocks). Without this, EDT's internal allocator
        // can hand out an id that the normalize pass already used for a
        // ContextMenu on a sibling item, producing the
        // `form-invalid-item-id` diagnostic.
        assertTrue("EdtMetadataService must declare assignSafeFormItemId", //$NON-NLS-1$
                source.contains("private void assignSafeFormItemId(")); //$NON-NLS-1$
        // One declaration + one call from each of the 5 add_* helpers
        // (group, table, decoration, field, button).
        int callCount = countOccurrences(source, "assignSafeFormItemId("); //$NON-NLS-1$
        assertTrue("assignSafeFormItemId must be invoked from all add_* paths " //$NON-NLS-1$
                + "(declaration + ≥5 calls expected, got " + callCount + ")", //$NON-NLS-1$ //$NON-NLS-2$
                callCount >= 6);
    }

    @Test
    public void safeIdReassignmentRecursesIntoSubElements() throws Exception {
        String source = readCoreSource();
        // Category #8 follow-up (2026-05-19 verification): the first cut of
        // assignSafeFormItemId only reassigned the top-level item id, leaving
        // EDT-allocated sub-element ids (ExtendedTooltip, ContextMenu, nested
        // ContextMenu, AutoCommandBar) to collide with prior normalize-pass
        // sub-element ids. The helper must walk item.eAllContents() and
        // reassign every nested FormItem so the entire sub-tree of the new
        // item lands past the current global max.
        assertTrue("assignSafeFormItemId must walk item.eAllContents() to renumber sub-elements", //$NON-NLS-1$
                source.contains("item.eAllContents()")); //$NON-NLS-1$
        assertTrue("assignSafeFormItemId must renumber every nested FormItem via setId(nextId[0]++)", //$NON-NLS-1$
                source.contains("nested.setId(nextId[0]++)")); //$NON-NLS-1$
    }

    // --- Cat-A: ContextMenu over-emission on ExtendedTooltip ----------------

    @Test
    public void normalizeSkipsContextMenuOnExtendedTooltip() throws Exception {
        String source = readCoreSource();
        // The 2026-05-19 "normalize-pass over-emit" report identified the
        // actual root cause behind the 3-day EDT-designer-blank-preview
        // saga: ExtendedTooltip extends Decoration in the form EMF model, so
        // the normalize loop's `instanceof Decoration` branch matched every
        // ExtendedTooltip and attached a phantom ContextMenu to it — ~27
        // phantom ContextMenus on the playground form. The fix gates the
        // Decoration branch by an explicit ExtendedTooltip skip.
        assertTrue("Normalize loop must check ExtendedTooltip before the Decoration branch", //$NON-NLS-1$
                source.contains("obj instanceof ExtendedTooltip")); //$NON-NLS-1$
    }

    @Test
    public void normalizeStripsLegacyContextMenuOnExtendedTooltip() throws Exception {
        String source = readCoreSource();
        // Cat-A cleanup: also un-attach a ContextMenu the prior buggy
        // normalize-passes (builds 1933 / 2204 / 2248) put on each
        // ExtendedTooltip. Without this, agents inheriting a damaged
        // form would need a Configurator round-trip to clear the
        // phantom ContextMenus; with it, the next mutation auto-fixes.
        assertTrue("Normalize must strip pre-existing ContextMenu on ExtendedTooltip (inherited damage)", //$NON-NLS-1$
                source.contains("tip.setContextMenu(null)")); //$NON-NLS-1$
    }

    // --- Cat-B: Additions get <enabled>false</enabled> + nested ExtendedTooltip

    @Test
    public void buildAdditionEmitsEnabledFalseAndExtendedTooltip() throws Exception {
        String source = readCoreSource();
        // Cat-B (2026-05-19): Configurator emits <enabled>false</enabled> on
        // every Addition and a nested <extendedTooltip> Label block. Match.
        assertTrue("buildAddition must mark Addition disabled via setEnabled(false)", //$NON-NLS-1$
                source.contains("addition.setEnabled(false)")); //$NON-NLS-1$
        assertTrue("buildAddition must create an ExtendedTooltip child", //$NON-NLS-1$
                source.contains("createExtendedTooltip(")); //$NON-NLS-1$
        assertTrue("buildAddition must wire the ExtendedTooltip into the Addition", //$NON-NLS-1$
                source.contains("addition.setExtendedTooltip(")); //$NON-NLS-1$
    }

    // --- Cat-C: rowFilter only on non-DynamicList tables --------------------

    @Test
    public void rowFilterMaterializationGatedByDynamicListCheck() throws Exception {
        String source = readCoreSource();
        // Cat-C (2026-05-19): regular Tables (bound to ValueTable /
        // TabularSection) get <rowFilter xsi:type="core:UndefinedValue"/>;
        // DynamicList-backed Tables do not (filtering lives on the
        // DynamicList settings) and Configurator strips it on round-trip.
        assertTrue("rowFilter materialization must be gated by DynamicListTableExtInfo check", //$NON-NLS-1$
                source.contains("DynamicListTableExtInfo")); //$NON-NLS-1$
    }

    @Test
    public void nextFormItemIdWalksEAllContentsForFormRoot() throws Exception {
        String source = readCoreSource();
        // Pin the global-walk upgrade. Without eAllContents, the allocator
        // misses Addition / ContextMenu / ExtendedTooltip ids and the
        // assignSafeFormItemId fix degenerates.
        assertTrue("nextFormItemId on Form root must walk eAllContents() to see sub-element ids", //$NON-NLS-1$
                source.contains("if (container instanceof Form formModel) {") //$NON-NLS-1$
                && source.contains("formModel.eAllContents()")); //$NON-NLS-1$
    }

    // --- Helpers ------------------------------------------------------------

    private String readCoreSource() throws Exception {
        Path repoRoot = findRepoRoot();
        return Files.readString(repoRoot.resolve(SOURCE_PATH), StandardCharsets.UTF_8);
    }

    private Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(); //$NON-NLS-1$
        while (current != null) {
            // The test bundle always lives under `bundles/` and the repo
            // root carries a top-level `pom.xml`. Use those two together
            // as the probe — robust across forks and fresh clones.
            if (Files.isDirectory(current.resolve("bundles")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("pom.xml")) //$NON-NLS-1$
                    && Files.isRegularFile(current.resolve("LICENSE"))) { //$NON-NLS-1$
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root"); //$NON-NLS-1$
    }

    private int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
