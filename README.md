# pdfbox-text-cropper

A Java 17 CLI tool powered by [Apache PDFBox](https://pdfbox.apache.org/) that finds a text string on every page of a PDF, crops around the match, and saves the result to a new PDF file.

- Pages **where the needle is found** are cropped to a padded bounding box around the match.
- Pages **where the needle is not found** are left unchanged.

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

## Run

```bash
mvn exec:java -Dexec.args="<input.pdf> <output.pdf> <needle> [options]"
```

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

---

## CLI Options

| Option | Default | Description |
|---|---|---|
| `--pad <points>` | `12` | Padding (in PDF points) to add around the matched bounding box |
| `--case-insensitive` | off | Match needle without regard to letter case |
| `--fail-if-not-found` | off | Exit with a non-zero status code if the needle is not found on any page; no output file is written |
| `--first-only` | off | Stop after cropping the first page where the needle is found |
| `--help` / `-h` | — | Print usage information and exit |

---

## How It Works

1. Opens the input PDF with PDFBox.
2. For each page, uses a custom `PDFTextStripper` subclass to capture every `TextPosition` character with its exact on-page coordinates.
3. Searches the collected characters for the first occurrence of the needle string.
4. Computes a bounding rectangle around those characters and expands it by `--pad` points in every direction, clamping to the original page bounds.
5. Sets both `CropBox` and `MediaBox` of the page to that rectangle.
6. Saves the modified document to the output path.
