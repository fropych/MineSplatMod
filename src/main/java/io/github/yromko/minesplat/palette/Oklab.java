package io.github.yromko.minesplat.palette;

public final class Oklab {
    private Oklab() {
    }

    public static double[] fromLinearRgb(double red, double green, double blue) {
        double l = 0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue;
        double m = 0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue;
        double s = 0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue;
        double lRoot = Math.cbrt(l);
        double mRoot = Math.cbrt(m);
        double sRoot = Math.cbrt(s);
        return new double[] {
                0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot,
                1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot,
                0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot
        };
    }

    public static double[] fromLinearBytes(int red, int green, int blue) {
        return fromLinearRgb(red / 255.0, green / 255.0, blue / 255.0);
    }

    public static double[] fromSrgbBytes(int red, int green, int blue) {
        return fromLinearRgb(toLinear(red / 255.0), toLinear(green / 255.0), toLinear(blue / 255.0));
    }

    public static double squaredDistance(double[] left, double[] right) {
        double dl = left[0] - right[0];
        double da = left[1] - right[1];
        double db = left[2] - right[2];
        return dl * dl + da * da + db * db;
    }

    private static double toLinear(double value) {
        return value <= 0.04045
                ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
