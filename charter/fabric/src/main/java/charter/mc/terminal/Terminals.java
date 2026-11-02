package charter.mc.terminal;

import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.mc.CharterMod;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.book.CharterMenu;
import charter.mc.book.Cues;
import charter.mc.land.Fixtures;
import charter.mc.land.Protection;
import charter.org.MemberRecord;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.phys.BlockHitResult;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Charter terminals in the world: right-click one and the Charter menu opens, the player's own Home (a native form on
 * Bedrock, a dialog on Java, through {@link CharterMenu#open}). Built so console and Bedrock players never depend on
 * an item in their inventory. A terminal is only a position: any block an operator builds (a lectern looks right).
 * A kingdom's Nexus lodestone is a terminal too, for that kingdom's members, worked out from the fixtures at click time.
 *
 * <p>The click is answered from the top of {@code Guard}'s use block listener, ahead of every protection rule, so it
 * works for a player with no kingdom and no race and inside the spawn, where using most blocks is refused. Sneaking
 * with something in hand is left alone, so an operator can still build against a terminal.
 *
 * <p>Nothing here runs from a chunk event: the cue is a tick task, and a block is only read when a player clicks it.
 */
public final class Terminals {

    /** Ticks between the sparks over each terminal. */
    public static final int CUE_EVERY_TICKS = 40;
    /** Players farther than this from a terminal do not get its sparks. */
    static final double CUE_RANGE = 24;
    /** Ticks a player must wait between two openings, so a held button does not open the menu over and over. */
    static final int OPEN_GAP_TICKS = 10;

    private static final TerminalBook BOOK = new TerminalBook();
    private static Path dir;
    private static long ticks;
    private static final Map<UUID, Long> LAST_OPEN = new HashMap<>();
    /** For tests: how many times a terminal has opened the menu for each player. */
    private static final Map<UUID, Integer> OPENED = new HashMap<>();

    private Terminals() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            dir = CharterServer.charterDir(server);
            BOOK.load(dir, CharterMod.LOG::warn);
            if (!BOOK.isEmpty()) CharterMod.LOG.info("Charter terminals: {} loaded", BOOK.size());
        });
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> save());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            save();
            dir = null;
            LAST_OPEN.clear();
            OPENED.clear();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LAST_OPEN.remove(handler.player.getUUID());
            OPENED.remove(handler.player.getUUID());
        });
        ServerTickEvents.END_SERVER_TICK.register(Terminals::tick);
        TerminalCommands.register();
    }

    // ---- the registry ----

    /** The terminals (read only use outside this class; change them through add, remove and clear so they are saved). */
    public static TerminalBook book() {
        return BOOK;
    }

    public static TerminalBook.Spot spot(Level level, BlockPos pos) {
        return new TerminalBook.Spot(Protection.dimension(level), pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean add(TerminalBook.Spot spot, String core) {
        boolean added = BOOK.add(spot, core);
        if (added) save();
        return added;
    }

    public static boolean remove(TerminalBook.Spot spot) {
        boolean removed = BOOK.remove(spot);
        if (removed) save();
        return removed;
    }

    public static int clear() {
        int n = BOOK.clear();
        save();
        return n;
    }

    /** Whether a registered terminal stands on this block (the Nexus is not counted). */
    public static boolean isTerminal(Level level, BlockPos pos) {
        return !BOOK.isEmpty() && BOOK.at(spot(level, pos)) != null;
    }

    private static void save() {
        if (dir != null) BOOK.save(dir, CharterMod.LOG::warn);
    }

    /** For tests: how many times a terminal opened the menu for this player. */
    public static int opened(ServerPlayer player) {
        return OPENED.getOrDefault(player.getUUID(), 0);
    }

    /** For tests: forgets the gap between openings for this player. */
    public static void forget(UUID id) {
        LAST_OPEN.remove(id);
        OPENED.remove(id);
    }

    // ---- the click ----

    /**
     * The answer to a right-click on a block, or null when it is not a terminal click and the usual rules go on. Called
     * first thing from {@code Guard}'s use block listener.
     */
    public static InteractionResult useBlock(ServerPlayer player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!CharterServer.running() || hand != InteractionHand.MAIN_HAND) return null;
        ItemStack held = player.getItemInHand(hand);
        // Sneaking with something in hand means "place against it", as vanilla treats it.
        if (player.isSecondaryUseActive() && !held.isEmpty()) return null;
        BlockPos pos = hit.getBlockPos();
        boolean terminal = isTerminal(level, pos);
        if (!terminal && !isOwnNexus(player, level, pos, held)) return null;
        long now = player.level().getServer().getTickCount();
        Long last = LAST_OPEN.get(player.getUUID());
        if (last == null || now - last >= OPEN_GAP_TICKS || now < last) {
            LAST_OPEN.put(player.getUUID(), now);
            OPENED.merge(player.getUUID(), 1, Integer::sum);
            Cues.click(player);
            CharterMenu.open(player);
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Whether this block is the Nexus of the player's own kingdom. A compass in hand is left to vanilla, so a member can
     * still point a compass at their lodestone.
     */
    private static boolean isOwnNexus(ServerPlayer player, Level level, BlockPos pos, ItemStack held) {
        if (held.is(Items.COMPASS)) return false;
        Fixtures.Fixture fixture = Protection.fixtureAt(level, pos);
        if (fixture == null || fixture.kind() != Fixtures.Kind.NEXUS) return false;
        CharterRuntime rt = CharterServer.runtime();
        Core core = rt.state.core(fixture.core()).orElse(null);
        if (core == null || !core.isKeep()) return false;
        OrgId owner = rt.state.root(core.owner);
        return rt.state.membership(new PlayerId(player.getUUID())).map(MemberRecord::org)
                .map(rt.state::root).filter(owner::equals).isPresent();
    }

    // ---- a lectern beside each new keep's Nexus ----

    /**
     * Puts a lectern terminal beside a keep's Nexus, inside the Nexus's own chunk, and drops the terminal the keep had
     * before (and its lectern, when one still stands there). Called when a keep is founded or moved; with the
     * {@code keepTerminals} knob off it does nothing. Returns the lectern's block, or null when no side had room.
     */
    public static BlockPos placeBeside(CharterRuntime rt, Level level, BlockPos nexus, CoreId core) {
        if (!rt.knobs.keepTerminals || !(level instanceof ServerLevel)) return null;
        dropKeep(level.getServer(), core, true);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos at = nexus.relative(side);
            if ((at.getX() >> 4) != (nexus.getX() >> 4) || (at.getZ() >> 4) != (nexus.getZ() >> 4)) continue;
            if (!level.getBlockState(at).canBeReplaced()) continue;
            // The lectern's sloped top faces away from the Nexus, so a reader stands with the lodestone behind the lectern.
            level.setBlockAndUpdate(at, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, side));
            add(spot(level, at), core.value().toString());
            return at;
        }
        return null;
    }

    /**
     * Forgets the terminals Charter placed for a keep. With {@code takeBlocks} their lecterns are removed too (a keep
     * that moved); a keep that was lost leaves its lectern standing as a plain block.
     */
    public static void dropKeep(MinecraftServer server, CoreId core, boolean takeBlocks) {
        List<TerminalBook.Terminal> gone = BOOK.removeCore(core.value().toString());
        if (gone.isEmpty()) return;
        save();
        if (!takeBlocks || server == null) return;
        for (TerminalBook.Terminal t : gone) {
            ServerLevel level = levelOf(server, t.spot().dimension());
            if (level == null) continue;
            BlockPos at = new BlockPos(t.spot().x(), t.spot().y(), t.spot().z());
            if (level.getBlockState(at).is(Blocks.LECTERN)) level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
        }
    }

    static ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (Protection.dimension(level).equals(dimension)) return level;
        }
        return null;
    }

    // ---- each tick: a few quiet sparks ----

    private static void tick(MinecraftServer server) {
        if (!CharterServer.running() || BOOK.isEmpty()) return;
        ticks++;
        if (ticks % CUE_EVERY_TICKS != 0 || !CharterServer.runtime().knobs.terminalCues) return;
        List<TerminalBook.Terminal> all = BOOK.all();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            String dimension = Protection.dimension(level);
            for (TerminalBook.Terminal t : all) {
                TerminalBook.Spot s = t.spot();
                if (!s.dimension().equals(dimension)) continue;
                double cx = s.x() + 0.5;
                double cy = s.y() + 1.25;
                double cz = s.z() + 0.5;
                if (player.distanceToSqr(cx, cy, cz) > CUE_RANGE * CUE_RANGE) continue;
                // End rod is the particle KeepBorders and the pads already use because Geyser maps it to Bedrock.
                level.sendParticles(player, ParticleTypes.END_ROD, true, false, cx, cy, cz, 2, 0.2, 0.15, 0.2, 0.01);
            }
        }
    }
}
