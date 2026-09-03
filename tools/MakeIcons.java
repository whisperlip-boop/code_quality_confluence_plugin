import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Produces the two UPM icons from the 512x512 source artwork.
 *
 * UPM serves these as image/png and ignores an SVG, so both sizes are rasterised here rather
 * than referenced directly. Regenerate whenever images/code-quality.png changes:
 *
 *   javac -d /tmp/cq-icons tools/MakeIcons.java
 *   java -cp /tmp/cq-icons MakeIcons src/main/resources/images
 */
public final class MakeIcons
{
    public static void main(String[] args) throws Exception
    {
        File dir = new File(args[0]);
        BufferedImage source = ImageIO.read(new File(dir, "code-quality.png"));
        if (source == null)
        {
            throw new IllegalStateException("Cannot read " + new File(dir, "code-quality.png"));
        }
        ImageIO.write(scale(source, 72), "png", new File(dir, "pluginIcon.png"));
        ImageIO.write(scale(source, 144), "png", new File(dir, "pluginLogo.png"));
        System.out.println("wrote pluginIcon.png (72) and pluginLogo.png (144) to " + dir);
    }

    /**
     * Halves repeatedly before the final step. A single one-shot draw from 512 to 72 aliases
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
