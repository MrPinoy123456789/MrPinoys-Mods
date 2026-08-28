package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * M11's one proof boss encounter: a single, heavily scaled vanilla mob in the
 * terminal cell of a boss-themed run, tagged so the completion gate can find
 * it. Not a custom entity (section 9 forbids one, and this mod has none); a
 * tagged {@code minecraft:ravager} reads as a boss without needing a client.
 *
 * <h2>Why this lives in the terminal cell rather than a new room type</h2>
 *
 * <p>The plan calls for a new {@code dungeon_room} entry and a hand-authored
 * {@code .nbt}. This session has no live client and no way to open a structure
 * block to author or capture one, so the boss is placed into the terminal
 * cell's existing {@code exit_hall} template instead: the one room every
 * procedural dungeon already stamps at a single fixed rotation
 * ({@link TrialContent}'s reward-chest placement leans on the same fact). This
 * is a scope divergence from the plan's implementation notes, recorded there
 * and in {@code plans/COMPLETED-MILESTONES.md}; a bespoke boss arena remains
 * future work for whoever next has a client attached. The completion
 * condition this milestone actually asks for, refusing the pad while the boss
 * lives, works exactly the same either way.
 */
final class BossContent {

    /** Read back by {@link #bossAlive} to find the mob this run's own encounter placed. */
    private static final String BOSS_TAG = PocketDungeonsMod.MOD_ID + ".boss";

    /**
     * Independent of {@link PocketDungeonsConfig#mobScalePerLevel()}: a boss is
     * meant to read as a step up from an ordinary scaled mob at the same level,
     * not a reskinned one. Tuning, same as the rest of the boss's shape (open in
     * the plan); {@code 3x} the ordinary bonus is the starting point.
     */
    private static final double BOSS_SCALE_FACTOR = 3.0;

    private BossContent() {}

    /**
     * Spawns the boss at the terminal cell's centre, tagged and scaled. Called
     * once, when a boss-themed run generates (see {@code Instances
     * .generateBehindLobby}), so the mob is standing there from the first
     * moment a player can reach the pad, never spawned reactively on contact.
     */
    static void spawn(ServerLevel level, BlockPos terminalOrigin, int keystoneLevel) {
        BlockPos centre = terminalOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        Mob boss = EntityTypes.RAVAGER.spawn(level, centre, EntitySpawnReason.TRIGGERED);
        if (boss == null) {
            PocketDungeonsMod.LOG.warn("Boss encounter at {} failed to spawn", centre.toShortString());
            return;
        }
        boss.setPersistenceRequired();
        boss.addTag(BOSS_TAG);
        boss.setCustomName(Component.literal("The Drowned Warden").withStyle(ChatFormatting.DARK_PURPLE));
        boss.setCustomNameVisible(true);
        double bonus = Math.max(0.0, (DifficultyProfile.mobScale(keystoneLevel,
                PocketDungeonsConfig.mobScalePerLevel(), PocketDungeonsConfig.mobScaleBase()) - 1.0)
                * BOSS_SCALE_FACTOR);
        Instances.applyMobScaleBonus(boss, bonus);
    }

    /**
     * Whether this run's tagged boss is still alive anywhere in the terminal
     * cell. {@code true} if the boss was never found (spawn failure, or a boss
     * already cleaned up by teardown) reads as <strong>not</strong> blocking
     * completion; a run must not be stuck forever because a spawn attempt
     * failed.
     */
    static boolean bossAlive(ServerLevel level, BlockPos terminalOrigin) {
        AABB bounds = CellGeometry.cellBounds(terminalOrigin);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.isAlive() && mob.entityTags().contains(BOSS_TAG)) {
                return true;
            }
        }
        return false;
    }
}
