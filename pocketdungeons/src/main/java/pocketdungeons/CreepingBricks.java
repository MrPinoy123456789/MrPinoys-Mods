package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The Infestation's creeping bricks (roadmap T24, decision 3b). A mossy stone brick appears, matures to a stone
 * brick, then to an infested stone brick, then bursts: the block is removed with no drop (a plain
 * {@code setBlock}, never a break that rolls loot) and one silverfish comes out. Each change has a sound and
 * crack particles, so the clock reads; a brick the party breaks before it bursts releases nothing.
 *
 * <p>The mechanic lives in two places only: the Chewed Corridors and Silverfish Nests rooms
 * ({@link CreepingRules#SEEDED_ROOMS}), seeded at stamp time and started by a member walking in, and the
 * Infestation finale ({@link InfestationFinale}). Both use a {@link Field}: the bricks of one place, with the
 * per room cap, the per floor cap ({@code creepingFloorCap}) and the stage clock ({@code creepingMossySeconds}
 * and its two siblings). Stages are a function of the planting tick ({@link CreepingRules#stageAt}); a poll
 * every {@link #POLL_TICKS} ticks applies them. A brick whose block is no longer the one its stage expects
 * (smashed, replaced) is dropped silently. A field that ends gives every brick back as it was.
 *
 * <p>Breaking a brick is vanilla mining under the dungeon tool rule ({@link RoomProtection#verdict}); this
 * class only takes over the result, so nothing drops and an infested brick does not wake a silverfish.
 */
final class CreepingBricks {

    /** How often the shared poll runs. Stage times are whole seconds, so 5 ticks reads cleanly. */
    static final int POLL_TICKS = 5;
    /** Ticks between the shakes an infested brick gives while it waits to burst. */
    static final int SHAKE_TICKS = 20;

    /** One brick. {@code stage} is the last stage applied to the world. */
    static final class Brick {
        final BlockPos pos;
        final BlockState original;
        final long plantedAt;
        int stage = CreepingRules.MOSSY;
        long lastShake;

        Brick(BlockPos pos, BlockState original, long plantedAt) {
            this.pos = pos;
            this.original = original;
            this.plantedAt = plantedAt;
        }
    }

    /** The bricks of one room or one boss arena. */
    static final class Field {
        final ServerLevel level;
        /** Names the floor for the per floor cap (the instance origin). */
        final BlockPos floorKey;
        final int roomCap;
        final Map<BlockPos, Brick> bricks = new HashMap<>();
        /** Silverfish released and not yet taken by {@link #collect}; only kept when {@code collecting}. */
        final List<Mob> released = new ArrayList<>();
        final boolean collecting;
        /** How many more bricks may burst; a ripe brick waits while it is 0. The finale sets it to its silverfish cap. */
        int releaseBudget = Integer.MAX_VALUE;
        boolean open = true;

        Field(ServerLevel level, BlockPos floorKey, int roomCap, boolean collecting) {
            this.level = level;
            this.floorKey = floorKey.immutable();
            this.roomCap = roomCap;
            this.collecting = collecting;
        }

        int active() {
            return bricks.size();
        }

        /** Plants a mossy brick at {@code pos} if it is air and the caps allow. Returns whether one was planted. */
        boolean plant(BlockPos pos, long now) {
            if (!open || !level.isLoaded(pos) || !level.getBlockState(pos).isAir() || bricks.containsKey(pos)
                    || CreepingRules.slots(active(), roomCap, floorActive(floorKey),
                            PocketDungeonsConfig.creepingFloorCap()) <= 0) {
                return false;
            }
            BlockPos at = pos.immutable();
            Brick brick = new Brick(at, level.getBlockState(at), now);
            brick.lastShake = now;
            level.setBlock(at, Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
            bricks.put(at, brick);
            crack(level, at, Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 6);
            level.playSound(null, at, SoundEvents.MOSS_PLACE, SoundSource.BLOCKS, 0.9f, 0.8f);
            return true;
        }

        /** Applies the stage clock to every brick; call every {@link #POLL_TICKS} ticks or so. */
        void tick(long now) {
            long mossy = PocketDungeonsConfig.creepingMossySeconds() * 20L;
            long stone = PocketDungeonsConfig.creepingStoneSeconds() * 20L;
            long infested = PocketDungeonsConfig.creepingInfestedSeconds() * 20L;
            Iterator<Brick> it = bricks.values().iterator();
            while (it.hasNext()) {
                Brick brick = it.next();
                if (!level.isLoaded(brick.pos)) {
                    continue;
                }
                if (level.getBlockState(brick.pos).getBlock() != blockFor(brick.stage).getBlock()) {
                    it.remove(); // smashed or replaced before it burst: nothing comes out
                    continue;
                }
                int target = CreepingRules.stageAt(now - brick.plantedAt, mossy, stone, infested);
                if (target == CreepingRules.BURST) {
                    if (releaseBudget <= 0) {
                        target = CreepingRules.INFESTED; // the cap is full: wait, shaking
                    } else {
                        releaseBudget--;
                        burst(brick);
                        it.remove();
                        continue;
                    }
                }
                if (target > brick.stage) {
                    brick.stage = target;
                    BlockState state = blockFor(target);
                    level.setBlock(brick.pos, state, Block.UPDATE_ALL);
                    crack(level, brick.pos, state, 10);
                    if (target == CreepingRules.STONE) {
                        level.playSound(null, brick.pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0f, 0.7f);
                    } else {
                        level.playSound(null, brick.pos, SoundEvents.SILVERFISH_AMBIENT, SoundSource.HOSTILE,
                                1.0f, 0.7f);
                    }
                    brick.lastShake = now;
                } else if (brick.stage == CreepingRules.INFESTED && now - brick.lastShake >= SHAKE_TICKS) {
                    brick.lastShake = now;
                    crack(level, brick.pos, blockFor(brick.stage), 3);
                    level.playSound(null, brick.pos, SoundEvents.SILVERFISH_HURT, SoundSource.HOSTILE, 0.5f, 1.4f);
                }
            }
            if (!collecting) {
                released.clear();
            }
        }

        private void burst(Brick brick) {
            level.setBlock(brick.pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            crack(level, brick.pos, Blocks.INFESTED_STONE_BRICKS.defaultBlockState(), 16);
            level.playSound(null, brick.pos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0f, 0.8f);
            Entity entity = EntityTypes.SILVERFISH.spawn(level, brick.pos, EntitySpawnReason.TRIGGERED);
            if (entity instanceof Mob mob) {
                mob.setPersistenceRequired();
                if (collecting) {
                    released.add(mob);
                }
            }
        }

        /** Takes the silverfish released since the last call. */
        List<Mob> collect() {
            List<Mob> out = new ArrayList<>(released);
            released.clear();
            return out;
        }

        /** A member broke the brick at {@code pos}: it goes with no drop and no silverfish. Returns whether it was one. */
        boolean smash(BlockPos pos) {
            Brick brick = bricks.remove(pos);
            if (brick == null) {
                return false;
            }
            BlockState state = level.getBlockState(pos);
            level.setBlock(pos, brick.original, Block.UPDATE_ALL);
            crack(level, pos, state, 14);
            level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
            return true;
        }

        /** Ends the field: every standing brick goes back to the block it replaced; the field leaves the registry. */
        void close() {
            if (!open) {
                return;
            }
            open = false;
            for (Brick brick : bricks.values()) {
                if (level.isLoaded(brick.pos)
                        && level.getBlockState(brick.pos).getBlock() == blockFor(brick.stage).getBlock()) {
                    level.setBlock(brick.pos, brick.original, Block.UPDATE_ALL);
                }
            }
            bricks.clear();
            released.clear();
            FIELDS.remove(this);
        }
    }

    /** A room's seeds: where its bricks start, and whether a member has come in yet. */
    private static final class Room {
        final Field field;
        final BlockPos cell;
        final List<BlockPos> seeds;
        final long seed;
        final boolean[] planted;
        boolean triggered;
        long triggeredAt;

        Room(Field field, BlockPos cell, List<BlockPos> seeds, long seed) {
            this.field = field;
            this.cell = cell;
            this.seeds = seeds;
            this.seed = seed;
            this.planted = new boolean[seeds.size()];
        }
    }

    private static final List<Field> FIELDS = SessionState.clearedAtStop(new ArrayList<>());
    private static final Map<BlockPos, Room> ROOMS = SessionState.clearedAtStop(new HashMap<>());
    private static boolean registered;

    private CreepingBricks() {}

    /** Wires the poll and the break handler. Call once from {@code onInitialize}, after {@code RoomProtection.register()}. */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % POLL_TICKS != 0 || (FIELDS.isEmpty() && ROOMS.isEmpty())) {
                return;
            }
            pollAll(server.getTickCount());
        });
        PlayerBlockBreakEvents.BEFORE.register(CreepingBricks::beforeBreak);
    }

    // ---- fields -----------------------------------------------------------------------------------------

    /** Opens a field for one place; {@code collecting} keeps released silverfish for {@link Field#collect}. */
    static Field open(ServerLevel level, BlockPos floorKey, int roomCap, boolean collecting) {
        Field field = new Field(level, floorKey, roomCap, collecting);
        FIELDS.add(field);
        return field;
    }

    /** The bricks growing on one floor, every field together. */
    static int floorActive(BlockPos floorKey) {
        int total = 0;
        for (Field field : FIELDS) {
            if (field.floorKey.equals(floorKey)) {
                total += field.active();
            }
        }
        return total;
    }

    /** Whether a creeping brick stands at {@code pos}. */
    static boolean isBrick(Level level, BlockPos pos) {
        return fieldAt(level, pos) != null;
    }

    private static Field fieldAt(Level level, BlockPos pos) {
        for (Field field : FIELDS) {
            if (field.level == level && field.bricks.containsKey(pos)) {
                return field;
            }
        }
        return null;
    }

    /**
     * The break handler: once the dungeon's own tool rule would allow the break, the brick goes with no drop and
     * no silverfish, and the vanilla break is cancelled (which is what stops the loot and the infested release).
     */
    private static boolean beforeBreak(Level level, Player player, BlockPos pos, BlockState state,
                                       net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        Field field = fieldAt(level, pos);
        if (field == null) {
            return true;
        }
        if (!player.isCreative() && !RoomProtection.verdict(player, pos, state).allowed()) {
            return true; // the tool rule says no; its handler refuses
        }
        field.smash(pos);
        return false;
    }

    // ---- rooms ------------------------------------------------------------------------------------------

    /**
     * Marks the seed spots of a stamped room, if it is one that grows bricks. Called by the stamper right
     * after the room's content; deterministic in the plan seed. Does nothing for any other room or theme.
     */
    static void armRoom(ServerLevel level, BlockPos floorKey, BlockPos cellOrigin, String roomId, String theme,
                        long planSeed) {
        if (!CreepingRules.seeds(roomId, theme) || PocketDungeonsConfig.creepingRoomSeeds() <= 0) {
            return;
        }
        clear(cellOrigin);
        List<Integer> lx = new ArrayList<>();
        List<Integer> lz = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                if (CreepingRules.inDoorLane(x, z)) {
                    continue;
                }
                cursor.set(cellOrigin.getX() + x, cellOrigin.getY() + 1, cellOrigin.getZ() + z);
                if (level.getBlockState(cursor).isAir() && level.getBlockState(cursor.above()).isAir()
                        && level.getBlockState(cursor.below()).isSolid()) {
                    lx.add(x);
                    lz.add(z);
                }
            }
        }
        long seed = planSeed ^ cellOrigin.asLong();
        int[] xs = lx.stream().mapToInt(Integer::intValue).toArray();
        int[] zs = lz.stream().mapToInt(Integer::intValue).toArray();
        int[] picks = CreepingRules.pickSpread(seed, xs, zs, PocketDungeonsConfig.creepingRoomSeeds(),
                CreepingRules.SEED_SPACING);
        if (picks.length == 0) {
            return;
        }
        List<BlockPos> seeds = new ArrayList<>();
        for (int pick : picks) {
            seeds.add(new BlockPos(cellOrigin.getX() + xs[pick], cellOrigin.getY() + 1, cellOrigin.getZ() + zs[pick]));
        }
        Field field = open(level, floorKey, PocketDungeonsConfig.creepingRoomCap(), false);
        ROOMS.put(cellOrigin.immutable(), new Room(field, cellOrigin.immutable(), seeds, seed));
    }

    /** The seed spots armed for the cell, for tests; empty if none. */
    static List<BlockPos> seedsOf(BlockPos cellOrigin) {
        Room room = ROOMS.get(cellOrigin);
        return room == null ? List.of() : List.copyOf(room.seeds);
    }

    /** Forgets the cell's seeds and gives its standing bricks back. Called from the cell state teardown. */
    static void clear(BlockPos cellOrigin) {
        Room room = ROOMS.remove(cellOrigin);
        if (room != null) {
            room.field.close();
        }
    }

    // ---- the poll ---------------------------------------------------------------------------------------

    private static void pollAll(long now) {
        for (Room room : new ArrayList<>(ROOMS.values())) {
            tickRoom(room, now);
        }
        for (Field field : new ArrayList<>(FIELDS)) {
            field.tick(now);
        }
    }

    /** One room's seeds: trigger on a member inside, then start each seed after its delay. */
    static void tickRoom(Room room, long now) {
        ServerLevel level = room.field.level;
        if (!room.triggered) {
            if (!level.isLoaded(room.cell) || !memberInside(level, room.cell)) {
                return;
            }
            room.triggered = true;
            room.triggeredAt = now;
        }
        for (int i = 0; i < room.seeds.size(); i++) {
            if (!room.planted[i] && now - room.triggeredAt >= CreepingRules.startDelay(room.seed, i)) {
                room.planted[i] = room.field.plant(room.seeds.get(i), now);
            }
        }
    }

    private static boolean memberInside(ServerLevel level, BlockPos cell) {
        int low = cell.getY() - RoomGeometry.STORY_HEIGHT * (RoomGeometry.MAX_SPAN_Y - 1) - 1;
        int high = cell.getY() + RoomGeometry.CEILING_Y + 1;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            double x = player.getX() - cell.getX();
            double z = player.getZ() - cell.getZ();
            if (x >= 0 && x < RoomGeometry.CELL && z >= 0 && z < RoomGeometry.CELL
                    && player.getY() >= low && player.getY() <= high) {
                return true;
            }
        }
        return false;
    }

    /** Test seam: runs the poll once at {@code now}. */
    static void pollForTest(long now) {
        pollAll(now);
    }

    // ---- looks ------------------------------------------------------------------------------------------

    static BlockState blockFor(int stage) {
        return switch (stage) {
            case CreepingRules.MOSSY -> Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
            case CreepingRules.STONE -> Blocks.STONE_BRICKS.defaultBlockState();
            default -> Blocks.INFESTED_STONE_BRICKS.defaultBlockState();
        };
    }

    /** Crack particles: bits of {@code state} flying off the block. */
    static void crack(ServerLevel level, BlockPos pos, BlockState state, int count) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 0.5,
                pos.getZ() + 0.5, count, 0.3, 0.3, 0.3, 0.05);
    }
}
