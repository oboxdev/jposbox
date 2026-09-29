package com.jposbox.printer;

import com.github.anastaciocintra.escpos.EscPos;
import com.github.anastaciocintra.escpos.EscPos.CutMode;
import com.github.anastaciocintra.escpos.EscPosConst.Justification;
import com.github.anastaciocintra.escpos.Style;
import com.github.anastaciocintra.escpos.Style.FontSize;
import com.github.anastaciocintra.escpos.barcode.BarCode;
import com.github.anastaciocintra.escpos.barcode.BarCode.BarCodeHRIPosition;
import com.github.anastaciocintra.escpos.barcode.BarCode.BarCodeSystem;
import com.github.anastaciocintra.escpos.barcode.QRCode;
import com.github.anastaciocintra.escpos.barcode.QRCode.QRErrorCorrectionLevel;
import com.github.anastaciocintra.escpos.barcode.QRCode.QRModel;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renders the legacy jIotBox XML receipt format directly to ESC/POS. Ported
 * from that project's Node.js {@code parseHtmltoPrint()}: a flat tag
 * vocabulary (receipt/img/br/hr/textnormal/textdoublewidth/textdoubleheight/
 * div/line/left/right/table/qrcode/cut/barcode, plus {@code font="a"|"b"} and
 * {@code align="left"|"center"|"right"} attributes on any tag) walked
 * depth-first, where alignment/bold/text-size are <b>global, mutable printer
 * state</b> that persists across siblings until changed again — exactly like
 * commands on a real ESC/POS printer, not scoped HTML-style inheritance.
 *
 * <p>Used by {@code print_xml_receipt} and, when a {@code default_printer_action}
 * payload turns out to be plain XML rather than an image, by
 * {@code default_printer_action} too.
 */
public class JiotBoxXmlRenderer {

    private static final Logger LOG = Logger.getLogger(JiotBoxXmlRenderer.class.getName());

    /** Tags that fully consume their own content: children are not walked further. */
    private static final Set<String> SELF_CONTAINED = Set.of(
            "img", "hr", "textnormal", "textdoublewidth", "textdoubleheight", "table", "qrcode", "barcode");

    private final int charWidth;
    private final int printerWidthPx;

    // Mutable ESC/POS-style state, persisting across siblings (matches the
    // original's use of a single stateful `printer` object).
    private Justification align = Justification.Left_Default;
    private boolean bold = false;
    private FontSize widthSize = FontSize._1;
    private FontSize heightSize = FontSize._1;

    public JiotBoxXmlRenderer(int charWidth, int printerWidthPx) {
        this.charWidth = Math.max(1, charWidth);
        this.printerWidthPx = printerWidthPx;
    }

    public void render(EscPos escpos, String xml) throws IOException {
        Document doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser());
        walkChildren(escpos, doc);
    }

    private void walkChildren(EscPos escpos, Node parent) throws IOException {
        for (Node child : parent.childNodes()) {
            if (child instanceof Element el) {
                walkElement(escpos, el);
            } else if (child instanceof TextNode tn) {
                String text = tn.text().trim();
                if (!text.isEmpty()) {
                    printText(escpos, text);
                }
            }
        }
    }

    private void walkElement(EscPos escpos, Element el) throws IOException {
        String tag = el.tagName().toLowerCase(Locale.ROOT);

        switch (tag) {
            case "left" -> align = Justification.Left_Default;
            case "right" -> align = Justification.Right;
            case "br", "line" -> escpos.feed(1);
            case "hr" -> printText(escpos, "-".repeat(charWidth));
            case "textnormal" -> {
                widthSize = FontSize._1;
                heightSize = FontSize._1;
            }
            case "textdoublewidth" -> {
                widthSize = FontSize._2;
                heightSize = FontSize._1;
            }
            case "textdoubleheight" -> {
                widthSize = FontSize._1;
                heightSize = FontSize._2;
            }
            case "cut" -> escpos.cut(CutMode.PART);
            case "img" -> printImage(escpos, el);
            case "table" -> renderTable(escpos, el);
            case "qrcode" -> printQrCode(escpos, el.text().trim());
            case "barcode" -> printBarcode(escpos, el.text().trim());
            default -> {
                // "receipt", "div", unrecognized tags: containers only.
            }
        }

        // font/align attributes apply regardless of which tag this is, and take
        // effect for whatever prints next (including this element's own
        // children) — matches the original applying them unconditionally.
        applyAttributes(el);

        if (!SELF_CONTAINED.contains(tag)) {
            walkChildren(escpos, el);
        }
    }

    private void applyAttributes(Element el) {
        String font = el.attr("font");
        if (font.equalsIgnoreCase("b")) {
            bold = true;
        } else if (font.equalsIgnoreCase("a")) {
            bold = false;
        }
        String alignAttr = el.attr("align");
        if (alignAttr.equalsIgnoreCase("center")) {
            align = Justification.Center;
        } else if (alignAttr.equalsIgnoreCase("right")) {
            align = Justification.Right;
        } else if (alignAttr.equalsIgnoreCase("left")) {
            align = Justification.Left_Default;
        }
    }

    private void printText(EscPos escpos, String text) throws IOException {
        Style style = new Style().setJustification(align).setBold(bold).setFontSize(widthSize, heightSize);
        for (String line : text.split("\n", -1)) {
            escpos.writeLF(style, line);
        }
    }

    /**
     * {@code <img src="data:image/png;base64,...">} (or a bare base64 attribute) is
     * decoded and printed as an ESC/POS raster image. There is no fixed/default
     * logo — an {@code <img>} without a usable {@code src} is skipped.
     */
    private void printImage(EscPos escpos, Element el) throws IOException {
        String src = el.attr("src");
        if (src.isBlank()) {
            LOG.fine("<img> without a 'src' attribute, skipping");
            return;
        }
        String base64 = src;
        int comma = src.indexOf(',');
        if (src.startsWith("data:") && comma >= 0) {
            base64 = src.substring(comma + 1);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "<img> src is not valid base64, skipping", e);
            return;
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            LOG.warning("<img> src did not decode to a usable image, skipping");
            return;
        }
        PrinterManager.writeRasterImage(escpos, image, printerWidthPx);
    }

    private void printQrCode(EscPos escpos, String content) throws IOException {
        if (content.isEmpty()) {
            return;
        }
        QRCode qr = new QRCode()
                .setJustification(align)
                .setModel(QRModel._2)
                .setErrorCorrectionLevel(QRErrorCorrectionLevel.QR_ECLEVEL_M_Default)
                .setSize(6);
        escpos.write(qr, content);
    }

    /** CODE128, prefixed with "{B" (code set B) as escpos-coffee requires for this symbology. */
    private void printBarcode(EscPos escpos, String content) throws IOException {
        if (content.isEmpty()) {
            return;
        }
        BarCode barCode = new BarCode()
                .setSystem(BarCodeSystem.CODE128)
                .setHRIPosition(BarCodeHRIPosition.BelowBarCode)
                .setJustification(align);
        escpos.write(barCode, "{B" + content);
    }

    /**
     * {@code <table><tr><td align="center|right|left" font="a|b" width="0.xx">...}.
     * Columns are laid out proportionally within {@code charWidth} and joined into
     * one physical line per {@code <tr>}, each cell keeping its own bold flag —
     * matching node-thermal-printer's {@code tableCustom}.
     */
    private void renderTable(EscPos escpos, Element table) throws IOException {
        for (Element row : table.select("tr")) {
            Elements cells = row.select("td");
            if (cells.isEmpty()) {
                continue;
            }
            writeTableRow(escpos, cells);
        }
    }

    private void writeTableRow(EscPos escpos, Elements cells) throws IOException {
        int n = cells.size();
        double defaultWidth = 1.0 / n;
        int[] colChars = new int[n];
        int used = 0;
        for (int i = 0; i < n; i++) {
            double fraction = defaultWidth;
            String widthAttr = cells.get(i).attr("width");
            if (!widthAttr.isBlank()) {
                try {
                    fraction = Double.parseDouble(widthAttr);
                } catch (NumberFormatException ignored) {
                    // keep the even split
                }
            }
            int chars = (i == n - 1) ? Math.max(0, charWidth - used) : (int) Math.round(fraction * charWidth);
            colChars[i] = chars;
            used += chars;
        }

        for (int i = 0; i < n; i++) {
            Element cell = cells.get(i);
            String font = cell.attr("font");
            boolean cellBold = font.equalsIgnoreCase("b");
            String alignAttr = cell.attr("align");
            Justification cellAlign = switch (alignAttr.toLowerCase(Locale.ROOT)) {
                case "center" -> Justification.Center;
                case "right" -> Justification.Right;
                default -> Justification.Left_Default;
            };
            String cellText = layoutInColumn(cell.text().trim(), colChars[i], cellAlign);
            Style style = new Style().setBold(cellBold);
            escpos.write(style, cellText);
        }
        escpos.feed(1);
    }

    private String layoutInColumn(String text, int width, Justification align) {
        if (width <= 0) {
            return "";
        }
        if (text.length() > width) {
            text = text.substring(0, width);
        }
        int pad = width - text.length();
        return switch (align) {
            case Center -> {
                int left = pad / 2;
                yield " ".repeat(left) + text + " ".repeat(pad - left);
            }
            case Right -> " ".repeat(pad) + text;
            default -> text + " ".repeat(pad);
        };
    }
}
