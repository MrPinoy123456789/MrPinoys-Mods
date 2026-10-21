package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * The shapes a telegraphed area attack can take (owner ask 2026-10-19): a circle, a cone or a line,
 * every one described as floor tiles relative to its origin. Pure geometry, no Minecraft imports, so
 * the marking and the "who is inside" tests run under plain {@code javac}; {@link AoETelegraph} does
 * them to the world.
 *
 * <p>Coordinates: {@code dx} and {@code dz} are block offsets from the origin tile's centre. A tile
 * offset of {@code {dx, dz}} names the tile at {@code origin + (dx, 0, dz)}: its centre sits exactly
 * {@code dx} blocks east and {@code dz} blocks south of the origin's centre, so {@link #tiles} may
 * use {@link Shape#contains} directly on integer offsets, while the resolve test feeds it the
 * entity's continuous position.
 */
final class AoEShapes {

    private AoEShapes() {}

    /** How wide the tile at the very apex of a cone or the tail of a line reads, in blocks. */
    private static final double APEX_RADIUS = 0.75;

    /** A marked area on the floor. */
    interface Shape {
        /** Whether the point {@code dx,dz} blocks east/south of the origin's centre is inside. */
        boolean contains(double dx, double dz);

        /** Every tile offset {@code {dx,dz}} the telegraph marks. */
        List<int[]> tiles();
    }

    /** A disc of {@code radius} blocks around the origin. */
    record Circle(double radius) implements Shape {
        @Override
        public boolean contains(double dx, double dz) {
            return dx * dx + dz * dz <= radius * radius;
        }

        @Override
        public List<int[]> tiles() {
            return fill(this, (int) Math.ceil(radius));
        }
    }

    /**
     * A wedge from the origin toward {@code dirX,dirZ}, {@code range} blocks long and
     * {@code halfAngleDeg} degrees either side of the direction. The origin tile always marks; a
     * zero direction points east rather than throwing on a caller's degenerate vector.
     */
    record Cone(double dirX, double dirZ, double range, double halfAngleDeg) implements Shape {
        @Override
        public boolean contains(double dx, double dz) {
            double dist = Math.hypot(dx, dz);
            if (dist > range) {
                return false;
            }
            if (dist <= APEX_RADIUS) {
                return true;
            }
            double t = dx * ux() + dz * uz();
            // A breath inside the edge: a point right on the boundary loses a float epsilon
            // to dist*cos, and a marked edge should read as marked, not as out.
            return t >= dist * cosHalf() - 1.0e-9;
        }

        @Override
        public List<int[]> tiles() {
            return fill(this, (int) Math.ceil(range));
        }

        private double ux() {
            double n = Math.hypot(dirX, dirZ);
            return n < 1.0e-6 ? 1.0 : dirX / n;
        }

        private double uz() {
            double n = Math.hypot(dirX, dirZ);
            return n < 1.0e-6 ? 0.0 : dirZ / n;
        }

        private double cosHalf() {
            return Math.cos(Math.toRadians(halfAngleDeg));
        }
    }

    /**
     * A bar {@code length} blocks long and {@code halfWidth} either side of the line from the
     * origin toward {@code dirX,dirZ} -- a rectangle, or a narrow rectangle a lane of fire.
     */
    record Line(double dirX, double dirZ, double length, double halfWidth) implements Shape {
        @Override
        public boolean contains(double dx, double dz) {
            double t = dx * ux() + dz * uz();
            double p = -dx * uz() + dz * ux();
            return t >= -APEX_RADIUS && t <= length && Math.abs(p) <= halfWidth;
        }

        @Override
        public List<int[]> tiles() {
            return fill(this, (int) Math.ceil(length + halfWidth + APEX_RADIUS));
        }

        private double ux() {
            double n = Math.hypot(dirX, dirZ);
            return n < 1.0e-6 ? 1.0 : dirX / n;
        }

        private double uz() {
            double n = Math.hypot(dirX, dirZ);
            return n < 1.0e-6 ? 0.0 : dirZ / n;
        }
    }

    /** Every tile offset of {@code shape} inside its {@code bound}-sized square, a stable order. */
    private static List<int[]> fill(Shape shape, int bound) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -bound; dx <= bound; dx++) {
            for (int dz = -bound; dz <= bound; dz++) {
                if (shape.contains(dx, dz)) {
                    out.add(new int[] {dx, dz});
                }
            }
        }
        return out;
    }
}
