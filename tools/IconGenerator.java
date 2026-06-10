import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generates the jPosBox app icon (printer + receipt, green theme matching the
 * tray icon) at high resolution. Run with: java tools/IconGenerator.java
 * Output: src/main/resources/icons/icon-1024.png
 */
public class IconGenerator {
    public static void main(String[] args) throws Exception {
        int size = 1024;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Background: rounded square, dark green
        g.setColor(new Color(0x1B, 0x5E, 0x20));
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, size * 0.22, size * 0.22));

        // Receipt paper sticking out the top
        g.setColor(Color.WHITE);
        int paperW = (int) (size * 0.46);
        int paperX = (size - paperW) / 2;
        g.fillRect(paperX, (int) (size * 0.10), paperW, (int) (size * 0.34));
        // Zig-zag torn edge at top of paper
        int zig = (int) (size * 0.03);
        Polygon torn = new Polygon();
        for (int x = paperX; x <= paperX + paperW; x += zig) {
            torn.addPoint(x, (int) (size * 0.10) + (((x - paperX) / zig) % 2 == 0 ? 0 : zig / 2));
        }
        torn.addPoint(paperX + paperW, (int) (size * 0.10) - zig);
        torn.addPoint(paperX, (int) (size * 0.10) - zig);
        g.setColor(new Color(0x1B, 0x5E, 0x20));
        g.fill(torn);
        g.setColor(Color.WHITE);
        g.fillRect(paperX, (int) (size * 0.10), paperW, (int) (size * 0.34));

        // Printed lines on the receipt
        g.setColor(new Color(0x42, 0x42, 0x42));
        int lineX = paperX + (int) (size * 0.05);
        int lineW = paperW - (int) (size * 0.10);
        int lineH = (int) (size * 0.018);
        int gap = (int) (size * 0.045);
        int ly = (int) (size * 0.16);
        for (int i = 0; i < 4; i++) {
            int w = (i == 3) ? lineW * 2 / 3 : lineW;
            g.fillRoundRect(lineX, ly, w, lineH, lineH, lineH);
            ly += gap;
        }

        // Printer body
        g.setColor(new Color(0x4C, 0xAF, 0x50));
        int bodyW = (int) (size * 0.74);
        int bodyH = (int) (size * 0.30);
        int bodyX = (size - bodyW) / 2;
        int bodyY = (int) (size * 0.46);
        RoundRectangle2D body = new RoundRectangle2D.Double(bodyX, bodyY, bodyW, bodyH, size * 0.07, size * 0.07);
        g.fill(body);

        // Slot where paper exits
        g.setColor(new Color(0x1B, 0x5E, 0x20));
        int slotW = (int) (size * 0.50);
        int slotH = (int) (size * 0.025);
        g.fillRoundRect((size - slotW) / 2, bodyY + (int) (size * 0.04), slotW, slotH, slotH, slotH);

        // Printer base / feet
        g.setColor(new Color(0x2E, 0x7D, 0x32));
        int baseW = (int) (size * 0.82);
        int baseH = (int) (size * 0.10);
        int baseX = (size - baseW) / 2;
        int baseY = bodyY + bodyH - (int) (size * 0.03);
        g.fillRoundRect(baseX, baseY, baseW, baseH, (int) (size * 0.04), (int) (size * 0.04));

        // Status light
        g.setColor(new Color(0xA5, 0xD6, 0xA7));
        int lightSize = (int) (size * 0.045);
        g.fillOval(bodyX + (int) (size * 0.06), bodyY + (int) (size * 0.18), lightSize, lightSize);

        g.dispose();

        File outDir = new File("src/main/resources/icons");
        outDir.mkdirs();
        ImageIO.write(image, "png", new File(outDir, "icon-1024.png"));
        System.out.println("Wrote " + new File(outDir, "icon-1024.png").getAbsolutePath());
    }
}
