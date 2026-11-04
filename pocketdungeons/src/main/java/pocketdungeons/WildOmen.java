package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Ominous lodestones (decision 2026-11-04, the Trial Chambers' Bad Omen in a place players hold). A player right clicks
 * a Ready lodestone holding an Ominous Bottle: the bottle is spent and the next dungeon built there is ominous. That
 * run is {@link #EXTRA_LEVELS} levels higher, carries the Ominous affix (ominous trial spawners, the {@code _ominous}
 * loot tables, so more chest rolls and half again the Sovereigns), and cools down {@link #COOLDOWN_PERCENT} percent
 * as long after its clear. The clear spends the omen ({@link WildState.Lodestone#markCleared}).
 */
final class WildOmen {

    // Hardcoded for now: switch these to pocketdungeons.json knobs (tools/new_knob.py --regen) when available.
    /** Levels an ominous run adds on top of the lodestone's own; it may pass the offering's maxLevel. */
    static final int EXTRA_LEVELS = 2;
    /** The cooldown after an ominous clear, as a percent of the offering's own. */
    static final int COOLDOWN_PERCENT = 150;

    private WildOmen() {}

    /** The level an ominous or plain run of this lodestone builds at. Pure. */
    static int level(int lodestoneLevel, boolean ominous) {
        return Math.max(1, lodestoneLevel) + (ominous ? EXTRA_LEVELS : 0);
    }

    /** The cooldown after a clear, in seconds. Pure. */
    static long cooldownSeconds(long baseSeconds, boolean ominous) {
        return ominous ? baseSeconds * COOLDOWN_PERCENT / 100L : baseSeconds;
    }

    /**
     * Handles a right click on an entrance with an Ominous Bottle in the main hand. Returns {@code false} when the
     * player holds no bottle (the menu opens as usual), {@code true} when the click was the bottle's, taken or refused.
     */
    static boolean tryApply(ServerPlayer player, ServerLevel level, BlockPos pos, WildState.Lodestone lodestone) {
        ItemStack held = player.getMainHandItem();
        if (!held.is(Items.OMINOUS_BOTTLE)) {
            return false;
        }
        WildState.OmenRefusal refusal = lodestone.omenRefusal();
        if (refusal != null) {
            player.sendSystemMessage(Component.literal(refusal == WildState.OmenRefusal.ALREADY
                    ? WildText.OMEN_ALREADY : WildText.OMEN_NOT_READY).withStyle(ChatFormatting.GRAY), true);
            return true;
        }
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        lodestone.ominous = true;
        WildStore.save();
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.OMINOUS_BOTTLE_DISPOSE,
                SoundSource.BLOCKS, 1.0f, 0.8f);
        level.sendParticles(ParticleTypes.WITCH, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 30,
                0.4, 0.6, 0.4, 0.02);
        player.sendSystemMessage(Component.literal(WildText.OMEN_TAKEN).withStyle(ChatFormatting.DARK_PURPLE));
        PocketDungeonsMod.LOG.info("{} made wild lodestone {} ominous", player.getName().getString(), lodestone.id);
        return true;
    }
}
