package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Mineshaft finale (roadmap T20, boss workshop 2026-10-11 row 1): the dark phase, on the phased spine
 * (roadmap T03, 2026-10-15). The data wave (zombies and cave spiders) is untouched except that its first zombie
 * is the boss, The Pit Boss, run through a {@link BossFight}. At the boss's two thirds bound the shaft creaks;
 * a few seconds later the lights on the final floor go out. At one third the rest of the shaft wakes: a
 * second batch of cave spiders joins the fight. The spiders glow, so a party with no torch can still see what
 * is biting it; a torch placed by a player lights as usual.
 *
 * <p>The darkness is reversible. Each torch and lantern removed is remembered with its block state and put
 * back in {@link #onWon} and {@link #onFloorEnded}, unless a player has built something there meanwhile.
 * Spared: the lights within {@link FinaleRules#DARK_SPARE_RADIUS} of the exit pad and the terminal, every
 * light in a room that {@code requiresLight} and in a lever Ordeal room (so the lever and its lamp stay
 * findable), and every solid light block (a redstone lamp is a mechanic, a glowstone block is the ceiling).
 */
final class MineshaftFinale implements FinaleScript {

    /** The warning on the action bar. */
    static final String TELL = "The beams creak.";

    /** The next action, shown as the lights go out. */
    static final String HINT = "Light a torch.";

    /** The boss: the wave's strongest mob (a zombie), named and given an elite's health. */
    static final String BOSS_NAME = "The Pit Boss";
    static final int BOSS_HEALTH = 40;
    private static final DungeonDef.Elite BOSS = new DungeonDef.Elite("minecraft:zombie", BOSS_NAME, BOSS_HEALTH,
            "minecraft:iron_pickaxe");

    /**
     * The three phases (2026-10-15 arena floors). One: the Pit Boss and his crew, lights on. Two: the beams
     * creak and the lights fail. Three: the rest of the shaft wakes as cave spiders.
     */
    private static final List<BossFight.Step> PHASES = List.of(
            new BossFight.Step(1.0, List.of(), (ctx, boss) -> {
            }),
            new BossFight.Step(2.0 / 3.0, List.of("The Pit Boss reels.", "The lights fail."),
                    (ctx, boss) -> startDark(ctx)),
            new BossFight.Step(1.0 / 3.0, List.of("The Pit Boss is cornered.", "The shaft wakes."),
                    (ctx, boss) -> releaseSpiders(ctx)));

    /** Per run state: when the lights go out and what was removed. */
    private static final class Dark {
        long darkAt = -1;
        boolean dark;
        boolean spiders;
        final Map<BlockPos, BlockState> removed = new LinkedHashMap<>();
    }

    /** The wave's first zombie becomes the boss; the wave keeps its size. */
    @Override
    public void onWaveSpawned(FinaleContext ctx) {
        Mob pick = null;
        for (Mob mob : ctx.liveMobs()) {
            if (mob.getType() == EntityTypes.ZOMBIE) {
                pick = mob;
                break;
            }
            if (pick == null) {
                pick = mob;
            }
        }
        ctx.adoptElite(pick, BOSS);
    }

    private static BossFight fight(FinaleContext ctx) {
        return ctx.scriptState(BossFight.class, () -> new BossFight(PHASES));
    }

    /** Phase two: the warning now, the dark after the tell. */
    private static void startDark(FinaleContext ctx) {
        Dark dark = ctx.scriptState(Dark.class, Dark::new);
        if (dark.darkAt >= 0) {
            return;
        }
        dark.darkAt = ctx.tickCount() + FinaleRules.darkTellTicks(PocketDungeonsConfig.mineshaftDarkTellSeconds());
        for (ServerPlayer member : ctx.members()) {
            ctx.level().playSound(null, member.blockPosition(), SoundEvents.WOOD_BREAK, SoundSource.AMBIENT, 1.5f,
                    0.5f);
            member.sendOverlayMessage(Component.literal(TELL).withStyle(ChatFormatting.GOLD));
        }
    }

    @Override
    public void onTick(FinaleContext ctx) {
        fight(ctx).tick(ctx);
        Dark dark = ctx.scriptState(Dark.class, Dark::new);
        if (dark.darkAt < 0 || dark.dark || ctx.tickCount() < dark.darkAt || ctx.members().isEmpty()) {
            return;
        }
        dark.dark = true;
        darken(ctx, dark);
        for (ServerPlayer member : ctx.members()) {
            ctx.level().playSound(null, member.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.AMBIENT,
                    1.0f, 0.6f);
            member.sendOverlayMessage(Component.literal(HINT).withStyle(ChatFormatting.YELLOW));
        }
    }

    /** Phase three: the rest of the wave comes as glowing cave spiders (a second batch, held to the wave cap). */
    private static void releaseSpiders(FinaleContext ctx) {
        Dark dark = ctx.scriptState(Dark.class, Dark::new);
        if (dark.spiders) {
            return;
        }
        dark.spiders = true;
        int count = FinaleRules.darkSpiders(PocketDungeonsConfig.mineshaftDarkSpiders(), ctx.party(),
                ctx.finale().perMemberPercent(), ctx.total());
        for (int i = 0; i < count; i++) {
            Mob spider = ctx.spawnNearParty("minecraft:cave_spider");
            if (spider != null) {
                spider.setGlowingTag(true);
            }
        }
    }

    @Override
    public void onWon(FinaleContext ctx) {
        restore(ctx.level(), ctx.scriptState(Dark.class, Dark::new));
    }

    @Override
    public void onFloorEnded(FinaleContext ctx) {
        restore(ctx.level(), ctx.scriptState(Dark.class, Dark::new));
    }

    // ---- the lights ----------------------------------------------------------------------------------

    /**
     * Removes every torch and lantern of the final floor's occupied cells that is not spared, once, in the
     * tick the lights go out (a bounded scan of the floor's own cells, no more).
     */
    private static void darken(FinaleContext ctx, Dark dark) {
        InstanceRecord record = ctx.record();
        ServerLevel level = ctx.level();
        PlanGeometry geometry = record.layout.geometry();
        BlockPos pad = record.layout.exitPad();
        BlockPos terminal = record.layout.terminal();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (PlanCell cell : geometry.cells()) {
            BlockPos origin = geometry.cellOrigin(cell);
            int minY = 0;
            FloorRooms.Room room = record.floor.rooms.get(cell);
            if (room != null) {
                RoomManifest.Entry entry = entryOf(room.id());
                if (entry == null || entry.meta.requiresLight
                        || DungeonLight.LEVER_ORDEAL_CONTENT.contains(entry.meta.content)) {
                    continue;
                }
                minY = entry.meta.spanY > 1 ? -RoomGeometry.storyOffset(entry.meta.spanY) : 0;
            }
            if (Ordeals.objectiveAt(origin) != null || Ordeals.isResolved(origin)) {
                continue;
            }
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    int wx = origin.getX() + x;
                    int wz = origin.getZ() + z;
                    if (spared(wx, wz, pad) || spared(wx, wz, terminal)) {
                        continue;
                    }
                    for (int y = minY; y <= RoomGeometry.CEILING_Y; y++) {
                        cursor.set(wx, origin.getY() + y, wz);
                        BlockState state = level.getBlockState(cursor);
                        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                        if (DungeonLight.isLightSource(path) && !DungeonLight.isSolidLight(path)) {
                            BlockPos at = cursor.immutable();
                            dark.removed.put(at, state);
                            level.setBlock(at, Blocks.AIR.defaultBlockState(),
                                    Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                        }
                    }
                }
            }
        }
    }

    private static boolean spared(int x, int z, BlockPos pad) {
        return pad != null && FinaleRules.nearPad(x, z, pad.getX(), pad.getZ());
    }

    private static RoomManifest.Entry entryOf(String name) {
        RoomManifest.Entry entry = RoomManifest.current().byName(name);
        return entry != null ? entry : RoomManifest.currentAnomaly().byName(name);
    }

    /** Puts back every removed light that is still air, then forgets them; safe to call twice. */
    private static void restore(ServerLevel level, Dark dark) {
        for (Map.Entry<BlockPos, BlockState> light : dark.removed.entrySet()) {
            BlockPos at = light.getKey();
            if (level.isLoaded(at) && level.getBlockState(at).isAir()) {
                level.setBlock(at, light.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        dark.removed.clear();
    }
}
