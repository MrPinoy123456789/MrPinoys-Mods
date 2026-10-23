package pocketdungeons;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.function.Consumer;

/**
 * What a {@link GlyphHandler} asks the dispatcher to do (spec 3.3). A small
 * builder; the dispatcher applies the parts in this order: {@link #consume},
 * {@link #refuse}, {@link #overlay}, {@link #sound}, {@link #restyle},
 * {@link #vanish}, {@link #then}. {@link #PASS} does nothing.
 */
public final class GlyphResult {

    /** The no-op result. */
    public static final GlyphResult PASS = new GlyphResult();

    int consume;
    boolean refuse;
    Component overlay;
    SoundEvent sound;
    Consumer<Glyph> restyle;
    boolean vanish;
    Identifier thenId;
    CompoundTag thenData;

    private GlyphResult() {}

    public static GlyphResult create() {
        return new GlyphResult();
    }

    /** Consume {@code n} of the held stack, applied first. */
    public GlyphResult consume(int n) {
        this.consume = n;
        return this;
    }

    /** The refuse chime and, for the standard look, the icon shake. */
    public GlyphResult refuse() {
        this.refuse = true;
        return this;
    }

    /** An action bar line to the clicker. */
    public GlyphResult overlay(Component message) {
        this.overlay = message;
        return this;
    }

    /** A sound at the glyph. */
    public GlyphResult sound(SoundEvent event) {
        this.sound = event;
        return this;
    }

    /** Redraw the glyph's visuals: the callback runs on the glyph itself. */
    public GlyphResult restyle(Consumer<Glyph> op) {
        this.restyle = op;
        return this;
    }

    /** Remove the whole glyph, anchor and visuals. */
    public GlyphResult vanish() {
        this.vanish = true;
        return this;
    }

    /** Run another handler after everything else, with its own saved data. */
    public GlyphResult then(Identifier id, CompoundTag data) {
        this.thenId = id;
        this.thenData = data == null ? new CompoundTag() : data;
        return this;
    }
}
