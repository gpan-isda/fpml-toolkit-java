# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Compile all sources and produce handcoded.jar in the project root
mvn compile
mvn package

# Run the full test suite (working directory must be project root)
mvn test

# Run a single test class
mvn test -Dtest=ConversionSmokeTest
mvn test -Dtest=ValidationSmokeTest
mvn test -Dtest=NegativeValidationTest

# Clean build artifacts
mvn clean

# Generate Javadoc to doc/
mvn javadoc:javadoc
```

Tests **must run from the project root** (Maven Surefire is configured to do this via `<workingDirectory>` in pom.xml). The toolkit resolves `files-core/`, `files-fpml/` etc. via relative paths from the JVM working directory.

### Standalone CLI tools (run after `mvn compile`)

```powershell
# Diff two consecutive schema versions to produce a candidate-delta.xml
java -cp "build/classes;lib/xercesImpl.jar;lib/xml-apis.jar" com.handcoded.meta.tools.XsdSchemaDiffer `
     files-fpml/schemas/fpml5-12/confirmation `
     files-fpml/schemas/fpml5-13/confirmation `
     5-12 5-13 confirmation `
     files-fpml/conversion-profiles/candidate-5-12-to-5-13-confirmation.xml

# Generate ALL candidate files in one shot (PowerShell)
.\generate-all-candidates.ps1

# Human-review a candidate file to produce an approved delta profile
java -cp "build/classes;lib/xercesImpl.jar;lib/xml-apis.jar" com.handcoded.meta.tools.DeltaProfileReviewer `
     files-fpml/conversion-profiles/candidate-5-12-to-5-13-confirmation.xml `
     files-fpml/conversion-profiles/confirmation-5-12-to-5-13.xml

# Validate FpML documents against a specific version (legacy batch scripts)
# misc-fpml/Validate5-13.bat <file>
```

## Source Layout

The project has multiple source roots, all compiled together by Maven via `build-helper-plugin`:

| Source root | Contents |
|---|---|
| `src-core/` | Core framework: `meta` (Specification/Release/Conversion abstractions), `validation` (RuleSet/Rule/ValidationErrorHandler), `xml` (DOM/XPath helpers, catalog resolver), `framework` (CLI Application base) |
| `src-coreext/` | Extensions: classification, view identification |
| `src-dsig/` | XML Digital Signature support |
| `src-fpml/` | FpML-specific library: `Releases`, `Conversions`, `FpMLConversionPipeline`, validation rule sets, pipeline infrastructure |
| `src-fpmlext/` | FpML extensions (classification, UPI) |
| `samples/src-acme/` | Sample consumer code (not production) |
| `src/test/java/` | JUnit 5 test suite |

Output JAR: `handcoded.jar` (project root), classes in `build/classes/`.

## Architecture

### Core meta framework (`src-core/com/handcoded/meta/`)

The fundamental abstraction is `Specification` → `Release` → `Conversion`:

- **`Specification`** — a named standard (e.g. "FpML"). Loaded from `files-core/releases.xml` via XInclude-assembled `files-fpml/meta/*.xml` files.
- **`Release`** — one version of a spec. Subclasses: `DTDRelease` (FpML 1–3), `SchemaRelease` (FpML 4+, each view is its own release).
- **`Conversion`** — upgrades a `Document` from one `Release` to another. Two types: `DirectConversion` (one-hop, contains transform logic) and `IndirectConversion` (multi-hop, automatically chained by the framework).
- **`Helper`** — supplies caller-side data during structural conversions.  The concrete form is `Conversions.ConversionHelper`, a single generic interface with one method `getHelperValue(key, context, note)` — there is no asset-class discrimination (no FX-specific named methods).  Any structural conversion step, regardless of product type, requests values by logical key name through this interface.

All `SchemaRelease` and `Conversion` singletons are registered as static fields in `Releases.java` at class load time. The `Specification` registry is populated by parsing `files-core/releases.xml` on first access.

### FpML version model

- FpML 1–3: DTD-based, single release per version.
- FpML 4.x: Schema-based, single release per version (flat schema directory).
- FpML 5.x+: Schema-based, **one `SchemaRelease` per version-view pair** (e.g. `R5_13_CONFIRMATION`, `R5_13_REPORTING`).
- Conversions are registered in `Releases.java` as `Conversion` statics (e.g. `R5_12__R5_13_CONFIRMATION`).

### Conversion pipeline (`src-fpml/com/handcoded/fpml/`)

The modern conversion flow for programmatic use:

```
FpMLConversionPipeline  (orchestrator)
  ├── ConfigurableHelper        (supplies helper values from delta profile)
  ├── Conversions.*             (structural transforms per version hop)
  ├── PostConversionEnricher    (XPath-based DOM mutations from delta profile)
  └── PipelineResult            (converted doc + errors + dropped fields)
```

- **`ConversionDeltaProfile`** — loads a `conversion-delta.xml` file. Provides `helper-values` (key→value map for structural conversions) and `enrich` actions (XPath + value DOM mutations).
- **`ConversionDeltaProfileRegistry`** — auto-discovers all `*.xml` files in `files-fpml/conversion-profiles/` and indexes them by `from`/`to`/`view` for `withAutoProfile()` lookup.
- **`PostConversionEnricher`** — applies `<enrich>` block mutations (SET_ATTRIBUTE, REMOVE_ATTRIBUTE, SET_TEXT) and `<conditional-defaults>` injections after each hop.
- **`ValidationRuleLoader`** — merges additional `business-rules.xml`-format rule files into the global `RuleSet` registry.

### Validation framework (`src-core/com/handcoded/validation/`)

- **`RuleSet`** — named collection of `Rule` objects, looked up by name via `RuleSet.forName()`.
- **`Rule`** — evaluates a predicate against an XML node and reports violations via `ValidationErrorHandler`.
- FpML-specific rule sets live in `src-fpml/com/handcoded/fpml/validation/` (e.g. `AllRules`, `CdsRules`).
- Business rules are loaded from `files-core/business-rules.xml` and `files-fpml/` at startup.

### XML catalog and schema resolution

- `files-fpml/catalog-fpml-5-13.xml` (and per-version equivalents) maps FpML namespace URIs to local schema files.
- The catalog chain: `catalog-fpml-5-13.xml` → `catalog-fpml-5-12.xml` → … → `catalog-fpml.xml` (covers all 4.x + 5.x URIs).
- Tests bootstrap via `ToolkitTestBase.bootstrapToolkit()` which loads `catalog-fpml-5-13.xml` and pre-compiles the default schema set.
- `XmlUtility.setDefaultCatalog(...)` / `XmlUtility.getDefaultSchemaSet()` control the global resolver state.

### Data/resource files

| Path | Purpose |
|---|---|
| `files-core/releases.xml` | Master specification registry (XIncludes FpML meta files) |
| `files-fpml/meta/fpml-*.xml` | Per-version/view release metadata |
| `files-fpml/schemas/fpml*/` | FpML XSD schemas (per version, per view for 5.x) |
| `files-fpml/conversion-profiles/` | `confirmation-N-to-M.xml` — approved delta profiles (deployed); `candidate-*.xml` — machine-generated, not deployed |
| `files-fpml/examples/` | Official FpML example documents (used in tests) |
| `files-fpml/test-cases/` | Validation test cases (valid and invalid) |
| `files-core/business-rules.xml` | Core validation rule definitions |

## Adding Support for a New FpML Version

See the detailed workflow in `README.md`. In brief:

1. Copy new XSD schemas to `files-fpml/schemas/fpmlN-M/`.
2. Run `XsdSchemaDiffer` to generate `candidate-N-to-M-VIEW.xml`.
3. Run `DeltaProfileReviewer` to produce the approved `VIEW-N-to-M.xml` profile.
4. Add new `SchemaRelease` constants to `Releases.java` and, if structural changes exist, a new `Conversions.R{N}__R{M}_VIEW` inner class.
5. Register the new `Conversion` constant in `Releases.java`.
6. Add the new meta file to `files-core/releases.xml` (via XInclude).
7. Add a new `catalog-fpml-N-M.xml` and update the catalog chain.

## Dependencies

All runtime JARs are bundled in `lib/` (system-scope in Maven — no internet access needed):
- `lib/xercesImpl.jar` — Xerces 2 XML parser
- `lib/xml-apis.jar` — XML APIs
- `lib/miglayout15-swing.jar` — Swing layout (GUI tools only)

Test dependency: JUnit 5.10.2 (downloaded by Maven from central).

## Key Design Constraints

- The toolkit bootstraps from the **project root as working directory** — all file paths in `Specification.specifications()`, catalog loading, etc. are relative to CWD. Never change the working directory during a JVM run.
- FpML 4.x releases use a flat schema directory; FpML 5.x uses per-view subdirectories. `Conversions.java` must handle namespace rewriting at the 4.x → 5.x boundary (`createTargetDocument()` strips the `xsi:type` attribute that doesn't exist in 5.x).
- `Conversion.conversionFor(source, target)` auto-chains multi-hop paths via `IndirectConversion` — there is no need to build the hop chain manually.
- `ConversionDeltaProfileRegistry` is a lazy singleton; call `setProfileDirectory()` before first use if you need a non-default location.