package com.eldeekhowong.pdfcrop;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * PDFBox Text Cropper / Splitter CLI application.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>crop</b> – finds a text string on every page, crops around the match, and saves output PDF.</li>
 *   <li><b>split</b> – splits an input PDF into multiple output PDFs at every page that contains a marker text.</li>
 * </ul>
 */
public class App {

    private static final float DEFAULT_PAD = 12f;

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            System.exit(0);
        }

        String command = args[0];

        if ("split".equals(command)) {
            runSplit(args);
            return;
        }

        // "crop" subcommand (explicit) or legacy positional usage (backward compatible)
        if ("crop".equals(command)) {
            String[] cropArgs = new String[args.length - 1];
            System.arraycopy(args, 1, cropArgs, 0, cropArgs.length);
            runCrop(cropArgs);
        } else {
            runCrop(args);
        }
    }

    // -------------------------------------------------------------------------
    // Crop mode
    // -------------------------------------------------------------------------

    private static void runCrop(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Error: crop mode requires at least 3 positional arguments: <input.pdf> <output.pdf> <needle>");
            printUsage();
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args[1];
        String needle = args[2];

        float pad = DEFAULT_PAD;
        boolean caseInsensitive = hasFlag(args, "--case-insensitive");
        boolean failIfNotFound = hasFlag(args, "--fail-if-not-found");
        boolean firstOnly = hasFlag(args, "--first-only");

        // Parse --pad value
        for (int i = 3; i < args.length - 1; i++) {
            if ("--pad".equals(args[i])) {
                try {
                    pad = Float.parseFloat(args[i + 1]);
                    i++; // skip the value token
                } catch (NumberFormatException e) {
                    System.err.println("Error: --pad value must be a number, got: " + args[i + 1]);
                    System.exit(1);
                }
            }
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.err.println("Error: input file not found: " + inputPath);
            System.exit(1);
        }

        try (PDDocument doc = Loader.loadPDF(inputFile)) {
            int pageCount = doc.getNumberOfPages();
            boolean foundAny = false;

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PDPage page = doc.getPage(pageIndex);
                PDRectangle mediaBox = page.getMediaBox();

                BoundingBoxFinder finder = new BoundingBoxFinder(needle, caseInsensitive, pageIndex + 1);
                finder.setSortByPosition(true);
                finder.setStartPage(pageIndex + 1);
                finder.setEndPage(pageIndex + 1);

                StringWriter sw = new StringWriter();
                finder.writeText(doc, sw);

                PDRectangle match = finder.getMatchBounds();
                if (match != null) {
                    foundAny = true;

                    // Expand by pad and clamp to page bounds
                    float llx = Math.max(mediaBox.getLowerLeftX(), match.getLowerLeftX() - pad);
                    float lly = Math.max(mediaBox.getLowerLeftY(), match.getLowerLeftY() - pad);
                    float urx = Math.min(mediaBox.getUpperRightX(), match.getUpperRightX() + pad);
                    float ury = Math.min(mediaBox.getUpperRightY(), match.getUpperRightY() + pad);

                    PDRectangle cropRect = new PDRectangle(llx, lly, urx - llx, ury - lly);
                    page.setCropBox(cropRect);
                    page.setMediaBox(cropRect);
                }

                if (firstOnly && foundAny) {
                    break;
                }
            }

            if (failIfNotFound && !foundAny) {
                System.err.println("Error: needle not found on any page: " + needle);
                System.exit(2);
            }

            doc.save(new File(outputPath));
            System.out.println("Saved: " + outputPath);
        }
    }

    // -------------------------------------------------------------------------
    // Split mode
    // -------------------------------------------------------------------------

    /**
     * Splits an input PDF into multiple output PDFs based on a marker text.
     * The page that contains the marker becomes the FIRST page of the new output chunk.
     * If the marker is never found, nothing is written and the process exits non-zero.
     *
     * @param args full args array starting with "split"
     */
    private static void runSplit(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("Error: split mode requires: split <input.pdf> <output-dir> <marker-text>");
            printUsage();
            System.exit(1);
        }

        String inputPath = args[1];
        String outputDir = args[2];
        String marker = args[3];

        boolean caseInsensitive = hasFlag(args, "--case-insensitive");
        boolean includeFirstChunk = hasFlag(args, "--include-first-chunk");
        int minPages = 0;

        // Parse --min-pages value
        for (int i = 4; i < args.length; i++) {
            if ("--min-pages".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: --min-pages requires an integer value");
                    System.exit(1);
                }
                try {
                    minPages = Integer.parseInt(args[i + 1]);
                    i++; // skip value token
                } catch (NumberFormatException e) {
                    System.err.println("Error: --min-pages value must be an integer, got: " + args[i + 1]);
                    System.exit(1);
                }
            }
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.err.println("Error: input file not found: " + inputPath);
            System.exit(1);
        }

        try (PDDocument doc = Loader.loadPDF(inputFile)) {
            int pageCount = doc.getNumberOfPages();

            // Scan pages and collect the 0-indexed page numbers where the marker appears
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<Integer> markerPages = new ArrayList<>();

            for (int i = 0; i < pageCount; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                StringWriter sw = new StringWriter();
                stripper.writeText(doc, sw);
                String pageText = sw.toString();

                String searchText = caseInsensitive ? pageText.toLowerCase() : pageText;
                String searchMarker = caseInsensitive ? marker.toLowerCase() : marker;

                if (searchText.contains(searchMarker)) {
                    markerPages.add(i);
                }
            }

            if (markerPages.isEmpty()) {
                System.err.println("Error: split marker not found in any page: " + marker);
                System.exit(2);
            }

            // Build chunk ranges [startPage, endPage) in 0-based indices
            List<int[]> chunks = new ArrayList<>();

            // Optional first chunk: pages 0..(firstMarkerPage-1)
            int firstMarker = markerPages.get(0);
            if (includeFirstChunk && firstMarker > 0) {
                chunks.add(new int[]{0, firstMarker});
            }

            // One chunk per marker page, extending to the next marker (or end of document)
            for (int i = 0; i < markerPages.size(); i++) {
                int start = markerPages.get(i);
                int end = (i + 1 < markerPages.size()) ? markerPages.get(i + 1) : pageCount;
                chunks.add(new int[]{start, end});
            }

            // Apply --min-pages filter
            final int minPagesFilter = minPages;
            if (minPagesFilter > 0) {
                chunks.removeIf(chunk -> (chunk[1] - chunk[0]) < minPagesFilter);
            }

            if (chunks.isEmpty()) {
                System.err.println("Error: no output chunks remain after filtering (--min-pages too large?)");
                System.exit(2);
            }

            // Create output directory only when we are about to write
            File outDir = new File(outputDir);
            if (!outDir.exists() && !outDir.mkdirs()) {
                System.err.println("Error: could not create output directory: " + outputDir);
                System.exit(1);
            }

            // Write each chunk as out-NNN.pdf
            int chunkNum = 1;
            for (int[] chunk : chunks) {
                try (PDDocument chunkDoc = new PDDocument()) {
                    for (int p = chunk[0]; p < chunk[1]; p++) {
                        chunkDoc.addPage(chunkDoc.importPage(doc.getPage(p)));
                    }
                    String outName = String.format("out-%03d.pdf", chunkNum++);
                    File outFile = new File(outDir, outName);
                    chunkDoc.save(outFile);
                    System.out.println("Saved: " + outFile.getPath());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println();
        System.out.println("  Crop mode:");
        System.out.println("    mvn exec:java -Dexec.args=\"[crop] <input.pdf> <output.pdf> <needle> [options]\"");
        System.out.println();
        System.out.println("  Crop options:");
        System.out.println("    --pad <points>        Padding around match in PDF points (default: 12)");
        System.out.println("    --case-insensitive    Case-insensitive needle matching");
        System.out.println("    --fail-if-not-found   Exit non-zero if needle not found on any page");
        System.out.println("    --first-only          Crop only the first page where needle is found");
        System.out.println();
        System.out.println("  Split mode:");
        System.out.println("    mvn exec:java -Dexec.args=\"split <input.pdf> <output-dir> <marker-text> [options]\"");
        System.out.println();
        System.out.println("  Split options:");
        System.out.println("    --case-insensitive    Case-insensitive marker matching");
        System.out.println("    --include-first-chunk Include pages before the first marker as an extra first chunk");
        System.out.println("    --min-pages <n>       Skip chunks with fewer than n pages");
        System.out.println();
        System.out.println("  General:");
        System.out.println("    --help, -h            Show this help message");
    }

    // -------------------------------------------------------------------------
    // Inner class: PDFTextStripper subclass that captures TextPosition per char
    // -------------------------------------------------------------------------

    /**
     * Extends PDFTextStripper to locate the first occurrence of a needle string
     * on the target page and compute its bounding rectangle.
     */
    static class BoundingBoxFinder extends PDFTextStripper {

        private final String needle;
        private final boolean caseInsensitive;
        private final int targetPage;

        /** All character positions collected on the target page. */
        private final List<TextPosition> positions = new ArrayList<>();
        private PDRectangle matchBounds = null;

        BoundingBoxFinder(String needle, boolean caseInsensitive, int targetPage) throws IOException {
            this.needle = needle;
            this.caseInsensitive = caseInsensitive;
            this.targetPage = targetPage;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            positions.addAll(textPositions);
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            super.endPage(page);
            computeMatch(page);
            positions.clear();
        }

        private void computeMatch(PDPage page) {
            if (matchBounds != null || positions.isEmpty()) {
                return;
            }

            // Build a string from positions so we can substring-search it
            StringBuilder sb = new StringBuilder();
            for (TextPosition tp : positions) {
                sb.append(tp.getUnicode());
            }
            String pageText = sb.toString();

            String searchText = caseInsensitive ? pageText.toLowerCase() : pageText;
            String searchNeedle = caseInsensitive ? needle.toLowerCase() : needle;

            int idx = searchText.indexOf(searchNeedle);
            if (idx < 0) {
                return;
            }

            // Find the bounding box of the matched characters
            int end = idx + searchNeedle.length();
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE;
            float maxY = Float.MIN_VALUE;

            for (int i = idx; i < end && i < positions.size(); i++) {
                TextPosition tp = positions.get(i);
                float x = tp.getXDirAdj();
                float y = tp.getYDirAdj();
                float w = tp.getWidthDirAdj();
                float h = tp.getHeightDir();

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + w);
                maxY = Math.max(maxY, y + h);
            }

            // PDFBox uses bottom-left origin; YDirAdj values are in the "reading" coordinate
            // where y increases downward. We need to convert back to PDF coordinates.
            // The page height gives us the flip:
            //   pdf_y = pageHeight - yDirAdj
            // We compute pdf-space lly/ury from the min/max of the adjusted ys.
            float pageHeight = page.getMediaBox().getHeight();
            float llx = minX;
            float ury = pageHeight - minY;
            float lly = pageHeight - maxY;

            matchBounds = new PDRectangle(llx, lly, maxX - llx, ury - lly);
        }

        PDRectangle getMatchBounds() {
            return matchBounds;
        }
    }
}
