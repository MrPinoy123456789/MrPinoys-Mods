package lemon;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;

final class LemonJournal {
    static void lemonAsk(ServerPlayer player, String text, String answeredBy, int hintTier, long waitSeconds) {
        JsonObject event = new JsonObject();
        event.addProperty("text", text);
        event.addProperty("answered_by", answeredBy);
        event.addProperty("hint_tier", hintTier);
        event.addProperty("wait_s", waitSeconds);
        write(player, "lemon_ask", event);
    }

    static void lemonAnswer(ServerPlayer player, String question, String text) {
        JsonObject event = new JsonObject();
        event.addProperty("question", question);
        event.addProperty("text", text);
        write(player, "lemon_answer", event);
    }

    static void lemonHeard(ServerPlayer player, String from, String text) {
        JsonObject event = new JsonObject();
        event.addProperty("from", from);
        event.addProperty("text", text);
        write(player, "lemon_heard", event);
    }

    static void report(ServerPlayer player, String text) {
        JsonObject event = new JsonObject();
        event.addProperty("text", text);
        write(player, "report", event);
    }

    private static void write(ServerPlayer player, String kind, JsonObject event) {
        Instant now = Instant.now();
        event.addProperty("t", now.toString());
        event.addProperty("ev", kind);
        event.addProperty("player", player.getUUID().toString());
        event.addProperty("name", player.getName().getString());
        var directory = player.level().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("lemon/journal").resolve(now.atOffset(ZoneOffset.UTC).toLocalDate().toString());
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(player.getUUID() + ".jsonl"), event + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LemonMod.LOG.warn("Could not write Lemon journal for {}", player.getUUID(), e);
        }
    }
}
