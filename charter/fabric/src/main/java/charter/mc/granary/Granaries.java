package charter.mc.granary;

import charter.id.ChunkKey;
import charter.id.PlayerId;
import charter.land.Core;
import charter.mc.CharterMod;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.granary.GranaryBook.Pos;
import charter.mc.granary.GranaryBook.Site;
import charter.mc.granary.GranaryService.Access;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * M9: the granary and the camp store as real chests (PLAN.md §6). Wires the events;
 * the work is in {@link GranaryService}.
 *
 * <ul>
 * <li>{@code food.json} is read when the server starts.</li>
 * <li>A chest placed in a keep's core chunk by a member becomes its granary, and
 * chests touching it join, up to {@code granaryMaxChests}. A barrel (or chest)
 * placed beside a war banner in a camp's chunk becomes its store.</li>
 * <li>Right-clicking one opens {@link GranaryScreen}, or is refused to outsiders.</li>
 * <li>{@link #around} wraps every sim step so the real items follow what core ate.</li>
 * </ul>
 */
public final class Granaries {

    private static final int DISPLAY_TICKS = 20;

    private record Pending(ServerPlayer player, ServerLevel level, BlockPos clicked, BlockPos placed) {
    }

    private static GranaryService service;
    private static final GranaryDisplays DISPLAYS = new GranaryDisplays();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static long ticks;

    private Granaries() {
    }

    /** The one register call: events, the block in the store, the admin command. */
    public static void register() {
        GranaryBook.register();
        ServerLifecycleEvents.SERVER_STARTING.register(Granaries::start);
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            if (CharterServer.running()) CharterServer.runtime().state.history.listen(Granaries::onHistory);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> stop());
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (service != null) {
                var pos = chunk.getPos();
                service.onChunkLoad(level.dimension().identifier().toString(), pos.x(), pos.z(), chunk);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(Granaries::tick);
        UseBlockCallback.EVENT.register(Granaries::onUse);
        charter.mc.land.HopperGate.register(new GranaryHopperGate());
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> GranaryCommands.register(dispatcher));
    }

    /** The running world's granary service, or null when no server is running. */
    public static GranaryService service() {
        return service;
    }

    private static void start(MinecraftServer server) {
        service = new GranaryService(server, CharterServer.runtime());
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(CharterMod.MOD_ID);
        FoodConfig.load(configDir, service.food(), Granaries::vanillaNourishment, CharterMod.LOG::info, CharterMod.LOG::warn);
        ticks = 0;
        PENDING.clear();
    }

    private static void stop() {
        DISPLAYS.forgetAll();
        service = null;
    }

    /** The vanilla food component's nutrition for an item id. */
    public static OptionalDouble vanillaNourishment(String id) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        if (item == null || item == Items.AIR) return OptionalDouble.empty();
        FoodProperties food = item.components().get(DataComponents.FOOD);
        return food == null ? OptionalDouble.empty() : OptionalDouble.of(food.nutrition());
    }

    /** Runs a sim step or an admin advance so the real chests follow what core eats and spoils. */
    public static void around(Runnable step) {
        if (service == null) step.run();
        else service.around(step);
    }

    // ---- events ----

    private static InteractionResult onUse(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level world,
                                           InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        if (service == null || !(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        ItemStack held = sp.getItemInHand(hand);
        BlockPos at = hit.getBlockPos();
        String dimension = level.dimension().identifier().toString();
        Site site = service.book().at(dimension, new Pos(at.getX(), at.getY(), at.getZ()));
        if (site == null) {
            if (held.is(Items.CHEST) || held.is(Items.BARREL)) {
                PENDING.add(new Pending(sp, level, at, at.relative(hit.getDirection())));
            }
            return InteractionResult.PASS;
        }
        // Sneaking with something in hand is placing a block against it, not opening it.
        if (sp.isShiftKeyDown() && !held.isEmpty()) return InteractionResult.PASS;
        Core core = service.coreOf(site).orElse(null);
        if (core == null) return InteractionResult.PASS;
        Access access = service.access(new PlayerId(sp.getUUID()), core);
        if (access == Access.NONE) {
            sp.sendSystemMessage(Component.literal(Text.get("granary.locked")).withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        if (service.loaded(site)) service.reconcile(site, null);
        new GranaryScreen(sp, service, site, core, access).open();
        return InteractionResult.SUCCESS;
    }

    private static void tick(MinecraftServer server) {
        if (service == null) return;
        ticks++;
        if (!PENDING.isEmpty()) {
            List<Pending> now = List.copyOf(PENDING);
            PENDING.clear();
            for (Pending p : now) placed(server, p);
        }
        if (ticks % DISPLAY_TICKS == 0) showDisplays();
    }

    /** A chest or barrel may have just been placed by this player: link it if it belongs to a granary. */
    private static void placed(MinecraftServer server, Pending p) {
        ServerPlayer player = p.player();
        for (BlockPos at : List.of(p.placed(), p.clicked())) {
            if (GranaryService.containerAt(p.level(), null, new Pos(at.getX(), at.getY(), at.getZ())) == null) continue;
            ChunkKey chunk = new ChunkKey(p.level().dimension().identifier().toString(), at.getX() >> 4, at.getZ() >> 4);
            Core core = service.coreAtChunk(chunk).orElse(null);
            if (core == null || service.fallen(core)) continue;
            if (service.access(new PlayerId(player.getUUID()), core) == Access.NONE) continue;
            String problem = service.link(core, p.level(), at, true, true);
            if (problem == null) {
                player.sendSystemMessage(Component.literal(Text.get(core.isKeep() ? "granary.linked" : "granary.linked_camp")).withStyle(ChatFormatting.GREEN));
            } else if (!problem.equals("granary.wrong_chunk") && !problem.equals("granary.already") && !problem.equals("granary.not_a_container")) {
                player.sendSystemMessage(Component.literal(Text.get(problem, "max", service.runtime().knobs.granaryMaxChests)).withStyle(ChatFormatting.YELLOW));
            }
            return;
        }
    }

    private static void showDisplays() {
        for (Site site : service.book().sites()) {
            Core core = service.coreOf(site).orElse(null);
            if (core == null) {
                service.book().remove(site.core);
                DISPLAYS.clear(site.core);
                continue;
            }
            if (!service.loaded(site)) continue;
            ServerLevel level = service.level(site.dimension);
            DISPLAYS.show(level, site.core, site.containers.getFirst(), GranaryText.display(service.runtime(), core));
        }
    }

    /** Forces a display refresh now (after an admin change, and in tests). */
    public static void refreshDisplays() {
        if (service != null) showDisplays();
    }

    /**
     * A keep or camp that is gone takes its granary record with it, as repair would on
     * the next load. Its chests stay in the world as ordinary chests: after a raze,
     * whatever food is left in them is loot.
     */
    static void onHistory(charter.history.History.Entry entry) {
        if (service == null || !(entry.event() instanceof charter.land.LandEvents.CoreLost lost)) return;
        if (service.book().site(lost.core()) == null) return;
        service.book().remove(lost.core());
        forget(lost.core());
    }

    /** Drops a site's display (the site is being removed by hand). */
    static void forget(charter.id.CoreId core) {
        DISPLAYS.clear(core);
    }

    /** The display entity above a site, or null when none is showing. */
    public static net.minecraft.world.entity.Display.TextDisplay displayEntity(charter.id.CoreId core) {
        return DISPLAYS.entity(core);
    }

    /** The text last shown above a site's display, or null when none is showing. */
    public static String displayText(charter.id.CoreId core) {
        return DISPLAYS.text(core);
    }
}
