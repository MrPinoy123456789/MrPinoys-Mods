package charter.gametest;

/**
 * Added to every class a protection Mixin will target (PLAN.md §5.6), by the
 * M0 spike Mixins in this package. If a class implements it at runtime, its
 * spike Mixin applied, and with {@code defaultRequire: 1} the no-op injection
 * into the named method applied too. These Mixins live in the gametest source
 * set only and never ship.
 */
public interface SpikeMarker {
}
