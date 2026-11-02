package charter.mc.cmd;

import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.org.Organization;
import charter.org.PlayerRecord;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * How every operator command reads a {@code <player>} and a {@code <faction>}, so they all accept the same things. A
 * player is an online name, a UUID (online or not), or the name of anyone the server has seen. A faction is its name
 * (any case) or its id ({@code org:...}). The operators' menu always passes UUIDs and ids, so two players with the same
 * name are never confused.
 */
public final class Resolve {

    /** A player found for a command: who, the name to print, and the online player when they are online. */
    public record Who(PlayerId id, String name, ServerPlayer online) {
    }

    private Resolve() {
    }

    public static Optional<Who> player(MinecraftServer server, CharterRuntime rt, String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        var list = server.getPlayerList();
        ServerPlayer online = list.getPlayer(raw);
        UUID uuid = uuid(raw);
        if (online == null && uuid != null) online = list.getPlayer(uuid);
        if (online != null) {
            PlayerId id = new PlayerId(online.getUUID());
            rt.state.touch(id, online.getPlainTextName(), rt.clock.now());
            return Optional.of(new Who(id, online.getPlainTextName(), online));
        }
        for (PlayerRecord p : rt.state.allPlayers()) {
            if (uuid != null ? p.id.value().equals(uuid) : p.name.equalsIgnoreCase(raw)) {
                return Optional.of(new Who(p.id, p.name.isEmpty() ? raw : p.name, null));
            }
        }
        return Optional.empty();
    }

    public static Optional<Who> player(CommandContext<CommandSourceStack> ctx, CharterRuntime rt) {
        return player(ctx.getSource().getServer(), rt, StringArgumentType.getString(ctx, "player"));
    }

    /** A faction by name (any case) or id; one that no longer exists is not found. */
    public static Optional<Organization> faction(CharterRuntime rt, String raw) {
        if (raw == null) return Optional.empty();
        String wanted = raw.strip();
        return rt.state.orgs().stream().filter(Organization::exists)
                .filter(o -> o.id.toString().equalsIgnoreCase(wanted) || o.name().equalsIgnoreCase(wanted)).findFirst();
    }

    /** The kingdoms and factions players made, by name, A to Z: what the operators' commands act on. */
    public static List<Organization> factions(CharterRuntime rt) {
        return rt.state.orgs().stream().filter(o -> o.exists() && !o.npc)
                .sorted(Comparator.comparing(o -> o.name().toLowerCase(Locale.ROOT))).toList();
    }

    /** Suggests faction names, quoted where a name needs it. */
    public static CompletableFuture<Suggestions> factions(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (!charter.mc.CharterServer.running()) return builder.buildFuture();
        return SharedSuggestionProvider.suggest(factions(charter.mc.CharterServer.runtime()).stream()
                .map(o -> StringArgumentType.escapeIfRequired(o.name())), builder);
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
