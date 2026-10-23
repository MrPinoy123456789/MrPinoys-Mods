package pocketdungeons;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
/**
 * Everything a {@link GlyphHandler} gets on a click (spec 3.3): who clicked with
 * what in hand and whether sneaking, the run they are in when inside one, the
 * glyph itself and a copy of its saved data.
 *
 * @param record the run the clicker is in, or null outside a party run
 */
public record GlyphContext(ServerPlayer player, ServerLevel level, ItemStack held, boolean sneaking,
                           InstanceRecord record, Glyph glyph, CompoundTag data) {
}
