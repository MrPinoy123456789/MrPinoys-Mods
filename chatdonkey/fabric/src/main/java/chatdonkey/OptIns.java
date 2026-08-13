package chatdonkey;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code config/chatdonkey/optin.json} -- who has agreed to be bothered
 * (SPEC.md section 7).
 *
 * <p>The one piece of this mod that <em>must</em> survive a restart. Everything
 * else here is deliberately in-memory because its worst failure is one extra
 * joke (SPEC.md section 2); forgetting consent has the opposite failure
 * direction, so this file is written the moment it changes rather than on a
 * flush timer.
 *
 * <p>An unreadable file is treated as an empty roster rather than a crash: the
 * failure direction for a cosmetic mod is "nobody gets a donkey", never "the
 * server will not start" (DESIGN.md section 9.5). It is quarantined rather than
 * overwritten, so a fat-fingered hand edit can be recovered.
 */
public final class OptIns {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Path temp;
    private final Set<UUID> players = new LinkedHashSet<>();

    public OptIns(Path directory) {
        this.file = directory.resolve("optin.json");
        this.temp = directory.resolve("optin.json.tmp");
    }

    public boolean contains(UUID player) {
        return players.contains(player);
    }

    public int size() {
        return players.size();
    }

    /** @return false if they were already opted in */
    public boolean add(UUID player) {
        if (!players.add(player)) {
            return false;
        }
        write();
        return true;
    }

    /** @return false if they were not opted in to begin with */
    public boolean remove(UUID player) {
        if (!players.remove(player)) {
            return false;
        }
        write();
        return true;
    }

    /** Reads the roster. Call once, from {@code SERVER_STARTED}. */
    public void load() {
        players.clear();

        if (!Files.exists(file)) {
            // The server died between the write and the rename: the temp file is
            // the newer roster, and losing it means losing consent records.
            if (Files.exists(temp)) {
                ChatDonkeyMod.LOG.warn("optin.json is missing but a temp file exists -- recovering from it");
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    ChatDonkeyMod.LOG.error("Could not recover optin.json", e);
                    return;
                }
            } else {
                ChatDonkeyMod.LOG.info("No opt-in roster yet -- nobody is signed up");
                return;
            }
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<String> raw = GSON.fromJson(reader, new TypeToken<List<String>>() {}.getType());
            if (raw != null) {
                for (String id : raw) {
                    try {
                        players.add(UUID.fromString(id));
                    } catch (IllegalArgumentException ignored) {
                        ChatDonkeyMod.LOG.warn("Skipping malformed opt-in entry: {}", id);
                    }
                }
            }
            ChatDonkeyMod.LOG.info("{} players are opted in to chat donkey", players.size());
        } catch (IOException | RuntimeException e) {
            ChatDonkeyMod.LOG.error("optin.json is unreadable -- quarantining it and starting empty. "
                    + "Everyone will have to opt in again.", e);
            quarantine();
        }
    }

    private void quarantine() {
        try {
            Files.move(file, file.resolveSibling("optin.json.corrupt"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            ChatDonkeyMod.LOG.error("Could not quarantine the bad opt-in roster", e);
        }
    }

    private void write() {
        List<String> out = new ArrayList<>(players.size());
        for (UUID player : players) {
            out.add(player.toString());
        }

        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(out, writer);
                writer.flush();
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // The in-memory roster stands for this session, so the player who
            // just opted in still gets what they asked for today.
            ChatDonkeyMod.LOG.error("Failed to write optin.json", e);
        }
    }
}
