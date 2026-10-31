package charter.mc.discord;

import charter.id.PlayerId;
import charter.mc.CharterMod;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.org.OrgEvents;
import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;

/**
 * Charter on Discord: a bot a player links their account to, so a member who may
 * take players in can see and answer their faction's join requests from Discord.
 * Off until {@code config/charter/discord.json} turns it on.
 *
 * <pre>
 *   in game    /charter discord           whether you are linked
 *              /charter discord link      a code to give the bot
 *              /charter discord unlink
 *   Discord    /link code                 links the Discord account to the player
 *              /unlink
 *              /requests                  who asks to join, with Admit and Turn away
 * </pre>
 *
 * <p>Interactions arrive on the gateway's thread. Each is acknowledged at once,
 * answered on the server thread (where all of Charter's state lives), and the
 * answer edited in over HTTP, so the server thread never waits on Discord.
 */
public final class DiscordBridge {

    private static DiscordConfig config = new DiscordConfig();
    private static LinkBook links;
    private static Path linksDir;
    private static volatile DiscordRest rest;
    private static DiscordGateway gateway;
    private static volatile DiscordInteractions interactions;
    private static volatile String applicationId;

    private DiscordBridge() {
    }

    public static void register() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(CharterMod.MOD_ID);
        config = DiscordConfig.readOrCreate(configDir, CharterMod.LOG::info, CharterMod.LOG::warn);
        ServerLifecycleEvents.SERVER_STARTED.register(DiscordBridge::start);
        ServerLifecycleEvents.AFTER_SAVE.register((s, flush, force) -> saveLinks());
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> stop());
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter").then(Commands.literal("discord")
                        .executes(DiscordBridge::status)
                        .then(Commands.literal("link").executes(DiscordBridge::issueCode))
                        .then(Commands.literal("unlink").executes(DiscordBridge::unlink)))));
    }

    /** True while the bot is set up and running. */
    public static boolean on() {
        return links != null;
    }

    // ---- lifecycle ----

    private static void start(MinecraftServer server) {
        String token = config.token(System::getenv);
        if (!config.enabled) {
            CharterMod.LOG.info("Discord is off (config/charter/{}).", DiscordConfig.FILE_NAME);
            return;
        }
        if (token.isEmpty()) {
            CharterMod.LOG.warn("Discord is enabled but has no bot token (botToken in {} or {}); it stays off.",
                    DiscordConfig.FILE_NAME, DiscordConfig.TOKEN_ENV);
            return;
        }
        linksDir = CharterServer.charterDir(server);
        links = new LinkBook(new SecureRandom(), config.linkCodeMinutes * 60_000L);
        links.load(linksDir, CharterMod.LOG::warn);
        rest = new DiscordRest(token, CharterMod.LOG::warn);
        interactions = new DiscordInteractions(links, DiscordBridge::desk,
                id -> CharterServer.running() ? CharterServer.runtime().describe.player(id) : Text.get("name.someone"),
                System::currentTimeMillis, DiscordBridge::saveLinks);
        CharterServer.runtime().state.history.listen(entry -> {
            if (entry.event() instanceof OrgEvents.Asked asked) toldOfRequest(asked);
        });
        gateway = new DiscordGateway(token, new DiscordGateway.Listener() {
            @Override
            public void ready(String app, String botName) {
                boolean first = applicationId == null;
                applicationId = app;
                if (first) {
                    rest.putCommands(app, config.guildId, DiscordMessages.commands());
                    CharterMod.LOG.info("Discord connected as {}; {} linked player(s).", botName, links.size());
                }
            }

            @Override
            public void interaction(JsonObject interaction) {
                answer(server, interaction);
            }
        }, CharterMod.LOG::info, CharterMod.LOG::warn);
        gateway.start();
    }

    private static void stop() {
        if (gateway != null) gateway.stop();
        saveLinks();
        gateway = null;
        rest = null;
        interactions = null;
        links = null;
        linksDir = null;
        applicationId = null;
    }

    private static void saveLinks() {
        if (links != null && linksDir != null) links.save(linksDir, CharterMod.LOG::warn);
    }

    private static JoinDesk desk() {
        if (!CharterServer.running()) return null;
        CharterRuntime rt = CharterServer.runtime();
        return new JoinDesk(rt.state, rt.engine, rt.describe);
    }

    // ---- Discord to the game ----

    /** On the gateway thread: acknowledge, work it out on the server thread, edit the answer in. */
    private static void answer(MinecraftServer server, JsonObject interaction) {
        DiscordRest r = rest;
        String app = applicationId;
        if (r == null || app == null) return;
        String id = interaction.get("id").getAsString();
        String token = interaction.get("token").getAsString();
        boolean click = DiscordInteractions.type(interaction) == DiscordInteractions.TYPE_COMPONENT;
        var deferred = r.defer(id, token, click);
        CompletableFuture<JsonObject> reply = new CompletableFuture<>();
        server.execute(() -> {
            try {
                DiscordInteractions handler = interactions;
                reply.complete(handler == null ? DiscordMessages.text(Text.get("discord.not_running")) : handler.handle(interaction));
            } catch (RuntimeException e) {
                CharterMod.LOG.warn("Discord interaction failed", e);
                reply.complete(DiscordMessages.text(Text.get("discord.failed")));
            }
        });
        deferred.thenCombine(reply, (ack, message) -> message).thenCompose(message -> r.editReply(app, token, message));
    }

    // ---- the game to Discord ----

    /** On the server thread: a player asked to join; message each linked member who may answer. */
    private static void toldOfRequest(OrgEvents.Asked asked) {
        if (!config.notifyJoinRequests || links == null || rest == null) return;
        JoinDesk d = desk();
        if (d == null) return;
        String notice = Text.get("notice.asked", "player", CharterServer.runtime().describe.player(asked.player()),
                "org", CharterServer.runtime().describe.org(asked.org()));
        for (PlayerId member : d.answerers(asked.org())) {
            links.discordOf(member).ifPresent(user -> rest.directMessage(user, DiscordMessages.requests(d.answerable(member), notice)));
        }
    }

    // ---- in game ----

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!on()) {
            player.sendSystemMessage(Component.literal(Text.get("discord.off")).withStyle(ChatFormatting.GRAY));
            return 0;
        }
        boolean linked = links.discordOf(new PlayerId(player.getUUID())).isPresent();
        player.sendSystemMessage(Component.literal(Text.get(linked ? "discord.status.linked" : "discord.status.not_linked"))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int issueCode(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!on()) {
            player.sendSystemMessage(Component.literal(Text.get("discord.off")).withStyle(ChatFormatting.GRAY));
            return 0;
        }
        String code = links.issue(new PlayerId(player.getUUID()), System.currentTimeMillis());
        player.sendSystemMessage(Component.literal(Text.get("discord.code.intro")).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" " + code + " ").withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)
                        .withClickEvent(new ClickEvent.CopyToClipboard(code))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(Text.get("discord.code.hover")))))));
        player.sendSystemMessage(Component.literal(Text.get("discord.code.how", "code", code, "minutes", config.linkCodeMinutes))
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int unlink(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!on()) {
            player.sendSystemMessage(Component.literal(Text.get("discord.off")).withStyle(ChatFormatting.GRAY));
            return 0;
        }
        boolean had = links.unlink(new PlayerId(player.getUUID()));
        if (had) saveLinks();
        player.sendSystemMessage(Component.literal(Text.get(had ? "discord.unlinked_here" : "discord.not_linked_here"))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }
}
