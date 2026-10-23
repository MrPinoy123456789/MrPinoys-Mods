package pocketdungeons;

/** A named glyph behaviour, run by the dispatcher on a right-click (spec 3.3). */
@FunctionalInterface
public interface GlyphHandler {
    GlyphResult use(GlyphContext ctx);
}
