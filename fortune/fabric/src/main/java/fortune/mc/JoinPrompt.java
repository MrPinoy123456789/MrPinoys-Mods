package fortune.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The prompt a player gets a few seconds after joining (so it lands below the MOTD and pack prompt, as Charter
 * Currency's login snapshot does). Java: one clickable chat line. Bedrock: chat links cannot be clicked, so Floodgate
 * players get a modal form with Spin and Later. A player whose spin is used gets the countdown line instead.
 */
final class JoinPrompt {

    private record Pending(UUID player, long dueTick) {
    }

    private final FortuneService service;
    private final List<Pending> pending = new ArrayList<>();

    JoinPrompt(FortuneService service) {
        this.service = service;
    }

    void queue(ServerPlayer player, long dueTick) {
        pending.removeIf(p -> p.player().equals(player.getUUID()));
        pending.add(new Pending(player.getUUID(), dueTick));
    }

    void tick(MinecraftServer server) {
        if (pending.isEmpty()) return;
        long now = server.getTickCount();
        pending.removeIf(entry -> {
            if (now < entry.dueTick()) return false;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.player());
            if (player != null) send(player);
            return true;
        });
    }

    /** Sends the prompt now. Package private for the gametests. */
    void send(ServerPlayer player) {
        if (!service.canSpinToday(player.getUUID())) {
            player.sendSystemMessage(Messages.plain("Fantasy's Fortune: next spin in " + service.nextSpinIn() + "."));
            return;
        }
        Reveal.sound(player, SoundEvents.NOTE_BLOCK_CHIME, 0.4f, 1.0f);
        if (Bedrock.isBedrock(player.getUUID())) {
            boolean sent = Bedrock.modal(service.server(), player.getUUID(), "Fantasy's Fortune",
                    "Your reels are loaded.", "Spin", "Later", button -> {
                        ServerPlayer again = service.server().getPlayerList().getPlayer(player.getUUID());
                        if (button == 0 && again != null) service.open(again, null);
                    });
            if (sent) return;
        }
        MutableComponent line = Component.literal("Fantasy's Fortune: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("your reels are loaded. ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("[Spin]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/spin"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the machine")))))
                .append(Component.literal(" "))
                .append(Component.literal("[Later]").withStyle(s -> s.withColor(ChatFormatting.GRAY)
                        .withClickEvent(new ClickEvent.RunCommand("/fortune later"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("The reels will wait. /spin any time today.")))));
        player.sendSystemMessage(line);
    }
}
