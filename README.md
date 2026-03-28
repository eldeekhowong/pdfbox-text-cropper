# pdfbox-text-cropper

A Java 17 CLI tool powered by [Apache PDFBox](https://pdfbox.apache.org/) with two modes:

- **Crop** – finds a text string on every page of a PDF, crops around the match, and saves the result to a new PDF file.
- **Split** – splits a PDF into multiple output PDFs at every page that contains a specified marker text.

---

## Requirements

- Java 17+
- Maven 3.6+

---

## Build

```bash
mvn clean package
```

---

## Crop Mode

Crops pages around a matching text string and saves the result.

- Pages **where the needle is found** are cropped to a padded bounding box around the match.
- Pages **where the needle is not found** are left unchanged.

### Usage

```bash
mvn exec:java -Dexec.args="[crop] <input.pdf> <output.pdf> <needle> [options]"
```

The `crop` subcommand keyword is optional for backward compatibility.

### Examples

Crop every page where `"Invoice"` appears, with 12-point padding (default):

```bash
mvn exec:java -Dexec.args="in.pdf out.pdf Invoice"
```

Case-insensitive search with 20-point padding:

```bash
mvn exec:java -Dexec.args="in.pdf out.pdf invoice --case-insensitive --pad 20"
```

Fail with a non-zero exit code if the needle is not found on any page:

```bash
mvn exec:java -Dexec.args="in.pdf out.pdf \"Total Amount\" --fail-if-not-found"
```

Crop only the first page where the needle is found:

```bash
mvn exec:java -Dexec.args="in.pdf out.pdf Summary --first-only"
```

### Crop Options

| Option | Default | Description |
|---|---|---|
| `--pad <points>` | `12` | Padding (in PDF points) to add around the matched bounding box |
| `--case-insensitive` | off | Match needle without regard to letter case |
| `--fail-if-not-found` | off | Exit with a non-zero status code if the needle is not found on any page; no output file is written |
| `--first-only` | off | Stop after cropping the first page where the needle is found |

---

## Split Mode

Splits a PDF into multiple output PDFs based on a marker text. The page that contains the marker text becomes the **first page of the new output chunk** (split occurs *before* that page).

- Output files are named `out-001.pdf`, `out-002.pdf`, … and written into the specified output directory.
- If the marker text is **never found** in the document, nothing is written and the process exits with a non-zero code.
- Only non-empty chunks are written.

### Usage

```bash
mvn exec:java -Dexec.args="split <input.pdf> <output-dir> <marker-text> [options]"
```

### Examples

Split a document wherever `"CHAPTER"` appears (each chapter page starts a new PDF):

```bash
mvn exec:java -Dexec.args="split book.pdf ./chapters CHAPTER"
```

Case-insensitive split on `"invoice"`:

```bash
mvn exec:java -Dexec.args="split invoices.pdf ./out invoice --case-insensitive"
```

Include the pages before the first marker as an extra first output chunk:

```bash
mvn exec:java -Dexec.args="split report.pdf ./out \"Section\" --include-first-chunk"
```

Skip any output chunks that are shorter than 3 pages:

```bash
mvn exec:java -Dexec.args="split doc.pdf ./out MARKER --min-pages 3"
```

### Split Options

| Option | Default | Description |
|---|---|---|
| `--case-insensitive` | off | Match marker text without regard to letter case |
| `--include-first-chunk` | off | Include pages before the first marker as an extra first output chunk (skipped if empty) |
| `--min-pages <n>` | — | Skip chunks that contain fewer than `n` pages |

---

## General Options

| Option | Description |
|---|---|
| `--help` / `-h` | Print usage information and exit |

---

## How It Works

### Crop
1. Opens the input PDF with PDFBox.
2. For each page, uses a custom `PDFTextStripper` subclass to capture every `TextPosition` character with its exact on-page coordinates.
3. Searches the collected characters for the first occurrence of the needle string.
4. Computes a bounding rectangle around those characters and expands it by `--pad` points in every direction, clamping to the original page bounds.
5. Sets both `CropBox` and `MediaBox` of the page to that rectangle.
6. Saves the modified document to the output path.

### Split
1. Opens the input PDF with PDFBox.
2. For each page, uses `PDFTextStripper` to extract all text and checks whether it contains the marker string.
3. Collects the page indices where the marker is found.
4. If no pages contain the marker, exits non-zero without writing any files.
5. Builds chunk ranges: each marker page starts a new chunk extending to the next marker (or the end of the document). Optionally includes pages before the first marker as an extra first chunk.
6. Filters out chunks smaller than `--min-pages` (if specified).
7. Creates the output directory and writes each chunk as `out-NNN.pdf`.
