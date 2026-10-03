import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Renders the launcher icon geometry (same as res/drawable/ic_launcher_*.xml) to PNGs:
 * legacy mipmaps for API 24-25, the Play Store icon and the TV banner in all densities.
 * Keep the glyph() geometry in sync with ic_launcher_foreground.xml.
 *
 * Usage (from the repo root): java tools/IconGen.java app/src/main/res app/src/main/ic_launcher-playstore.png
 */
public class IconGen {
    static final Color START = new Color(0x4C, 0x8D, 0xFF);
    static final Color END = new Color(0x23, 0x46, 0xC9);

    public static void main(String[] args) throws Exception {
        String res = args[0];
        int[] sizes = {48, 72, 96, 144, 192};
        String[] dirs = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        for (int i = 0; i < sizes.length; i++) {
            write(icon(sizes[i], false), res + "/mipmap-" + dirs[i] + "/ic_launcher.png");
            write(icon(sizes[i], true), res + "/mipmap-" + dirs[i] + "/ic_launcher_round.png");
        }
        write(fullBleed(512), args[1]);
        String[] bannerDirs = {"mdpi", "hdpi", "xhdpi", "xxhdpi"};
        double[] bannerScales = {1, 1.5, 2, 3};
        for (int i = 0; i < bannerDirs.length; i++) {
            int w = (int) (320 * bannerScales[i]), h = (int) (180 * bannerScales[i]);
            write(banner(w, h), res + "/drawable-" + bannerDirs[i] + "/banner.png");
        }
    }

    static Graphics2D setup(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /** Legacy (pre-API 26) icon: shape with a small margin, adaptive viewport 18..90 mapped onto it. */
    static BufferedImage icon(int size, boolean round) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = setup(img);
        double m = size / 48.0 * 2; // 2dp margin like the platform templates
        double s = size - 2 * m;
        Shape shape = round
            ? new Ellipse2D.Double(m, m, s, s)
            : new RoundRectangle2D.Double(m, m, s, s, s * 0.3, s * 0.3);
        g.setPaint(new GradientPaint((float) m, (float) m, START, (float) (m + s), (float) (m + s), END));
        g.fill(shape);
        g.setClip(shape);
        // Visible part of the adaptive canvas is roughly 18..90; map it onto the shape.
        AffineTransform t = new AffineTransform();
        t.translate(m, m);
        t.scale(s / 72.0, s / 72.0);
        t.translate(-18, -18);
        glyph(g, t);
        g.dispose();
        return img;
    }

    /** Play Store icon: square, full bleed (the store applies its own mask). */
    static BufferedImage fullBleed(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = setup(img);
        g.setPaint(new GradientPaint(0, 0, START, size, size, END));
        g.fillRect(0, 0, size, size);
        AffineTransform t = new AffineTransform();
        t.scale(size / 72.0, size / 72.0);
        t.translate(-18, -18);
        glyph(g, t);
        g.dispose();
        return img;
    }

    /** TV banner 320x180dp at xhdpi: glyph on the left, app name on the right. */
    static BufferedImage banner(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = setup(img);
        g.setPaint(new GradientPaint(0, 0, START, w, h, END));
        g.fillRect(0, 0, w, h);
        double glyphSize = h * 0.8;
        AffineTransform t = new AffineTransform();
        t.translate(h * 0.12, (h - glyphSize) / 2);
        t.scale(glyphSize / 66.0, glyphSize / 66.0);
        t.translate(-21, -21);
        glyph(g, t);
        g.setColor(Color.WHITE);
        Font font = new Font("SansSerif", Font.BOLD, (int) (h * 0.19));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        float x = (float) (h * 0.12 + glyphSize + h * 0.04);
        float lineGap = fm.getAscent() * 1.05f;
        float top = (h - lineGap - fm.getDescent()) / 2f + fm.getAscent() - lineGap / 2f + fm.getDescent() / 2f;
        g.drawString("Torrent", x, top);
        g.drawString("Player", x, top + lineGap);
        g.dispose();
        return img;
    }

    /** Foreground geometry in the 108-unit adaptive icon coordinate space. */
    static void glyph(Graphics2D g, AffineTransform t) {
        g.setColor(Color.WHITE);
        Path2D tri = new Path2D.Double();
        tri.moveTo(44, 31);
        tri.lineTo(44, 63);
        tri.lineTo(71, 47);
        tri.closePath();
        Shape triT = t.createTransformedShape(tri);
        g.fill(triT);
        float scale = (float) t.getScaleX();
        g.setStroke(new BasicStroke(5 * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(triT);
        double[][] pieces = {{34, 10}, {46, 10}, {58, 10}, {70, 6}};
        for (int i = 0; i < pieces.length; i++) {
            g.setColor(i == 3 ? new Color(255, 255, 255, 102) : Color.WHITE);
            g.fill(t.createTransformedShape(new RoundRectangle2D.Double(pieces[i][0], 71, pieces[i][1], 5, 3, 3)));
        }
    }

    static void write(BufferedImage img, String path) throws Exception {
        File f = new File(path);
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
    }
}
