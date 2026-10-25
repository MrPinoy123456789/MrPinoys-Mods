package charter.mc.store;

import charter.state.CharterState;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

/**
 * One block of {@code state.json}, written under its own key. Each feature owns the
 * block for its records (CharterState's blocks, PLAN.md §2.2), so a milestone adds
 * state by registering a block with {@link StateCodec#register}, not by editing
 * another block. A block missing from an older file reads as empty: {@link #read}
 * is simply not called, so the block's records stay as a new state has them.
 *
 * <p>Blocks are read in registration order, after the ones they refer to. A block
 * that finds a record pointing at something gone drops or fixes it and says so
 * through {@link Repairs}; it never throws for that.
 */
public interface StateBlock {

    /** The key in {@code state.json}. Never changes once released. */
    String key();

    JsonElement write(CharterState state, Gson gson);

    void read(JsonElement json, CharterState state, Gson gson, Repairs repairs);
}
