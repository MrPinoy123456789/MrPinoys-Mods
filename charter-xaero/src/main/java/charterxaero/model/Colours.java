package charterxaero.model;

/**
 * Colour maths for the map overlay. Plain JDK, unit tested.
 *
 * <p>Xaero's highlighters take a colour as one int laid out {@code 0xBBGGRRAA} (blue in the top byte, alpha in the
 * bottom), read from how Xaero's own claim highlighter builds it; {@link #toXaero} makes that from {@code 0xRRGGBB}.
 */
public final class Colours {

    /** Alpha of a claimed chunk's fill: light enough to read the terrain through. */
    public static final int FILL_ALPHA = 0x48;
    /** Alpha of the keep's core chunk fill, a little stronger so the heart of a kingdom stands out. */
    public static final int CORE_ALPHA = 0x80;
    /** Alpha of the outer edge of a kingdom: strong, so borders read at a glance. */
    public static final int BORDER_ALPHA = 0xD8;
    /** Alpha of a marker's chunk when there is no Minimap to show it as a waypoint. */
    public static final int MARKER_ALPHA = 0xC0;

    private Colours() {
    }

    /** {@code 0xRRGGBB} and an alpha (0 to 255) as Xaero's {@code 0xBBGGRRAA}. */
    public static int toXaero(int rgb, int alpha) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (b << 24) | (g << 16) | (r << 8) | (alpha & 0xFF);
    }

    /** The {@code 0xRRGGBB} part of a Xaero colour. */
    public static int rgbOfXaero(int xaero) {
        int b = (xaero >>> 24) & 0xFF;
        int g = (xaero >>> 16) & 0xFF;
        int r = (xaero >>> 8) & 0xFF;
        return (r << 16) | (g << 8) | b;
    }

    /** The alpha of a Xaero colour. */
    public static int alphaOfXaero(int xaero) {
        return xaero & 0xFF;
    }

    /**
     * The edge colour of a kingdom: its own colour pulled a quarter of the way towards black, so a pale kingdom (the
     * spawn's light grey, Azurius' baby blue) still has a border that shows on snow and sand.
     */
    public static int border(int rgb) {
        return mix(rgb, 0x000000, 0.25);
    }

    /** {@code a} moved {@code t} (0 to 1) of the way to {@code b}, channel by channel. */
    public static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * The index of the palette colour nearest to {@code rgb}, by a red mean weighted distance (closer to what an eye
     * sees than plain RGB distance). Used to pick a Xaero waypoint colour, which is one of a fixed list.
     */
    public static int nearest(int rgb, int[] palette) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            double d = distance(rgb, palette[i]);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    static double distance(int a, int b) {
        int r1 = (a >> 16) & 0xFF, g1 = (a >> 8) & 0xFF, b1 = a & 0xFF;
        int r2 = (b >> 16) & 0xFF, g2 = (b >> 8) & 0xFF, b2 = b & 0xFF;
        double rMean = (r1 + r2) / 2.0;
        int dr = r1 - r2, dg = g1 - g2, db = b1 - b2;
        return (2 + rMean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rMean) / 256) * db * db;
    }

    /**
     * The five colours Xaero asks a chunk highlighter for: the fill, then the north, east, south and west edges.
     * An edge where the neighbour is not the same kingdom takes the border colour; inside a kingdom it is the fill,
     * so neighbouring chunks of one kingdom melt into one area.
     *
     * @param sides bits {@link Borders#NORTH}, {@link Borders#EAST}, {@link Borders#SOUTH}, {@link Borders#WEST}
     */
    public static int[] chunkColours(int rgb, boolean core, int sides, int[] into) {
        int fill = toXaero(rgb, core ? CORE_ALPHA : FILL_ALPHA);
        int edge = toXaero(border(rgb), BORDER_ALPHA);
        into[0] = fill;
        into[1] = (sides & Borders.NORTH) != 0 ? edge : fill;
        into[2] = (sides & Borders.EAST) != 0 ? edge : fill;
        into[3] = (sides & Borders.SOUTH) != 0 ? edge : fill;
        into[4] = (sides & Borders.WEST) != 0 ? edge : fill;
        return into;
    }

    /** The five colours of a marker's chunk: one solid colour with a darker rim. */
    public static int[] markerColours(int rgb, int[] into) {
        int fill = toXaero(rgb, MARKER_ALPHA);
        int edge = toXaero(border(rgb), BORDER_ALPHA);
        into[0] = fill;
        into[1] = edge;
        into[2] = edge;
        into[3] = edge;
        into[4] = edge;
        return into;
    }
}
