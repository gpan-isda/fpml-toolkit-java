# The HandCoded Java Toolkit for FpML Processing 

This is the official GIT repository for the HandCoded FpML Toolkit for FpML
processing. We have moved it here from SourceForge.

The Java source files on SourceForge will no longer be updated. All updates, bug
fixes and additions will be released here.

Andrew Jacobs, April 2016<br>
Director & CTO HandCoded Software Ltd.

---

## Adding Support for a New FpML Version

This section describes the end-to-end workflow for onboarding a new FpML release
(e.g. `5-13` → `5-14`) so that documents can be automatically upgraded by
`FpMLConversionPipeline` with correct default values for any new mandatory or
conditionally-required attributes and elements.

### Overview

```
XsdSchemaDiffer          ──►  candidate-delta.xml   (machine-generated; not deployed)
  ↓ (human review)
DeltaProfileReviewer     ──►  conversion-delta.xml   (reviewed; deployed to files-fpml/conversion-profiles/)
  ↓ (runtime)
FpMLConversionPipeline   reads conversion-delta.xml via ConversionDeltaProfileRegistry
  └─ PostConversionEnricher.applyConditionalDefaults()   injects approved defaults
```

### Step 1 – Place the new XSD schemas

Copy the target-version XSD directory into `files-fpml/schemas/`, e.g.:

```
files-fpml/schemas/fpml5-14/confirmation/
```

### Step 2 – Run the XSD schema differ

```powershell
mvn compile
java -cp target/classes com.handcoded.meta.tools.XsdSchemaDiffer `
     files-fpml/schemas/fpml5-13/confirmation `
     files-fpml/schemas/fpml5-14/confirmation `
     5-13 5-14 confirmation `
     files-fpml/conversion-profiles/candidate-5-13-to-5-14-confirmation.xml
```

This produces `candidate-5-13-to-5-14-confirmation.xml` containing all detected
structural differences between the two schema versions:

| `change-type`         | Meaning |
|-----------------------|---------|
| `NEW_ATTRIBUTE`       | Attribute present in 5-14 but absent in 5-13 |
| `REQUIRED_ATTRIBUTE`  | Attribute present in both; `use` changed to `required` |
| `NEW_ELEMENT`         | Child element present in 5-14 but absent in 5-13 |
| `REQUIRED_ELEMENT`    | Child element present in both; `minOccurs` increased to ≥ 1 |
| `DROPPED_ATTRIBUTE`   | Attribute removed in 5-14 (informational, auto-skipped) |
| `DROPPED_ELEMENT`     | Element removed in 5-14 (informational, auto-skipped) |

### Step 3 – Human-in-the-loop review

Run the interactive reviewer:

```powershell
java -cp target/classes com.handcoded.meta.tools.DeltaProfileReviewer `
     files-fpml/conversion-profiles/candidate-5-13-to-5-14-confirmation.xml `
     files-fpml/conversion-profiles/confirmation-5-13-to-5-14.xml
```

For each `PENDING` entry you will be prompted to:

1. Supply a **default value** (or type `skip` to exclude).
2. Optionally **override the XPath** if the auto-suggested one is imprecise.
3. Set an optional **view scope** (e.g. `confirmation`).
4. Set an optional **product-type scope** (e.g. `swap`).
5. Choose whether injection is `only-if-absent` (default: yes).
6. Add a free-text **reviewer note**.

The session can be re-run safely — only entries still in `PENDING` state are
presented; `APPROVED` / `SKIPPED` entries are preserved.

> **Tip:** Cross-reference `files-fpml/schemas/fpml5-14/` and the FpML change log
> to determine appropriate default values.  For business-rule-mandated fields that
> are `schema-optional`, set `constraint-type="business-rule"` in your note.

### Step 4 – Inspect and commit the profile

The generated `confirmation-5-13-to-5-14.xml` (in
`files-fpml/conversion-profiles/`) follows `conversion-delta.xsd` and will
contain a `<conditional-defaults>` block:

```xml
<conversion-delta from="5-13" to="5-14"
                  from-view="confirmation" to-view="confirmation"
                  xmlns="http://www.handcoded.com/fpml/conversion-delta">

  <conditional-defaults>
    <!-- NEW_ATTRIBUTE: SomeType/newField -->
    <insert xpath="//*[local-name()='someElement']/@newField"
            value="defaultValue"
            only-if-absent="true"
            view="confirmation"
            constraint-type="schema-required"
            note="Added in FpML 5-14 per CR-XYZ"/>
  </conditional-defaults>

</conversion-delta>
```

Review the XPath expressions carefully before committing.

### Step 5 – Add a Conversion class (structural changes only)

If the new version introduces **structural** differences (element renames,
reorderings, type changes) beyond simple attribute/element additions, create a
new inner class in `src-fpml/.../Conversions.java`:

```java
public static class R5_13__R5_14_CONFIRMATION extends DirectConversion {
    public R5_13__R5_14_CONFIRMATION() {
        super(Releases.R5_13_CONFIRMATION, Releases.R5_14_CONFIRMATION);
    }
    @Override
    public Document convert(Document source, Helper helper) throws ConversionException {
        // namespace upgrade + structural transforms
        ...
    }
}
```

Then register it in `Releases.java`:

```java
public static final Conversion R5_13__R5_14_CONFIRMATION
    = new Conversions.R5_13__R5_14_CONFIRMATION();
```

For a pure namespace upgrade (no structural changes) the existing
`DirectConversion` base is sufficient and no new class is required — the
pipeline handles namespace rewriting automatically.

### Step 6 – Use the pipeline

```java
// Auto-load the matching profile from files-fpml/conversion-profiles/
FpMLConversionPipeline pipeline = new FpMLConversionPipeline.Builder("5-14")
        .withAutoProfile("5-13", "confirmation")
        .build();

PipelineResult result = pipeline.convert(document);
if (result.isSuccess()) {
    Document upgraded = result.getDocument();
}
```

Or load a specific profile explicitly:

```java
ConversionDeltaProfile profile = ConversionDeltaProfile.load(
        new File("files-fpml/conversion-profiles/confirmation-5-13-to-5-14.xml"));

FpMLConversionPipeline pipeline = new FpMLConversionPipeline.Builder("5-14")
        .withProfile(profile)
        .build();
```

### How `ConversionDeltaProfileRegistry` works

`ConversionDeltaProfileRegistry` auto-discovers all `*.xml` files in
`files-fpml/conversion-profiles/` on first use and indexes them by
`from`/`to`/`view`.  Adding a new profile file there is sufficient — no Java
code change is required for `withAutoProfile()` to pick it up.

To override the directory:

```java
ConversionDeltaProfileRegistry.setProfileDirectory(
        Paths.get("my-org/fpml-profiles"));
```

