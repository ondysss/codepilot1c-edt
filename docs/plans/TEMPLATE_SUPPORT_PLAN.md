# Plan: Template/Макет Support via EDT BM API

## Status: Phase 1 ✅, Phase 1.5a ✅, Phase 2 ✅, Phase 2.5 ✅ — ALL DONE

---

## What Works (Phase 1 — COMPLETED)
- `add_metadata_child` with `child_kind=Template` creates Template metadata in BM
- `TemplateType` is set correctly (SpreadsheetDocument default, DCS via `template_type=dcs`)
- For spreadsheet templates: placeholder `.mxl` file is created via `ensureTemplateArtifact()`
- For DCS templates: `.mxl` is NOT created (correct — DCS uses `dcs_manage`)
- `template_type` parameter flows through validation service correctly
- E2E tested: model (kimi-k2.5) creates both spreadsheet and DCS templates successfully

## What's Wrong (discovered during testing)
- **`.mxl` file format is INCORRECT**: we create XML placeholder, but real `.mxl` is **binary MOXCEL format**
- Real `.mxl` starts with magic bytes `MOXCEL` (hex: `4d 4f 58 43 45 4c`)
- Our placeholder starts with `<?xml` — EDT won't recognize it as a valid spreadsheet document
- `template.setTemplate(SpreadsheetDocument)` fails in BM transaction with
  "Failed to persist reference value" — SpreadsheetDocument is stored as external blob, not containment

---

## Key Discovery: .mxl File Format

### Real .mxl (from 1C platform)
```
00000000: 4d4f 5843 454c 0008 0001 000a 00ef bbbf  MOXCEL..........
00000010: 7b38 2c31 2c31 302c ...                   {8,1,10,...
```
- **Binary** MOXCEL proprietary format
- NOT XML
- Contains spreadsheet structure in a compressed text/binary hybrid

### EDT Internal Storage
- SpreadsheetDocument stored in BM as IBmObject (separate from Template metadata)
- Exported to disk via `SpreadsheetDocumentExporter` → `MxlSerializer.serializeMxl()`
- `MoxelResourceMxl` is the EMF Resource that handles `.mxl` file I/O (`doLoad`/`doSave`)
- `MoxelResourceMxl` implements `IDtProjectAware` — requires `setDtProject(IDtProject)` for full initialization
- `SpreadsheetDocumentImporter` imports from `.mxl` files into BM on workspace changes

### Correct API Surface (verified from JavaDoc)
```java
// SpreadsheetDocument — EMap-based, NOT EList
SpreadsheetDocument sheet = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
sheet.getRows()    // → EMap<Integer, Row>  (keyed by row index, NOT EList<Row>)
row.getCells()     // → EMap<Integer, Cell> (keyed by column index)

// MoxelResourceMxl — IDtProjectAware
MoxelResourceMxl resource = new MoxelResourceMxl(uri);
resource.setDtProject(dtProject);  // REQUIRED for proper serialization
resource.getContents().add(sheet);
resource.save(Collections.emptyMap());

// MxlSerializer — internal API
new MxlSerializer(sheet, dtProject).serializeMxl(storage, false);
```

---

## EDT SpreadsheetDocument API Reference

### Core Object Model
```
SpreadsheetDocument
├── columns: Columns (primary column set)
│   ├── columnsId: UUID
│   ├── size: int (total width in units, NOT column count)
│   └── columns: EMap<Integer, Column>
│       └── width: int, formatIndex: int, hidden: boolean
├── rows: EMap<Integer, Row>  (keyed by row index)
│   └── cells: EMap<Integer, Cell>  (keyed by column index)
│       ├── text: LocalString (static text)
│       ├── parameter: String (document-level data binding — "НомерДокумента")
│       ├── detailParameter: String (repeating-row/detail-level binding)
│       ├── value: Value (formula/expression)
│       ├── formatIndex: int → points to formats[]
│       └── pictureParameter: String
├── formats: EList<Format>
│   ├── font: int (index), textColor: int, backColor: int
│   ├── horizontalAlignment, verticalAlignment: enum
│   ├── leftBorder, topBorder, rightBorder, bottomBorder: int
│   ├── height: int, width: int
│   └── dataFormat, editFormat, mask: LocalString
├── fonts: EList<Font> (from mcore)
├── colors: EList<Color>
├── namedItems: EMap<String, NamedItem>
│   └── NamedItemCells → Area
│       ├── RectArea (position: Rect{x,y,width,height})
│       ├── RowsArea (begin, end — for repeating template sections)
│       ├── ColumnsArea (begin, end)
│       └── TableArea
├── merges: EList<Merge> (position: Rect, antimerge: enum)
├── rowGroups, columnGroups: EList<RowGroup/ColumnGroup>
└── printSettings, languageSettings, drawings, pictures, ...
```

### MoxelFactory Create Methods
```java
MoxelFactory f = MoxelFactory.eINSTANCE;
f.createSpreadsheetDocument()  // root
f.createColumns()              // column set
f.createColumn()               // single column
f.createRow()                  // row
f.createCell()                 // cell
f.createFormat()               // formatting
f.createRect()                 // position rectangle
f.createMerge()                // merged cells
f.createNamedItemCells()       // named area
f.createRectArea()             // rectangular area
f.createRowsArea()             // row range (for СтрокаТаблицы)
f.createColumnsArea()          // column range
f.createTableArea()            // table area
f.createPrintSettings()        // print setup
f.createLanguageSettings()     // language config
```

### Print Template Named Areas (1C Convention)
- **Шапка** (Header) — static section: document title, number, date
- **ШапкаТаблицы** (TableHeader) — column headers for table section
- **СтрокаТаблицы** (TableRow) — repeating section for each data row
- **Подвал** (Footer) — totals, signatures
- Each is a `NamedItemCells` containing `RowsArea(begin, end)`

### Cell Content Types — Binding Semantics (v1 strict rules)
| Type | API | Use Case | Binding Level |
|------|-----|----------|---------------|
| Static text | `cell.setText(localString)` | Labels: "Перемещение товаров №" | — |
| Document binding | `cell.setParameter("НомерДокумента")` | Document-level attribute | Document |
| Detail binding | `cell.setDetailParameter("Номенклатура")` | Repeating row column from tabular section | Detail (СтрокаТаблицы) |
| Picture | `cell.setPictureParameter("...")` | Image binding | Detail |

**v1 restriction**: Each cell contains EITHER static text OR a single binding. Mixed content like `"Text [Field] more text"` is NOT supported in v1 — model must split into separate cells.

---

## Design Philosophy: Model-Driven Template Generation

### Research Findings

Best practices from AI design tools (v0.dev, Figma AI, Excel Copilot):

1. **Models excel at content + structure, not pixel-level formatting**
2. **Section-level abstraction** is optimal for tabular layouts
3. **Named styles** instead of inline formatting — model picks from vocabulary, renderer defines appearance
4. **Data bindings as bracket expressions** `[FieldName]` — natural for models, easy to parse
5. **Two-tier architecture**: model specifies WHAT, renderer decides HOW

### Abstraction Level Comparison
| Level | Model Reliability | Use |
|-------|------------------|-----|
| Pixel-level | Very poor | Never use |
| CSS/property-level | Mediocre | Sparingly |
| Component-level | Good | General UI |
| **Section/semantic-level** | **Excellent** | **1C print templates** |

### Key Principle
```
Model describes WHAT (content + structure + data bindings)
    → Renderer handles HOW (column widths, fonts, borders, binary format)
```

---

## Tool API Design: `render_template`

**Name rationale**: Codex review correctly identified that `mutate_template` over-promises.
The tool performs **full-layout replacement** (generation from scratch), not incremental mutation.
Renamed to `render_template` to reflect actual semantics.

### Input Format (optimized for LLM generation)

Model generates **section-based flat JSON** with bracket data bindings:

```json
{
  "project": "Конфигурация",
  "template_fqn": "Document.ПеремещениеТоваровВнутреннее.Template.МакетПеремещения",
  "sections": [
    {
      "name": "Шапка",
      "rows": [
        ["Перемещение товаров №", "[НомерДокумента]", "от", "[Дата]"],
        ["Склад-отправитель:", "[СкладОтправитель]"],
        ["Склад-получатель:", "[СкладПолучатель]"]
      ]
    },
    {
      "name": "ШапкаТаблицы",
      "style": "table-header",
      "rows": [
        ["№", "Номенклатура", "Количество", "Цена", "Сумма"]
      ]
    },
    {
      "name": "СтрокаТаблицы",
      "rows": [
        ["[НомерСтроки]", "[Номенклатура]", "[Количество]", "[Цена]", "[Сумма]"]
      ]
    },
    {
      "name": "Подвал",
      "rows": [
        ["", "", "", "Итого:", "[ИтогоСумма]"],
        ["Отпустил _____________", "", "Получил _____________"]
      ]
    }
  ],
  "validation_token": "..."
}
```

### Why This Works for Models
- `[НомерДокумента]` — bracket data bindings are natural for models
- `Шапка`, `СтрокаТаблицы`, `Подвал` — models know 1C conventions
- 2D array of strings — models generate tables perfectly
- `style: "table-header"` — named styles from closed enum, not raw formatting
- Flat section structure — Qwen-friendly (no deep nesting)

### v1 Binding Rules (strict)
- **Whole-cell only**: each cell is either static text OR `[Binding]`, never mixed
- **Document bindings** (Шапка/Подвал): `[НомерДокумента]` → `cell.setParameter("НомерДокумента")`
- **Detail bindings** (СтрокаТаблицы): `[Номенклатура]` → `cell.setDetailParameter("Номенклатура")`
  - Renderer auto-detects: cells in СтрокаТаблицы sections use `setDetailParameter`
- **No inline mixed content**: `"Text [Field] Text"` is forbidden — split into 3 cells

### Section Styles (closed enum)
| Style | Auto-applied Formatting |
|-------|------------------------|
| `title` | Bold, large font, merged across columns |
| `table-header` | Bold, borders all sides, gray background |
| `table-row` | Borders all sides, normal font |
| `total-row` | Bold, top border double, right-aligned numbers |
| `signature` | No borders, spacing above |
| `default` | Normal text, no borders |

### What the Renderer Auto-Handles
- Column widths (calculated from content + data type)
- Row heights (auto)
- Font (standard 1C font: Arial 10pt)
- Bold text for headers/totals (by section style)
- Cell borders (by section style)
- Cell alignment (left for text, right for numbers based on `[Parameter]` type)
- Merged cells (colspan for rows with fewer cells than max columns)
- Named areas mapping sections → NamedItemCells with RowsArea

### Data Binding Syntax
```
[FieldName]                    → document-level attribute (setParameter)
[ColumnName]                   → detail-level in СтрокаТаблицы (setDetailParameter)
[НомерСтроки]                  → auto line number
Static text                    → setText (no brackets)
```

### Validation
After model generates template, renderer validates:
1. **Binding validity**: check `[Номенклатура]` against actual document attributes/TabSections
2. **Column consistency**: all rows in a section should have compatible column count
3. **Section names**: must follow 1C conventions (Шапка, СтрокаТаблицы, etc.)
4. **No mixed content**: reject cells containing both text and `[Binding]`
5. Errors returned to model as structured feedback for correction

---

## Implementation Plan

### Phase 1: Template Metadata Creation ✅ DONE
- `add_metadata_child` creates Template with correct TemplateType
- `ensureTemplateArtifact` creates placeholder file (format needs fix)

### Phase 1.5a: Serialization Spike (IN PROGRESS)

**Goal**: Prove that `MoxelResourceMxl` + `IDtProject` wiring produces valid binary `.mxl`
that EDT re-imports correctly. Do NOT proceed to Phase 2 until this spike passes.

**Steps**:
1. Create minimal SpreadsheetDocument via MoxelFactory
2. Set up MoxelResourceMxl with proper IDtProject context
3. Serialize via `resource.save()` → verify binary MOXCEL header
4. Refresh workspace → verify EDT re-imports without errors
5. Run diagnostics → no template-related warnings

**Changes in `EdtMetadataService.ensureTemplateArtifact()`**:
```java
SpreadsheetDocument sheet = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
Columns columns = MoxelFactory.eINSTANCE.createColumns();
columns.setColumnsId(UUID.randomUUID());
columns.setSize(100); // total width in units, NOT column count
sheet.setColumns(columns);

// Serialize via EMF Resource with project context
IDtProject dtProject = projectManager.getDtProject(project);
URI fileUri = URI.createPlatformResourceURI(project.getName() + "/" + templatePath, true);
MoxelResourceMxl resource = new MoxelResourceMxl(fileUri);
resource.setDtProject(dtProject);
resource.getContents().add(sheet);
resource.save(Collections.emptyMap());
```

**Risk**: `MoxelResourceMxl` may need OSGi wiring or resource set registration.
**Fallback**: Write raw MOXCEL header + minimal binary payload directly.

### Phase 1.5b: Fix .mxl in ensureTemplateArtifact

After spike proves the approach:
- Replace XML placeholder with binary MOXCEL in `ensureTemplateArtifact()`
- Add importer synchronization wait (reuse `waitForDerivedDataConvergence` pattern)
- Add post-creation diagnostic check

### Phase 2: `render_template` Tool — Model-Driven Generation

**Goal**: Model generates print template content from natural language description.

**New tool**: `render_template` (not mutate — this is full-layout replacement)

**Architecture**:
```
Model → sections JSON → Validation (check bindings vs metadata) → Renderer → .mxl binary
```

**Required plumbing** (per Codex review):
1. Register in `ToolRegistry.registerDefaultTools()`
2. Add `ValidationOperation.RENDER_TEMPLATE`
3. Add to profile allowlists (`BuildAgentProfile`, etc.)
4. Add Qwen curated example in `QwenToolCallExamples`
5. Add Qwen description override in `QwenToolSurfaceRewriteContributor`
6. Add normalization in `MetadataRequestValidationService`

**Renderer responsibilities**:
1. Parse sections and cells from model JSON
2. Resolve data bindings:
   - `[Field]` in Шапка/Подвал → `cell.setParameter("Field")`
   - `[Column]` in СтрокаТаблицы → `cell.setDetailParameter("Column")`
3. Create `Format` objects for each section style
4. Build `Row`/`Cell` objects with proper `formatIndex` references (use EMap API)
5. Create `NamedItemCells` + `RowsArea` for each section
6. Auto-calculate column widths from content
7. Create `Merge` objects for colspan cells
8. Serialize via `MoxelResourceMxl.save()` with IDtProject context
9. Wait for importer sync + verify convergence
10. Run post-mutation diagnostics

**Tool JSON Schema** (Qwen-optimized, flat):
```json
{
  "type": "object",
  "properties": {
    "project": { "type": "string" },
    "template_fqn": { "type": "string", "description": "FQN макета: Document.X.Template.Y" },
    "sections": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "name": { "type": "string", "enum": ["Шапка", "ШапкаТаблицы", "СтрокаТаблицы", "Подвал", "Заголовок"] },
          "style": { "type": "string", "enum": ["default", "title", "table-header", "table-row", "total-row", "signature"] },
          "rows": {
            "type": "array",
            "items": { "type": "array", "items": { "type": "string" } }
          }
        },
        "required": ["name", "rows"]
      }
    },
    "validation_token": { "type": "string" }
  },
  "required": ["project", "template_fqn", "sections", "validation_token"]
}
```

### Phase 2.5: `inspect_template` Tool (BEFORE mutation support)

**Rationale** (Codex review): inspect/read first, mutate second. Required prerequisite
before any future mutation semantics.

**Tool**: `inspect_template` or extend `edt_metadata_details`

**Output**: grid of cells with text/parameters, list of named areas, column count.
Model can read existing template content.

### Phase 3: Template Mutation (FUTURE — requires inspect_template)

Only after `inspect_template` exists, consider adding incremental mutation:
- Requires stable identifiers for existing named areas
- Must preserve unknown formatting/merges
- Needs round-trip safety guarantees

### Phase 4: Template Recipes (Optional)

High-level recipes for common patterns:
- `print_document` — auto-generates Шапка + ШапкаТаблицы + СтрокаТаблицы + Подвал from document metadata
- `print_list` — list form printing template
- `label` — sticker/label template

Model calls `apply_template_recipe` and gets a pre-filled template, then can refine with `render_template`.

---

## Priority & Dependencies

| Phase | Priority | Depends On | Effort | Status |
|-------|----------|------------|--------|--------|
| Phase 1 | HIGH | — | ~2h | ✅ DONE |
| Phase 1.5a | HIGH | Phase 1 | ~2h | ✅ DONE |
| Phase 1.5b | HIGH | Phase 1.5a | ~2h | ✅ DONE (merged into 1.5a) |
| Phase 2 | HIGH | Phase 1.5b | ~10h | ✅ DONE |
| Phase 2.5 | MEDIUM | Phase 1 | ~3h | ✅ DONE |
| Phase 3 | LOW | Phase 2.5 | ~8h | Future |
| Phase 4 | LOW | Phase 2 | ~4h | Future |

## Risks (expanded per Codex review)

1. **MoxelResourceMxl IDtProjectAware wiring** — `MoxelResourceMxl` implements `IDtProjectAware`
   and requires `setDtProject(IDtProject)`. Need to obtain `IDtProject` from `IDtProjectManager`.

2. **Importer synchronization latency** — after writing `.mxl` file, `SpreadsheetDocumentImporter`
   must sync content to BM. Tool may return success before convergence. Must reuse
   `waitForDerivedDataConvergence` pattern with post-sync verification.

3. **SpreadsheetDocument BM persistence** — `template.setTemplate(SpreadsheetDocument)` fails.
   File-based approach (write .mxl via MoxelResourceMxl, let importer sync to BM) is the correct path.

4. **Data binding validation** — need to resolve `[Номенклатура]` against actual
   document metadata. Requires access to parent's tabular sections and attributes.
   Document-level bindings use `setParameter`, detail-level use `setDetailParameter`.

5. **OSGi package imports** — may need additional imports: `com._1c.g5.v8.dt.moxel.content`,
   possibly `com._1c.g5.v8.dt.core.resource` for resource set integration.

6. **Post-mutation diagnostics** — template creation/modification must be followed by
   diagnostic check to catch type warnings or export failures.

7. **Round-trip loss on existing templates** — `render_template` is full replacement only.
   Existing formatting, pictures, drawings will be lost. This is acceptable for v1
   (generation-only), but Phase 3 mutation requires `inspect_template` first.

8. **Qwen tool call accuracy** — `sections[].rows[][]` is 2-level nested. Curated example
   in `QwenToolCallExamples` is mandatory from day one. If accuracy is still weak,
   consider flattening rows to `string[]` with delimiter-based DSL.

## Qwen Optimization Checklist
- [x] `template_type` parameter in `add_metadata_child` schema (flat string enum)
- [x] Description override in `QwenToolSurfaceRewriteContributor`
- [x] `template_type` flows through validation service
- [ ] Add curated Qwen example for `render_template` tool (MANDATORY day one)
- [ ] Section `name` as closed enum (Шапка/СтрокаТаблицы/Подвал/...)
- [ ] Section `style` as closed enum (table-header/total-row/...)
- [ ] Provide 1-2 concrete JSON examples in tool prompt context
- [ ] Keep sections array shallow (max 1 level nesting)
- [ ] Qwen description override for `render_template`

## Codex Review Findings (applied)

| Finding | Severity | Action Taken |
|---------|----------|--------------|
| MoxelResourceMxl requires IDtProjectAware wiring | HIGH | Added to Phase 1.5a spike, updated API reference |
| Detail bindings need setDetailParameter, not setParameter | HIGH | Split binding semantics in v1 rules |
| Mixed text+binding in one cell is unsound | HIGH | Forbidden in v1, strict whole-cell rule |
| Missing risk: importer sync latency | HIGH | Added to risks + convergence wait in Phase 1.5b |
| getRows() is EMap not EList, size != column count | MEDIUM | Fixed in API reference section |
| mutate_template over-promises | MEDIUM | Renamed to render_template |
| inspect_template should come before mutation | MEDIUM | Moved to Phase 2.5, before Phase 3 |
| MxlSerializer fallback increases coupling | MEDIUM | Removed as fallback option |
| Qwen curated example mandatory | MEDIUM | Added to Phase 2 plumbing list |

## Files Changed (Phase 1)

- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`
  - Added `initializeTemplateIfNeeded()`, `resolveTemplateType()`, `ensureTemplateArtifact()`
  - Added imports: BasicTemplate, TemplateType, MoxelFactory, SpreadsheetDocument
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/AddMetadataChildTool.java`
  - Added `template_type` parameter to JSON schema
  - Updated `mergeFormOptions()` to handle template kind
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/validation/MetadataRequestValidationService.java`
  - Merge `template_type` from top-level payload into properties during normalization
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/QwenToolSurfaceRewriteContributor.java`
  - Updated `add_metadata_child` description for template support
- `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF`
  - Added `com._1c.g5.v8.dt.moxel` to Import-Package
