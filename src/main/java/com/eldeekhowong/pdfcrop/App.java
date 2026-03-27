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
 * PDFBox Text Cropper CLI application.
 *
 * <p>Finds a text string on every page, crops around the match, and saves output PDF.
 * Pages where the needle is not found are left unchanged.
 */
public class App {

    private static final float DEFAULT_PAD = 12f;

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            System.exit(0);
        }

        if (args.length < 3) {
            System.err.println("Error: requires at least 3 positional arguments: <input.pdf> <output.pdf> <needle>");
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

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("Usage: mvn exec:java -Dexec.args=\"<input.pdf> <output.pdf> <needle> [options]\"");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --pad <points>        Padding around match in PDF points (default: 12)");
        System.out.println("  --case-insensitive    Case-insensitive needle matching");
        System.out.println("  --fail-if-not-found   Exit non-zero if needle not found on any page");
        System.out.println("  --first-only          Crop only the first page where needle is found");
        System.out.println("  --help, -h            Show this help message");
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
