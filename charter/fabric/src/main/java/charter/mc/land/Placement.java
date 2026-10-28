package charter.mc.land;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.CampPhase;
import charter.land.Core;
import charter.land.LandEvents;
import charter.land.actions.LoseCore;
import charter.land.actions.PlaceNexus;
import charter.land.actions.PlantCamp;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.org.MemberRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Block placement (PLAN.md §5.6 Mixin 1, §7.1). The Mixin hands every
 * {@code BlockItem.place} here. Placement into protected land is refused for
 * anyone who may not build there. A nexus or war banner item then runs
 * {@code PlaceNexus} or {@code PlantCamp} through the engine; when the engine
 * refuses, the block is taken back and the item returned, so a refusal costs the
 * player nothing.
 */
public final class Placement {

    private Placement() {
    }

    public static InteractionResult place(BlockPlaceContext ctx, Supplier<InteractionResult> original) {
        Level level = ctx.getLevel();
        if (level.isClientSide() || !CharterServer.running()) return original.get();
        BlockPos pos = ctx.getClickedPos();
        var player = ctx.getPlayer();
        if (player != null && !Protection.mayAct(player, level, pos)) {
            Protection.deny(player, level, pos);
            return InteractionResult.FAIL;
        }
        String kind = LandItems.kindOf(ctx.getItemInHand());
        if (kind == null || !(player instanceof ServerPlayer sp)) return original.get();

        ItemStack single = ctx.getItemInHand().copyWithCount(1);
        InteractionResult result = original.get();
        if (!result.consumesAction()) return result;

        String problem = switch (kind) {
            case LandItems.NEXUS -> placeNexus(sp, level, pos, single);
            case LandItems.WAR_BANNER -> plantCamp(sp, level, pos);
            default -> null;
        };
        if (problem == null) return result;

        // Refused: take the block back and give the item back, so nothing was spent.
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        ItemStack held = ctx.getItemInHand();
        if (held.isEmpty()) sp.setItemInHand(ctx.getHand(), single);
        else if (!sp.hasInfiniteMaterials()) held.grow(1);
        sp.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED));
        return InteractionResult.FAIL;
    }

    private static Optional<OrgId> orgOf(CharterRuntime rt, ServerPlayer player) {
        return rt.state.membership(new PlayerId(player.getUUID())).map(MemberRecord::org);
    }

    /** Null on success, else the sentence that says why not. */
    private static String run(CharterRuntime rt, ServerPlayer player, charter.engine.Action action) {
        rt.state.touch(new PlayerId(player.getUUID()), player.getPlainTextName(), rt.clock.now());
        Outcome outcome = rt.engine.run(new Actor.Player(new PlayerId(player.getUUID())), action);
        if (outcome instanceof Outcome.Refused(Reason reason)) {
            String key = "reason." + reason.key();
            return Text.has(key) ? Text.get(key) : reason.text();
        }
        return null;
    }

    private static String placeNexus(ServerPlayer player, Level level, BlockPos pos, ItemStack placed) {
        CharterRuntime rt = CharterServer.runtime();
        Optional<OrgId> org = orgOf(rt, player);
        boolean leavingGroup = org.isPresent() && charter.mc.book.NexusItems.foundingName(placed).isPresent()
                && rt.state.groupStartedBy(new PlayerId(player.getUUID())).isPresent();
        if (org.isEmpty() || leavingGroup) {
            // A player with no faction, or the starter of a group breaking away, places a named Nexus to found a faction and its keep.
            Optional<String> name = charter.mc.book.NexusItems.foundingName(placed);
            if (name.isEmpty()) return Text.get("book.nexus.unnamed");
            return charter.mc.book.FoundingPlacement.found(player, level, pos, name.get(), charter.mc.book.NexusItems.bannerOf(placed));
        }
        ChunkKey chunk = chunkOf(level, pos);
        String problem = run(rt, player, new PlaceNexus(org.get(), chunk));
        if (problem != null) return problem;
        recordNexus(rt, level, pos, chunk);
        player.sendSystemMessage(Component.literal(Text.get("land.nexus_placed", "org", rt.describe.org(org.get()))).withStyle(ChatFormatting.GREEN));
        return null;
    }

    /** Remembers the block of the nexus of the keep just raised on this chunk (shared with founding). */
    public static void recordNexus(CharterRuntime rt, Level level, BlockPos pos, ChunkKey chunk) {
        Core core = rt.state.claimAt(chunk).flatMap(c -> rt.state.core(c.core())).orElseThrow();
        Fixtures.of(rt.state).add(new Fixtures.Fixture(Fixtures.Kind.NEXUS, Protection.dimension(level),
                pos.getX(), pos.getY(), pos.getZ(), core.id));
    }

    private static String plantCamp(ServerPlayer player, Level level, BlockPos pos) {
        CharterRuntime rt = CharterServer.runtime();
        Optional<OrgId> org = orgOf(rt, player);
        if (org.isEmpty()) return Text.get("page.no_org");
        ChunkKey chunk = chunkOf(level, pos);
        String problem = run(rt, player, new PlantCamp(org.get(), chunk));
        if (problem != null) return problem;
        Core core = rt.state.claimAt(chunk).flatMap(c -> rt.state.core(c.core())).orElseThrow();
        Fixtures fixtures = Fixtures.of(rt.state);
        fixtures.add(new Fixtures.Fixture(Fixtures.Kind.BANNER, Protection.dimension(level),
                pos.getX(), pos.getY(), pos.getZ(), core.id));
        BlockPos fire = raiseSignal(level, pos);
        if (fire != null) {
            fixtures.add(new Fixtures.Fixture(Fixtures.Kind.CAMPFIRE, Protection.dimension(level),
                    fire.getX(), fire.getY(), fire.getZ(), core.id));
        }
        player.sendSystemMessage(Component.literal(Text.get("land.camp_raising", "hours", rt.knobs.campRaisingHours)).withStyle(ChatFormatting.GREEN));
        return null;
    }

    /**
     * The signal campfire (PLAN.md §7.2): a hay bale beside the banner with a lit
     * campfire on it, burning signal smoke. It stays inside the banner's own chunk
     * so raising a camp never writes into a neighbour's land. Null when no spot is free.
     */
    public static BlockPos raiseSignal(Level level, BlockPos banner) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos hay = banner.relative(side);
            BlockPos fire = hay.above();
            if ((hay.getX() >> 4) != (banner.getX() >> 4) || (hay.getZ() >> 4) != (banner.getZ() >> 4)) continue;
            if (!level.getBlockState(hay).canBeReplaced() || !level.getBlockState(fire).canBeReplaced()) continue;
            level.setBlock(hay, Blocks.HAY_BLOCK.defaultBlockState(), 3);
            BlockState lit = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, true);
            level.setBlock(fire, lit, 3);
            return fire;
        }
        return null;
    }

    public static ChunkKey chunkOf(Level level, BlockPos pos) {
        return new ChunkKey(Protection.dimension(level), pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * A break of one of the mod's objects. Returns false to stop the break.
     * The nexus stands while its keep does. A raising camp's banner and campfire
     * come down for anyone, and taking the banner down ends the camp. A finished
     * camp's banner stays while the camp is protected; once a sally has emptied its
     * hold and left it exposed (DESIGN.md §10.6), anyone may break it, and that ends
     * the camp too.
     */
    public static boolean allowBreak(ServerPlayer player, Level level, BlockPos pos, Fixtures.Fixture fixture) {
        CharterRuntime rt = CharterServer.runtime();
        Fixtures fixtures = Fixtures.of(rt.state);
        Core core = rt.state.core(fixture.core()).orElse(null);
        if (core == null) {
            fixtures.remove(fixture);
            return true;
        }
        switch (fixture.kind()) {
            case NEXUS -> {
                player.sendSystemMessage(Component.literal(Text.get("land.nexus_unbreakable")).withStyle(ChatFormatting.RED), true);
                return false;
            }
            case BANNER -> {
                if (core.phase() != CampPhase.RAISING) {
                    var site = rt.state.siege.camps.get(core.id);
                    if (site != null && site.exposed) {
                        rt.engine.run(new Actor.System(), new LoseCore(core.id));
                        player.sendSystemMessage(Component.literal(Text.get("land.camp_broken")).withStyle(ChatFormatting.YELLOW));
                        return true;
                    }
                    if (!Protection.mayAct(player, level, pos)) Protection.deny(player, level, pos);
                    else player.sendSystemMessage(Component.literal(Text.get("land.banner_stays")).withStyle(ChatFormatting.RED), true);
                    return false;
                }
                // Anyone may pull a raising banner down, and that ends the camp.
                rt.engine.run(new Actor.System(), new LoseCore(core.id));
                player.sendSystemMessage(Component.literal(Text.get("land.camp_cut_down")).withStyle(ChatFormatting.YELLOW));
                return true;
            }
            case CAMPFIRE -> {
                if (core.phase() == CampPhase.RAISING) {
                    fixtures.remove(fixture);
                    return true;
                }
                if (!Protection.mayAct(player, level, pos)) {
                    Protection.deny(player, level, pos);
                    return false;
                }
                fixtures.remove(fixture);
                return true;
            }
        }
        return true;
    }

    /** History listener: a core that is gone takes its fixtures out of the registry. */
    public static void onHistory(charter.history.History.Entry entry) {
        if (entry.event() instanceof LandEvents.CoreLost lost && CharterServer.running()) {
            Fixtures.of(CharterServer.runtime().state).removeCore(lost.core());
        }
    }
}
