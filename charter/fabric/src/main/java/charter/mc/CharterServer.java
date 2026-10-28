package charter.mc;

import charter.id.PlayerId;
import charter.mc.store.Persistence;
import charter.time.Durations;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Map;

/**
 * Makes core live in a server (M7): opens the world's state on start, drives the
 * sim and the governance tick, saves with the world, and greets returning players
 * with what they missed.
 *
 * <p><b>The siege clock pauses while the server is down</b> (D14): the sim only
 * ever advances by real time measured between two of its own steps, and the
 * first step after a start measures from the start, never from the last save.
 */
public final class CharterServer {

    /** A gap longer than this between steps (a freeze, a debugger) counts as this much. */
    public static final long MAX_STEP_MILLIS = Durations.MINUTE;

    private static CharterRuntime runtime;
    private static MinecraftServer server;
    private static long lastStepMillis;
    private static long ticks;

    private CharterServer() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(CharterServer::start);
        ServerTickEvents.END_SERVER_TICK.register(CharterServer::tick);
        ServerLifecycleEvents.AFTER_SAVE.register((s, flush, force) -> save());
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> stop());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> join(handler.player));
    }

    /** The running world's Charter. Only while a server is running. */
    public static CharterRuntime runtime() {
        if (runtime == null) throw new IllegalStateException("Charter is not running");
        return runtime;
    }

    public static boolean running() {
        return runtime != null;
    }

    /** The running server, or null. */
    public static MinecraftServer server() {
        return server;
    }

    public static Path charterDir(MinecraftServer s) {
        return s.getWorldPath(LevelResource.ROOT).resolve(CharterMod.MOD_ID);
    }

    private static void start(MinecraftServer s) {
        Persistence.register();
        server = s;
        runtime = CharterRuntime.open(charterDir(s), CharterMod.knobs(), System::currentTimeMillis, new Messenger(),
                CharterMod.LOG::info, CharterMod.LOG::warn);
        lastStepMillis = runtime.clock.now();
        ticks = 0;
        CharterMod.LOG.info("Charter state ready: {} organization(s), {} claim(s), siege clock at {} ms",
                runtime.state.orgs().size(), runtime.state.claims().size(), runtime.state.siegeClock.now());
    }

    private static void tick(MinecraftServer s) {
        if (runtime == null) return;
        ticks++;
        if (ticks % Math.max(1, runtime.knobs.simStepTicks) != 0) return;
        step();
    }

    /** One sim step: seen players, then siege time by real time elapsed, then governance. */
    static void step() {
        long started = System.nanoTime();
        long now = runtime.clock.now();
        long elapsed = Math.max(0, Math.min(MAX_STEP_MILLIS, now - lastStepMillis));
        lastStepMillis = now;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            runtime.state.touch(new PlayerId(player.getUUID()), player.getPlainTextName(), now);
        }
        charter.mc.war.SiegeWorld.beforeStep(server, runtime);
        charter.mc.granary.Granaries.around(() -> runtime.sim.step(elapsed));
        LoadMeter.step(System.nanoTime() - started);
    }

    private static void save() {
        if (runtime != null) runtime.save();
    }

    private static void stop() {
        if (runtime == null) return;
        runtime.save();
        runtime.close();
        runtime = null;
        server = null;
    }

    private static void join(ServerPlayer player) {
        if (runtime == null) return;
        PlayerId id = new PlayerId(player.getUUID());
        runtime.state.touch(id, player.getPlainTextName(), runtime.clock.now());
        var waiting = runtime.mail.take(id);
        if (!waiting.isEmpty()) {
            player.sendSystemMessage(Component.literal(Text.get("mail.header", "count", waiting.size())).withStyle(ChatFormatting.GOLD));
            for (Mail.Message m : waiting) player.sendSystemMessage(Component.literal("  " + m.text()).withStyle(ChatFormatting.GRAY));
        }
        give(player);
        charter.mc.book.BookMod.onJoin(player);
    }

    /** Hands over whatever the treasury owes this player; what does not fit stays owed. */
    public static void give(ServerPlayer player) {
        PlayerId id = new PlayerId(player.getUUID());
        Map<String, Integer> owed = runtime.mail.takeOwed(id);
        boolean leftOver = false;
        for (Map.Entry<String, Integer> e : owed.entrySet()) {
            Item item = item(e.getKey());
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(left, item.getDefaultMaxStackSize());
                ItemStack stack = new ItemStack(item, n);
                player.getInventory().add(stack);
                int placed = n - stack.getCount();
                left -= placed;
                if (stack.getCount() > 0) break;
            }
            if (left > 0) {
                runtime.mail.owe(id, e.getKey(), left);
                leftOver = true;
            }
        }
        if (leftOver) player.sendSystemMessage(Component.literal(Text.get("vault.owed_left")).withStyle(ChatFormatting.YELLOW));
    }

    public static Item item(String id) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        return item == null ? Items.AIR : item;
    }

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Notices through the live player list. */
    private static final class Messenger implements Notices.Messenger {
        @Override
        public boolean online(PlayerId player) {
            return server != null && server.getPlayerList().getPlayer(player.value()) != null;
        }

        @Override
        public void send(PlayerId player, String text) {
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player.value());
            if (p != null) p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.AQUA));
        }

        @Override
        public void askedToJoin(PlayerId player, String text) {
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player.value());
            if (p == null) return;
            p.sendSystemMessage(Component.literal(text + " ").withStyle(ChatFormatting.AQUA)
                    .append(charter.mc.cmd.Chat.act(p, Text.get("book.button.open_faction"), Text.get("book.button.open_faction_hover"),
                            charter.mc.book.MemberPages::openFaction)));
        }

        @Override
        public void alert(PlayerId player, String text) {
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player.value());
            if (p == null) return;
            p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(5, 50, 15));
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(text).withStyle(ChatFormatting.GOLD)));
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(Text.get("notice.alert_title"))));
            // A quiet confirmation, not a fanfare (docs/DESIGN.md §4.8: volume 0.15 to 0.4).
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL,
                    net.minecraft.sounds.SoundSource.MASTER, p.getX(), p.getY(), p.getZ(), 0.3F, 1.0F, p.getRandom().nextLong()));
        }

        @Override
        public void deliver(PlayerId player) {
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player.value());
            if (p != null && runtime != null) give(p);
        }
    }
}
