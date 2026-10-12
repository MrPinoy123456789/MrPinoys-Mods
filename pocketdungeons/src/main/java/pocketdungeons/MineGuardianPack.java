package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;

/**
 * The Act 1 Endless Mine's second guardian (roadmap T41), a miniature of the Kennels pack. A pillager
 * packmaster waits in the terminal cell with a ring of wolves at his heels: every wolf is marked
 * hostile ({@link HostileWolves#mark}), so it goes for the party, and the packmaster is set as the
 * cell's guard ({@link HostileWolves#guard}), so the pack goes quiet when he falls. Hardness scales
 * the pack's size ({@link MineGuardianRules#wolvesFor}) and every mob's stats ({@link MineGuardians#scale});
 * the wolf count never takes the wave past {@code mineGuardianMobCap}.
 *
 * <p>No ticking state: the anger and the guard sweep are {@link HostileWolves}' own, and the guard
 * entry is dropped by {@code HostileWolves.clear} when the cell ends.
 */
final class MineGuardianPack implements MineGuardians.Guardian {

    /** The handler's name: the Handler's smaller cousin. */
    static final String NAME = "The Packmaster";

    /** Wolves stand this far from the packmaster. */
    private static final double RING = 3.0;

    @Override
    public void spawn(MineGuardians.Spawn spawn) {
        ServerLevel level = spawn.level();
        Mob handler = EntityTypes.PILLAGER.spawn(level, spawn.centre(), EntitySpawnReason.TRIGGERED);
        if (handler == null) {
            PocketDungeonsMod.LOG.warn("The mine packmaster failed to spawn at {}", spawn.centre().toShortString());
            return;
        }
        MineGuardians.tag(handler);
        MineGuardians.scale(handler, spawn.floorLevel(), spawn.hardness());
        handler.setCustomName(Component.literal(NAME).withStyle(ChatFormatting.RED));
        handler.setCustomNameVisible(true);

        int wolves = MineGuardianRules.capped(MineGuardianRules.wolvesFor(spawn.hardness()),
                Math.max(0, PocketDungeonsConfig.mineGuardianMobCap() - 1));
        for (int i = 0; i < wolves; i++) {
            double angle = 2.0 * Math.PI * i / wolves;
            BlockPos at = spawn.centre().offset((int) Math.round(Math.cos(angle) * RING), 0,
                    (int) Math.round(Math.sin(angle) * RING));
            Wolf pack = EntityTypes.WOLF.spawn(level, at, EntitySpawnReason.TRIGGERED);
            if (pack == null) {
                continue;
            }
            MineGuardians.tag(pack);
            MineGuardians.scale(pack, spawn.floorLevel(), spawn.hardness());
            HostileWolves.mark(pack);
        }
        HostileWolves.guard(spawn.terminal(), handler);
        level.playSound(null, spawn.centre(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 4.0f, 1.0f);
    }
}
