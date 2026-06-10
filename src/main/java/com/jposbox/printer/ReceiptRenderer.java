package com.jposbox.printer;

import com.github.anastaciocintra.escpos.EscPos;
import com.github.anastaciocintra.escpos.EscPosConst.Justification;
import com.github.anastaciocintra.escpos.Style;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Converts the simplified HTML used by Odoo POS receipts (divs/tables with
 * pos-receipt-* classes) into ESC/POS output. This is a best-effort renderer:
 * text, alignment, bold, tables and horizontal rules are supported; images
 * (logos/QR/barcodes) are skipped in this version.
 */
public class ReceiptRenderer {

    private static final Set<String> BLOCK_TAGS = Set.of(
            "div", "p", "section", "header", "footer", "ul", "li",
            "h1", "h2", "h3", "h4", "h5", "h6", "tr");

    private final int charWidth;

    public ReceiptRenderer(int charWidth) {
        this.charWidth = charWidth;
    }

    public void render(EscPos escpos, String html) throws IOException {
        Document doc = Jsoup.parse(html);
        Element root = doc.body() != null ? doc.body() : doc;
        renderChildren(escpos, root, Justification.Left_Default);
    }

    private void renderChildren(EscPos escpos, Element parent, Justification inheritedAlign) throws IOException {
        for (Node node : parent.childNodes()) {
            if (node instanceof Element) {
                renderElement(escpos, (Element) node, inheritedAlign);
            }
        }
    }

    private void renderElement(EscPos escpos, Element el, Justification inheritedAlign) throws IOException {
        String tag = el.tagName().toLowerCase();

        switch (tag) {
            case "br":
                escpos.feed(1);
                return;
            case "hr":
                writeLine(escpos, "-".repeat(charWidth), Justification.Left_Default, false);
                return;
            case "img":
                // logos/QR/barcodes not supported in this version
                return;
            case "table":
                renderTable(escpos, el, inheritedAlign);
                return;
            default:
                break;
        }

        Justification align = alignmentOf(el, inheritedAlign);

        if (hasBlockChild(el)) {
            renderChildren(escpos, el, align);
            return;
        }

        String text = el.text().trim();
        if (text.isEmpty()) {
            return;
        }
        boolean bold = isBold(el);
        writeLine(escpos, text, align, bold);
    }

    private boolean hasBlockChild(Element el) {
        for (Element child : el.children()) {
            if (BLOCK_TAGS.contains(child.tagName().toLowerCase()) || child.tagName().equalsIgnoreCase("table")) {
                return true;
            }
        }
        return false;
    }

    private boolean isBold(Element el) {
        String tag = el.tagName().toLowerCase();
        if (tag.matches("h[1-6]") || tag.equals("strong") || tag.equals("b") || tag.equals("th")) {
            return true;
        }
        if (!el.select("strong, b").isEmpty()) {
            return true;
        }
        String cls = el.className().toLowerCase();
        return cls.contains("emph") || cls.contains("title") || cls.contains("total");
    }

    private Justification alignmentOf(Element el, Justification inherited) {
        String cls = el.className().toLowerCase();
        if (cls.contains("center")) {
            return Justification.Center;
        }
        if (cls.contains("right")) {
            return Justification.Right;
        }
        if (cls.contains("left")) {
            return Justification.Left_Default;
        }
        return inherited;
    }

    private void renderTable(EscPos escpos, Element table, Justification inheritedAlign) throws IOException {
        Elements rows = table.select("tr");
        for (Element row : rows) {
            Elements cells = row.children();
            if (cells.isEmpty()) {
                continue;
            }
            boolean bold = isBold(row) || !row.select("th").isEmpty();
            String line = layoutColumns(cells);
            if (!line.isBlank()) {
                writeLine(escpos, line, Justification.Left_Default, bold);
            }
        }
    }

    /** Lays cells out left-to-right, with the last cell right-aligned to charWidth. */
    private String layoutColumns(List<Element> cells) {
        if (cells.size() == 1) {
            return cells.get(0).text().trim();
        }
        String last = cells.get(cells.size() - 1).text().trim();
        StringBuilder left = new StringBuilder();
        for (int i = 0; i < cells.size() - 1; i++) {
            String t = cells.get(i).text().trim();
            if (t.isEmpty()) {
                continue;
            }
            if (left.length() > 0) {
                left.append("  ");
            }
            left.append(t);
        }
        int pad = charWidth - left.length() - last.length();
        if (pad < 1) {
            // Not enough room: put right column on its own line, right-aligned.
            String leftLine = left.toString();
            String rightLine = " ".repeat(Math.max(0, charWidth - last.length())) + last;
            return leftLine + "\n" + rightLine;
        }
        return left + " ".repeat(pad) + last;
    }

    private void writeLine(EscPos escpos, String text, Justification align, boolean bold) throws IOException {
        Style style = new Style()
                .setJustification(align)
                .setBold(bold);
        for (String line : text.split("\n", -1)) {
            escpos.writeLF(style, line);
        }
    }
}
