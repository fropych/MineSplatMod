import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * Development-only helper. It emits alpha-weighted numeric sRGB face averages;
 * source Minecraft textures are never copied into the MineSplat JAR.
 */
public final class PaletteAverage {
    private PaletteAverage() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) {
            System.err.println("Usage: java tools/PaletteAverage.java <texture.png> [...]");
            System.exit(2);
        }
        for (String argument : arguments) {
            BufferedImage image = ImageIO.read(Path.of(argument).toFile());
            if (image == null) {
                throw new IllegalArgumentException("Not an image: " + argument);
            }
            double red = 0;
            double green = 0;
            double blue = 0;
            double alphaTotal = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    double alpha = ((argb >>> 24) & 0xff) / 255.0;
                    red += ((argb >>> 16) & 0xff) * alpha;
                    green += ((argb >>> 8) & 0xff) * alpha;
                    blue += (argb & 0xff) * alpha;
                    alphaTotal += alpha;
                }
            }
            if (alphaTotal == 0) {
                throw new IllegalArgumentException("Texture is fully transparent: " + argument);
            }
            System.out.printf(
                    "%s: [%d,%d,%d]%n",
                    argument,
                    Math.round(red / alphaTotal),
                    Math.round(green / alphaTotal),
                    Math.round(blue / alphaTotal));
        }
    }
}
