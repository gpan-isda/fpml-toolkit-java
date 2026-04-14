# Archive

This directory contains legacy FpML artefacts that are no longer part of the
active toolkit but are retained here for historical reference.

## Contents

| Path | Description |
|------|-------------|
| `files-fpml/meta/` | Release metadata descriptors for FpML 1.0, 2.0, 3.0 and ACME/DSIG extensions |
| `files-fpml/data/` | Scheme data files for FpML 1.0, 2.0 and 3.0 |
| `files-fpml/schemas/` | XSD schema trees for FpML 1.0, 2.0, 3.0, ACME 1.0/2.0 and DSIG |
| `files-fpml/examples/` | Example documents for FpML 1.0, 2.0 and 3.0 |
| `files-fpml/test-cases/` | Test cases for FpML 1.0 and 3.0 |
| `misc-fpml/` | Legacy validation batch scripts for FpML 1.0, 2.0 and 3.0 |

## Why were these archived?

* **FpML 1.0 / 2.0 / 3.0** – pre-4.x versions are outside the supported
  conversion range (4.6 ↔ 5.x).  The structural conversion classes
  `R1_0__R2_0`, `R2_0__R3_0` and `R3_0__R4_0` are retained in
  `Conversions.java` as they form the entry of the chain from any older source
  document.
* **ACME / DSIG** – sample extension schemas only; moved to `samples/`.

These files are **not** on the active Java source path and are excluded from
the build.

