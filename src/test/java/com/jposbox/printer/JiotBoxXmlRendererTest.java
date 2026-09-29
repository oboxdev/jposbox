package com.jposbox.printer;

import com.github.anastaciocintra.escpos.EscPos;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies JiotBoxXmlRenderer against the actual ESC/POS bytes escpos-coffee
 * emits (confirmed empirically -- see comments), not just "it doesn't throw".
 *
 * <p>Markers used with indexOf() below are deliberately multi-character words,
 * never single letters: escpos-coffee re-emits a full style preamble (ESC M,
 * ESC E, GS !, ESC -, ESC a, ESC 2, GS B) before every single write, and those
 * opcodes themselves contain printable ASCII letters (e.g. the 'B' in "GS B" —
 * reverse-print off). A single-letter marker like "B" can match that opcode
 * byte instead of the real printed text.
 */
class JiotBoxXmlRendererTest {

    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;

    private byte[] render(String xml) throws Exception {
        return render(xml, 32, 384);
    }

    private byte[] render(String xml, int charWidth, int printerWidthPx) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        EscPos escpos = new EscPos(buf);
        new JiotBoxXmlRenderer(charWidth, printerWidthPx).render(escpos, xml);
        escpos.flush();
        return buf.toByteArray();
    }

    private String ascii(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int v = b & 0xFF;
            sb.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
        }
        return sb.toString();
    }

    /** ESC a n : n='0' left, '1' center, '2' right (confirmed via hex dump). */
    private byte alignByte(byte[] bytes, String marker) {
        int textIdx = ascii(bytes).indexOf(marker);
        assertTrue(textIdx >= 0, "text '" + marker + "' not found in output: " + ascii(bytes));
        for (int i = textIdx; i >= 2; i--) {
            if (bytes[i - 2] == ESC && bytes[i - 1] == 'a') {
                return bytes[i];
            }
        }
        throw new AssertionError("no ESC a before '" + marker + "'");
    }

    private boolean boldBeforeText(byte[] bytes, String marker) {
        int textIdx = ascii(bytes).indexOf(marker);
        assertTrue(textIdx >= 0, "text not found: " + marker);
        for (int i = textIdx; i >= 2; i--) {
            if (bytes[i - 2] == ESC && bytes[i - 1] == 'E') {
                return bytes[i] != 0;
            }
        }
        throw new AssertionError("no ESC E before '" + marker + "'");
    }

    @Test
    void plainTextIsPrinted() throws Exception {
        String out = ascii(render("<receipt>Hello world</receipt>"));
        assertTrue(out.contains("Hello world"), out);
    }

    @Test
    void leftRightTagsSetAlignmentAndPersistAcrossSiblings() throws Exception {
        // no reset between BRAVO and CHARLIE: alignment must still be "right" for CHARLIE too.
        byte[] bytes = render("<receipt><left>ALPHA</left><right>BRAVO</right>CHARLIE</receipt>");
        assertEquals((byte) '0', alignByte(bytes, "ALPHA"));
        assertEquals((byte) '2', alignByte(bytes, "BRAVO"));
        assertEquals((byte) '2', alignByte(bytes, "CHARLIE"), "alignment must persist to the next sibling, not reset");
    }

    @Test
    void alignAttributeWorksOnAnyTag() throws Exception {
        byte[] bytes = render("<receipt><div align=\"center\">Centered</div></receipt>");
        assertEquals((byte) '1', alignByte(bytes, "Centered"));
    }

    @Test
    void boldFontAttributePersistsUntilChanged() throws Exception {
        byte[] bytes = render("<receipt><div font=\"b\">Bolded</div>StillBolded<div font=\"a\">Regular</div></receipt>");
        assertTrue(boldBeforeText(bytes, "Bolded"));
        assertTrue(boldBeforeText(bytes, "StillBolded"), "bold must persist across siblings");
        assertFalse(boldBeforeText(bytes, "Regular"));
    }

    @Test
    void textSizeTagsEmitCorrectGsBangByte() throws Exception {
        // GS ! n: 0x00 normal, 0x10 double-width, 0x01 double-height (confirmed empirically).
        assertGsBang(render("<receipt><textdoublewidth/>WideText</receipt>"), "WideText", (byte) 0x10);
        assertGsBang(render("<receipt><textdoubleheight/>TallText</receipt>"), "TallText", (byte) 0x01);
        assertGsBang(render("<receipt><textdoublewidth/>Wide<textnormal/>Normal</receipt>"), "Normal", (byte) 0x00);
    }

    private void assertGsBang(byte[] bytes, String marker, byte expected) {
        int textIdx = ascii(bytes).indexOf(marker);
        assertTrue(textIdx >= 0, marker);
        for (int i = textIdx; i >= 2; i--) {
            if (bytes[i - 2] == GS && bytes[i - 1] == '!') {
                assertEquals(expected, bytes[i], "GS ! byte before '" + marker + "'");
                return;
            }
        }
        throw new AssertionError("no GS ! before " + marker);
    }

    @Test
    void hrDrawsADashedLineFullCharWidth() throws Exception {
        byte[] bytes = render("<receipt><hr/></receipt>", 20, 384);
        assertTrue(ascii(bytes).contains("-".repeat(20)));
    }

    @Test
    void brAndLineFeedWithoutPrintingText() throws Exception {
        byte[] bytes = render("<receipt>FirstLine<br/>SecondLine<line/>ThirdLine</receipt>");
        String out = ascii(bytes);
        assertTrue(out.contains("FirstLine"));
        assertTrue(out.contains("SecondLine"));
        assertTrue(out.contains("ThirdLine"));
    }

    @Test
    void cutEmitsGsVMidStream() throws Exception {
        byte[] bytes = render("<receipt>Before<cut/>After</receipt>");
        // GS V '1' (0x31) is the partial-cut sequence this app uses everywhere (CutMode.PART).
        boolean found = false;
        for (int i = 0; i + 2 < bytes.length; i++) {
            if (bytes[i] == GS && bytes[i + 1] == 'V') {
                found = true;
                break;
            }
        }
        assertTrue(found, "expected a GS V cut command: " + ascii(bytes));
    }

    @Test
    void qrCodeDoesNotThrowAndProducesBytes() throws Exception {
        byte[] bytes = render("<receipt><qrcode>https://example.com</qrcode></receipt>");
        assertTrue(bytes.length > 20);
    }

    @Test
    void barcodeDoesNotThrowAndProducesBytes() throws Exception {
        byte[] bytes = render("<receipt><barcode>ABC12345</barcode></receipt>");
        assertTrue(bytes.length > 20);
        assertTrue(ascii(bytes).contains("ABC12345"));
    }

    @Test
    void tableLaysOutColumnsProportionallyAndTrimsToWidth() throws Exception {
        String xml = "<receipt><table><tr>"
                + "<td width=\"0.5\">Item</td>"
                + "<td width=\"0.5\" align=\"right\">Price</td>"
                + "</tr></table></receipt>";
        byte[] bytes = render(xml, 20, 384);
        String out = ascii(bytes);
        // 20 chars wide, 50/50 split -> col0 = "Item" left-padded to 10 chars,
        // col1 = "Price" right-aligned within 10 chars. Each column is one
        // independent write() call, so its own text+padding IS contiguous in
        // the byte stream, but a fresh style preamble separates the two
        // columns -- so check them independently, not concatenated.
        assertTrue(out.contains("Item      "), out); // "Item" + 6 spaces = 10
        assertTrue(out.contains("     Price"), out); // 5 spaces + "Price" = 10
    }

    @Test
    void tablePerCellBoldIsIndependent() throws Exception {
        String xml = "<receipt><table><tr>"
                + "<td font=\"b\">BoldCell</td>"
                + "<td font=\"a\">PlainCell</td>"
                + "</tr></table></receipt>";
        byte[] bytes = render(xml, 20, 384);
        assertTrue(boldBeforeText(bytes, "BoldCell"));
        assertFalse(boldBeforeText(bytes, "PlainCell"));
    }

    @Test
    void imgWithDataUriIsRenderedAsRasterImage() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 8, 8);
        g.dispose();
        ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", pngBytes);
        String base64 = Base64.getEncoder().encodeToString(pngBytes.toByteArray());

        byte[] bytes = render("<receipt><img src=\"data:image/png;base64," + base64 + "\"/></receipt>", 32, 64);

        // GS v 0 is the raster bit image command.
        boolean found = false;
        for (int i = 0; i + 2 < bytes.length; i++) {
            if (bytes[i] == GS && bytes[i + 1] == 'v' && bytes[i + 2] == '0') {
                found = true;
                break;
            }
        }
        assertTrue(found, "expected a GS v 0 raster image command");
    }

    @Test
    void imgWithoutSrcIsSkippedInsteadOfFailing() throws Exception {
        byte[] bytes = render("<receipt><img/>AfterImage</receipt>"); // must not throw
        assertTrue(ascii(bytes).contains("AfterImage"));
    }

    @Test
    void mixedInlineTextAndTagsBothPrint() throws Exception {
        byte[] bytes = render("<receipt>Hello <div font=\"b\">World</div> Goodbye</receipt>");
        String out = ascii(bytes);
        assertTrue(out.contains("Hello"));
        assertTrue(out.contains("World"));
        assertTrue(out.contains("Goodbye"));
    }

    @Test
    void ampersandEntityIsDecoded() throws Exception {
        byte[] bytes = render("<receipt>Fish &amp; Chips</receipt>");
        assertTrue(ascii(bytes).contains("Fish & Chips"), ascii(bytes));
    }
}
