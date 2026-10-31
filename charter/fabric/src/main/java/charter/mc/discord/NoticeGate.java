package charter.mc.discord;

import charter.id.OrgId;
import charter.id.PlayerId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Keeps the join request messages from becoming spam. Three rules, all plain data:
 * the same player asking the same faction again within the cooldown is not told
 * about again; a member who was messaged lately is not messaged again until the quiet
 * window has passed (the requests that came in meanwhile are counted, and one message
 * then covers them); and a member can mute the messages altogether. Time is passed in,
 * so it is testable. Used on the server thread only.
 */
public final class NoticeGate {

    public static final String FILE_NAME = "discord-mutes.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** What to do with a message for one member. */
    public enum Verdict { SEND, HELD, SKIP }

    private final long applicantCooldownMillis;
    private final long quietMillis;
    private final Set<PlayerId> muted = new LinkedHashSet<>();
    private final Map<String, Long> askedAt = new HashMap<>();
    private final Map<PlayerId, Long> toldAt = new HashMap<>();
    private final Map<PlayerId, Integer> waiting = new LinkedHashMap<>();

    public NoticeGate(int applicantCooldownMinutes, int quietMinutes) {
        this.applicantCooldownMillis = Math.max(0, applicantCooldownMinutes) * 60_000L;
        this.quietMillis = Math.max(0, quietMinutes) * 60_000L;
    }

    /** Once per request: false when this player already asked this faction within the cooldown. */
    public boolean worthTelling(PlayerId applicant, OrgId org, long now) {
        String key = applicant.value() + "|" + org;
        Long last = askedAt.get(key);
        if (last != null && applicantCooldownMillis > 0 && now - last < applicantCooldownMillis) return false;
        askedAt.put(key, now);
        if (askedAt.size() > 2000) askedAt.values().removeIf(t -> now - t >= applicantCooldownMillis);
        return true;
    }

    /** Per member who may answer: send now, hold for the digest, or skip because they muted it. */
    public Verdict forMember(PlayerId member, long now) {
        if (muted.contains(member)) return Verdict.SKIP;
        Long last = toldAt.get(member);
        if (last != null && quietMillis > 0 && now - last < quietMillis) {
            waiting.merge(member, 1, Integer::sum);
            return Verdict.HELD;
        }
        toldAt.put(member, now);
        waiting.remove(member);
        return Verdict.SEND;
    }

    /** Members whose quiet window has ended with requests held back, and how many; they count as told now. */
    public Map<PlayerId, Integer> due(long now) {
        Map<PlayerId, Integer> out = new LinkedHashMap<>();
        for (var it = waiting.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Long last = toldAt.get(e.getKey());
            if (muted.contains(e.getKey())) {
                it.remove();
            } else if (last == null || now - last >= quietMillis) {
                out.put(e.getKey(), e.getValue());
                toldAt.put(e.getKey(), now);
                it.remove();
            }
        }
        return out;
    }

    public boolean isMuted(PlayerId member) {
        return muted.contains(member);
    }

    /** Returns true when this changed anything. */
    public boolean setMuted(PlayerId member, boolean mute) {
        if (mute) {
            waiting.remove(member);
            return muted.add(member);
        }
        return muted.remove(member);
    }

    // ---- the file ----

    public void load(Path dir, Consumer<String> warn) {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("muted")) {
                muted.add(new PlayerId(UUID.fromString(e.getAsString())));
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("Could not read " + file + " (" + e.getMessage() + "); nobody is muted. The file was left as it is.");
            muted.clear();
        }
    }

    public void save(Path dir, Consumer<String> warn) {
        JsonArray list = new JsonArray();
        for (PlayerId p : muted) list.add(p.value().toString());
        JsonObject root = new JsonObject();
        root.add("muted", list);
        Path file = dir.resolve(FILE_NAME);
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(FILE_NAME + ".tmp");
            Files.writeString(tmp, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            warn.accept("Could not save " + file + ": " + e);
        }
    }
}
