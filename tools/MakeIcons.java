import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Rasterises the 512x512 source artwork down to the sizes the plugin actually serves.
 *
 * UPM and the macro browser want PNGs and ignore an SVG, and the row-action icons are used as
 * CSS masks at 18px - shipping the 512px originals for those would be 50KB of download for
 * four glyphs. Regenerate whenever the sources change:
 *
 *   javac -d /tmp/cq-icons tools/MakeIcons.java
 *   java -cp /tmp/cq-icons MakeIcons src/main/resources/images
 */
public final class MakeIcons
{
    /** source name, output name, size. */
    private static final String[][] OUTPUTS = {
            { "code-quality", "pluginIcon", "72" },
            { "code-quality", "pluginLogo", "144" },
            // The macro browser renders its icon at 80px.
            { "code-quality", "macroIcon", "80" },
            // Row actions are masked at 18px; 36 covers a 2x display.
            { "analyze", "analyze-icon", "36" },
            { "report", "report-icon", "36" },
            { "edit", "edit-icon", "36" },
            { "delete", "delete-icon", "36" },
    };

    public static void main(String[] args) throws Exception
    {
        File dir = new File(args[0]);
        for (String[] output : OUTPUTS)
        {
            File source = new File(dir, output[0] + ".png");
            if (!source.isFile())
            {
                System.out.println("skip " + source.getName() + " (missing)");
                continue;
            }
            BufferedImage image = ImageIO.read(source);
            if (image == null)
            {
                System.out.println("skip " + source.getName() + " (unreadable)");
                continue;
            }
            int size = Integer.parseInt(output[2]);
            ImageIO.write(scale(image, size), "png", new File(dir, output[1] + ".png"));
            System.out.println("wrote " + output[1] + ".png (" + size + ")");
        }
    }

    /**
     * Halves repeatedly before the final step. A single one-shot draw from 512 to 36 aliases
     * badly with bilinear filtering, and stepwise reduction is the cheap fix.
     */
    private static BufferedImage scale(BufferedImage source, int target)
    {
        BufferedImage current = source;
        while (current.getWidth() / 2 >= target)
        {
            current = draw(current, current.getWidth() / 2);
        }
        return current.getWidth() == target ? current : draw(current, target);
    }

    private static BufferedImage draw(BufferedImage source, int size)
    {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();
        return out;
    }
}
