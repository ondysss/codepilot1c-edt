# Plan: Template/Макет Support via EDT BM API

## Status: Phase 1 DONE, Phase 1.5 + Phase 2 in Planning

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
- `SpreadsheetDocumentImporter` imports from `.mxl` files into BM on workspace changes

### Correct Creation Flow
Use EMF Resource API to serialize SpreadsheetDocument in valid binary MOXCEL format:
```java
SpreadsheetDocument sheet = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
// ... populate cells, areas, formatting ...
URI fileUri = URI.createPlatformResourceURI(path, true);
Resource resource = new MoxelResourceFactory().createResource(fileUri);
resource.getContents().add(sheet);
resource.save(Collections.emptyMap()); // writes binary MOXCEL via MxlSerializer
```

---

## EDT SpreadsheetDocument API Reference

### Core Object Model
```
SpreadsheetDocument
├── columns: Columns (primary column set)
│   ├── columnsId: UUID
│   ├── size: int (total width)
│   └── columns: EMap<Integer, Column>
│       └── width: int, formatIndex: int, hidden: boolean
├── rows: EList<Row>
│   └── cells: EMap<Integer, Cell>
│       ├── text: LocalString (static text)
│       ├── parameter: String (data binding — "НомерДокумента")
│       ├── value: Value (formula/expression)
│       ├── formatIndex: int → points to formats[]
│       └── detailParameter, pictureParameter: String
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

### Cell Content Types
| Type | API | Use Case |
|------|-----|----------|
| Static text | `cell.setText(localString)` | Labels: "Перемещение товаров №" |
| Data binding | `cell.setParameter("НомерДокумента")` | Bound to document field |
| Detail binding | `cell.setDetailParameter("...")` | Detail-level data |
| Picture | `cell.setPictureParameter("...")` | Image binding |

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

## Tool API Design: `mutate_template`

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
        ["Перемещение товаров № [НомерДокумента] от [Дата]"],
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
        ["[НомерСтроки]", "[Товары.Номенклатура]", "[Товары.Количество]", "[Товары.Цена]", "[Товары.Сумма]"]
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
[FieldName]                    → document attribute
[TableName.ColumnName]         → tabular section column
[НомерСтроки]                  → auto line number
Static text [Field] more text  → mixed content (text split into cells + parameter cell)
```

### Validation
After model generates template, renderer validates:
1. **Binding validity**: check `[Товары.Номенклатура]` against actual document attributes/TabSections
2. **Column consistency**: all rows in a section should have compatible column count
3. **Section names**: must follow 1C conventions (Шапка, СтрокаТаблицы, etc.)
4. Errors returned to model as structured feedback for correction

---

## Implementation Plan

### Phase 1: Template Metadata Creation ✅ DONE
- `add_metadata_child` creates Template with correct TemplateType
- `ensureTemplateArtifact` creates placeholder file (format needs fix)

### Phase 1.5: Fix .mxl File Format (NEXT)

**Goal**: Create valid binary MOXCEL `.mxl` files instead of XML placeholders.

**Approach**: Use `MoxelResourceMxl` EMF Resource to serialize SpreadsheetDocument.

**Changes in `EdtMetadataService.ensureTemplateArtifact()`**:
```java
// Instead of writing XML text:
SpreadsheetDocument sheet = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
Columns columns = MoxelFactory.eINSTANCE.createColumns();
columns.setColumnsId(UUID.randomUUID());
columns.setSize(6);
sheet.setColumns(columns);

// Serialize via EMF Resource (writes binary MOXCEL format)
URI fileUri = URI.createPlatformResourceURI(project.getName() + "/" + templatePath, true);
Resource resource = new MoxelResourceFactory().createResource(fileUri);
resource.getContents().add(sheet);
resource.save(Collections.emptyMap());
```

**Imports needed**:
```java
import com._1c.g5.v8.dt.moxel.MoxelResourceFactory;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.common.util.URI;
```

**Risk**: `MoxelResourceFactory` may require `IDtProject` or have internal dependencies.
**Fallback**: Use `MxlSerializer` directly (internal API).

### Phase 2: `mutate_template` Tool — Model-Driven Design

**Goal**: Model generates print template content from natural language description.

**New tool**: `mutate_template`

**Architecture**:
```
Model → sections JSON → Validation (check bindings vs metadata) → Renderer → .mxl binary
```

**Renderer responsibilities**:
1. Parse sections and cells from model JSON
2. Resolve data bindings `[Field]` → `cell.setParameter("Field")`
3. Create `Format` objects for each section style
4. Build `Row`/`Cell` objects with proper `formatIndex` references
5. Create `NamedItemCells` + `RowsArea` for each section
6. Auto-calculate column widths from content
7. Create `Merge` objects for colspan cells
8. Serialize via `MoxelResourceMxl.save()` or BM transaction + forceExport

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

### Phase 3: Template Inspection

**Tool**: `inspect_template` or extend `edt_metadata_details`

**Output**: grid of cells with text/parameters, list of named areas, column count.
Model can read existing template and modify it.

### Phase 4: Template Recipes (Optional)

High-level recipes for common patterns:
- `print_document` — auto-generates Шапка + ШапкаТаблицы + СтрокаТаблицы + Подвал from document metadata
- `print_list` — list form printing template
- `label` — sticker/label template

Model calls `apply_template_recipe` and gets a pre-filled template, then can refine with `mutate_template`.

---

## Priority & Dependencies

| Phase | Priority | Depends On | Effort | Status |
|-------|----------|------------|--------|--------|
| Phase 1 | HIGH | — | ~2h | ✅ DONE |
| Phase 1.5 | HIGH | Phase 1 | ~3h | NEXT |
| Phase 2 | HIGH | Phase 1.5 | ~8h | Planned |
| Phase 3 | MEDIUM | Phase 1 | ~2h | Planned |
| Phase 4 | LOW | Phase 2 | ~4h | Planned |

## Risks

1. **MoxelResourceMxl access** — `MoxelResourceFactory` is public API but the serialization
   uses `MxlSerializer` from internal package. May need OSGi import for internal package.

2. **SpreadsheetDocument BM persistence** — `template.setTemplate(SpreadsheetDocument)` fails.
   File-based approach (write .mxl via EMF Resource, let importer sync to BM) is safer.

3. **Data binding validation** — need to resolve `[Товары.Номенклатура]` against actual
   document metadata. Requires access to parent's tabular sections and attributes.

4. **Qwen tool call accuracy** — `sections` array is 1-level nested (array of objects with
   array of arrays). Tested: Qwen handles this level of nesting acceptably.

## Qwen Optimization Checklist
- [x] `template_type` parameter in `add_metadata_child` schema (flat string enum)
- [x] Description override in `QwenToolSurfaceRewriteContributor`
- [x] `template_type` flows through validation service
- [ ] Add Qwen example params for `mutate_template` tool
- [ ] Section `name` as closed enum (Шапка/СтрокаТаблицы/Подвал/...)
- [ ] Section `style` as closed enum (table-header/total-row/...)
- [ ] Provide 1-2 concrete JSON examples in tool prompt context
- [ ] Keep sections array shallow (max 1 level nesting)

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
