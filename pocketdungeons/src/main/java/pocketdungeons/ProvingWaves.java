package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.Set;
import java.util.UUID;

/**
 * The Proving's wave engine (decision 2026-10-16): a descent dungeon of kind
 * {@link DungeonDef.Kind#PROVING} whose every floor is one arena fight. Each floor's
 * terminal cell holds a wave, spawned when the floor commits; the pad stays shut while any
 * wave mob lives, and clearing the floor pays a loot tier that climbs with depth. Descend or
 * bank is the same checkpoint the Endless Mine already offers: a door deeper or the HOME
 * lever.
 *
 * <p>The class is the engine, not the descent: {@link EndlessMineRules} still carries the
 * flag, the theme, the depth and the floor levels; a Proving is simply a descent whose
 * dungeon kind is {@code proving}. Its waves borrow the guardian machinery's scale dial
 * ({@link MineGuardians#scale}) and spot picking ({@link MineGuardians#freeSpotInCell}) but
 * tag with {@link #PROVING_TAG}, so a proving floor never answers to the guardian's gate and
 * never counts toward {@link MineGuardians#alive}.
 */
final class ProvingWaves {

    /** Read back by {@link #alive} to find the mobs this floor's wave spawned. */
    static final String PROVING_TAG = PocketDungeonsMod.MOD_ID + ".proving_wave";

    /** The mob pool a wave draws on in act 1, cycled by floor and index so the mix changes as it deepens. */
    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Mob>[] ACT_ONE = new EntityType[]{
            EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER};

    /** The pool for act 2 and every later act: ranged pressure, a bruiser and a witch. */
    @SuppressWarnings("unchecked")
    private static final EntityType<? extends Mob>[] ACT_TWO = new EntityType[]{
            EntityTypes.PILLAGER, EntityTypes.VINDICATOR, EntityTypes.SKELETON, EntityTypes.WITCH};

    private ProvingWaves() {}

    // ---- which dungeon ---------------------------------------------------------------

    /** Whether the record's descent is a Proving run: flagged like a Mine, in a proving-kind dungeon. */
    static boolean isProving(InstanceRecord record) {
        DungeonDef def = EndlessMineRules.mineOf(record);
        return def != null && def.kind() == DungeonDef.Kind.PROVING && EndlessMineRules.isMine(record);
    }

    /** Whether {@code offer} opens a Proving from the first staging room. */
    static boolean isProvingOffer(Keystone.Offer offer) {
        if (offer == null) {
            return false;
        }
        DungeonDef def = DungeonDefs.current().byId(offer.dungeonId());
        return def != null && def.kind() == DungeonDef.Kind.PROVING;
    }

    /** Whether the descent {@code offer} would open (or {@code record} is in) is a Proving. */
    static boolean isProvingFor(InstanceRecord record, Keystone.Offer offer) {
        DungeonDef def = DungeonDefs.current().byId(EndlessMineRules.mineIdFor(record, offer));
        return def != null && def.kind() == DungeonDef.Kind.PROVING;
    }

    // ---- the wave --------------------------------------------------------------------

    /** How many mobs floor {@code floor} (from 1) sends at a party of {@code members}: the knobs, clamped. */
    static int waveCount(int floor, int members) {
        int count = PocketDungeonsConfig.provingWaveBaseSize()
                + PocketDungeonsConfig.provingWaveSizePerFloor() * Math.max(0, floor - 1)
                + PocketDungeonsConfig.provingWaveSizePerMember() * Math.max(0, members - 1);
        return Math.clamp(count, 3, PocketDungeonsConfig.provingWaveMaxSize());
    }

    /** Whether floor {@code floor} (from 1) sends a champion with its wave ({@code provingChampionEvery}). */
    static boolean isChampionFloor(int floor) {
        int every = Math.max(1, PocketDungeonsConfig.provingChampionEvery());
        return floor % every == 0;
    }

    /** The mob pool for {@code act}: act 1 fights the overworld's rabble, later acts the raiders'. */
    private static EntityType<? extends Mob>[] pool(int act) {
        return act <= 1 ? ACT_ONE : ACT_TWO;
    }

    /**
     * Spawns floor {@code floor}'s wave around the terminal (arena) cell's centre, every mob
     * tagged so the pad stays shut while it lives. The last slot is the champion on a
     * champion floor: named, scaled half again above the wave's own scale. Called once when
     * the floor commits, the way a guardian waits on its floor.
     */
    static void spawn(ServerLevel level, InstanceRecord record, BlockPos terminalOrigin, int floor,
                      int floorLevel, int members) {
        DungeonDef def = EndlessMineRules.mineOf(record);
        int act = def == null ? 1 : def.act();
        double hardness = EndlessMineRules.hardness(floor);
        int count = waveCount(floor, members);
        EntityType<? extends Mob>[] pool = pool(act);
        boolean champion = isChampionFloor(floor);
        Set<BlockPos> taken = new java.util.HashSet<>();
        taken.add(terminalOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2));
        for (int i = 0; i < count; i++) {
            BlockPos at = MineGuardians.freeSpotInCell(level, terminalOrigin, taken, level.getRandom());
            if (at == null) {
                break;
            }
            Mob mob = pool[(i + floor) % pool.length].spawn(level, at, EntitySpawnReason.TRIGGERED);
            if (mob == null) {
                PocketDungeonsMod.LOG.warn("Proving wave mob failed to spawn at {}", at.toShortString());
                continue;
            }
            tag(mob, terminalOrigin);
            if (champion && i == count - 1) {
                mob.setCustomName(Component.literal("Champion").withStyle(ChatFormatting.GOLD));
                mob.setCustomNameVisible(true);
                MineGuardians.scale(mob, floorLevel, hardness * 1.5);
            } else {
                MineGuardians.scale(mob, floorLevel, hardness);
            }
        }
    }

    /**
     * Marks {@code mob} as part of the floor's wave at {@code terminal}, so the pad stays
     * shut while it lives. Same scope rule the guardians use: a wave chases players, so
     * {@link #alive} scans wide and two nearby floors' waves must not hold each other's pad.
     */
    static void tag(Mob mob, BlockPos terminal) {
        mob.setPersistenceRequired();
        mob.addTag(PROVING_TAG);
        mob.addTag(scopeTag(terminal));
    }

    /** The tag that ties a wave mob to the terminal cell it spawned for. */
    private static String scopeTag(BlockPos terminal) {
        return PROVING_TAG + "@" + terminal.getX() + "," + terminal.getY() + "," + terminal.getZ();
    }

    /** Whether {@code mob} belongs to the wave at {@code terminal}: it carries that terminal's scope tag. */
    private static boolean scopedTo(Mob mob, BlockPos terminal) {
        return mob.entityTags().contains(scopeTag(terminal));
    }

    /** Whether any wave mob of this floor still lives around the terminal cell. */
    static boolean alive(ServerLevel level, BlockPos terminalOrigin) {
        // A wave chases players, so look well beyond the cell the wave spawned in.
        AABB bounds = CellGeometry.cellBounds(terminalOrigin).inflate(RoomGeometry.CELL * 2.0);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.isAlive() && mob.entityTags().contains(PROVING_TAG)
                    && scopedTo(mob, terminalOrigin)) {
                return true;
            }
        }
        return false;
    }

    /** The pad refusal line while the floor's wave stands, or null (not a Proving, or the wave is down). */
    static String padRefusal(ServerLevel level, InstanceRecord record) {
        if (!isProving(record) || record.layout == null) {
            return null;
        }
        return alive(level, record.layout.terminal()) ? "The wave still stands. Finish it first." : null;
    }

    // ---- the hidden pad room (PD-215) -----------------------------------------

    /**
     * Stamps the floor's exit pad as its own room, one cell beyond the arena's far wall,
     * sealed behind the wall until the wave is down (PD-215, playtest 2026-10-16: the
     * arena itself carries no lodestone, so a spent wave left the run unfinishable).
     * The room is the generated {@code exit_hall} template turned so its door faces the
     * arena, themed by the run theme's processors like every other stamped cell; the
     * staging room lands on this same cell when the floor completes, so the reveal
     * reads as the checkpoint opening.
     */
    static void stampPadRoom(ServerLevel level, InstanceRecord record, InstanceLayout layout,
                             String themeId) {
        BlockPos terminalOrigin = layout.terminal();
        DoorMask.Direction farWall = CellGeometry.opposite(
                CellGeometry.terminalEntranceDirection(layout.geometry(), terminalOrigin));
        BlockPos padOrigin = CellGeometry.offsetInDirection(terminalOrigin, farWall, RoomGeometry.CELL);
        InstanceTeardown.holdCell(level, padOrigin);
        ThemeManifest.Entry theme = ThemeManifest.current().byId(themeId);
        String processors = theme == null ? null : theme.meta().processors;
        TemplateStamper.place(level, level.getStructureManager(), padOrigin, TemplateStamper.EXIT_HALL,
                CellGeometry.rotationToFace(DoorMask.Direction.WEST, CellGeometry.opposite(farWall)),
                layout.seed() ^ padOrigin.asLong(),
                processors == null ? null : Identifier.parse(processors));
        // The envelope skips every side a live cell stands against: the ring would
        // land in the arena's wall column and seal the doorway the reveal opens.
        BedrockEnvelope.applyToCell(level, padOrigin, InstanceTeardown.standingSides(record, padOrigin));
        record.floor.provingPadOrigin = padOrigin;
    }

    /**
     * The reveal: while a proving floor's pad room is still sealed and its wave is down,
     * open the arena's far wall onto it. Runs on the watch tick like the spawner-clear
     * cue, since the wave dying has no event to hook. A wave that failed to spawn opens
     * the room straight away rather than holding the floor hostage.
     */
    static void tick(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level != null) {
            tick(level, server, record);
        }
    }

    /** The level-taking half of {@link #tick}, the seam the gametest drives. */
    static void tick(ServerLevel level, MinecraftServer server, InstanceRecord record) {
        if (record.floor.provingPadOrigin == null || record.floor.provingPadRevealed
                || record.layout == null || record.layout.geometry() == null) {
            return;
        }
        if (alive(level, record.layout.terminal())) {
            return;
        }
        CellGeometry.openDoorOnWall(level, record.layout.terminal(), CellGeometry.opposite(
                CellGeometry.terminalEntranceDirection(record.layout.geometry(),
                        record.layout.terminal())));
        record.floor.provingPadRevealed = true;
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                player.sendSystemMessage(Component.literal(
                        "The wave is beaten. The way on opens.").withStyle(ChatFormatting.AQUA));
                Chime.spawnerCleared(player);
            }
        }
    }

    // ---- pay ---------------------------------------------------------------------------

    /** The loot tier index a Proving floor pays: every floor is a fight, so the tier climbs with depth. */
    static int tierIndex(int floor) {
        return Math.min(EndlessMineRules.MAX_TIER, Math.max(1, floor));
    }

    /**
     * The loot table a cleared Proving floor pays: the act's own tier table for the floor's
     * tier index (the same A, B, C tables a guardian pays; the wave is the fight, the table
     * is the pay), else {@code fallback} when the run is not a Proving.
     */
    static String payingTable(InstanceRecord record, int floorsCleared, String fallback) {
        if (!isProving(record)) {
            return fallback;
        }
        return EndlessMineRules.lootTableId(EndlessMineRules.actOf(record), tierIndex(floorsCleared));
    }

    /** The completion chest tier of a Proving floor: the band's minimum plus the tier the wave paid. */
    static int floorTier(DungeonDef.LootBand band, int floor) {
        DungeonDef.LootBand use = band == null ? new DungeonDef.LootBand(1, 2) : band;
        return use.clamp(use.min() + tierIndex(floor) - 1);
    }

    // ---- commitment surface text ---------------------------------------------------------

    /** Published when a Proving run first commits, the same rule the Mine's start message keeps. */
    static String startMessage() {
        return "The Proving. There is no final floor. Every floor is one fight: a wave in the ring, "
                + "and a champion every " + Math.max(1, PocketDungeonsConfig.provingChampionEvery())
                + " floors. Each checkpoint offers a door deeper, or the HOME lever to leave with "
                + "what you carry. Previous floors close behind you.";
    }

    /** Published at each Proving checkpoint, naming the depth and what waits next. */
    static String checkpointMessage(int floorIndex) {
        String next = isChampionFloor(floorIndex + 1)
                ? "A champion waits on the next floor." : "The next wave is bigger.";
        return "Proving floor " + floorIndex + " cleared. " + next
                + " Walk a door to descend, or pull the HOME lever to leave with your haul.";
    }

    /** Said when a Proving interval banks, naming the depth reached. */
    static String cashOutMessage(int depth) {
        return "You leave the Proving with " + depth + " floor" + (depth == 1 ? "" : "s") + " banked.";
    }
}
