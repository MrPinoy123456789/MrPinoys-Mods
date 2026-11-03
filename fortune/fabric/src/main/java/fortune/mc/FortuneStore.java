package fortune.mc;

import fortune.core.Annals;
import fortune.core.PlayerRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The world's {@code fortune/} folder: {@code players.json} (every player's calendar, streak and last spin, plus the
 * Annals), {@code machines.json} (the Fortune Machine blocks) and {@code spins.jsonl} (one line per spin, append only).
 *
 * <p>A players file that cannot be read is never overwritten: the store marks itself broken and the machine refuses
 * to spin until an operator fixes or removes the file and restarts.
 */
final class FortuneStore {

    static final class PlayersFile {
        Map<String, PlayerRecord> players = new LinkedHashMap<>();
        List<Annals.Win> annals = new ArrayList<>();
    }

    static final class MachinesFile {
        List<MachineSpot> machines = new ArrayList<>();
    }

    /** One Fortune Machine block: a dimension id and a position. */
    static final class MachineSpot {
        String dimension;
        int x;
        int y;
        int z;

        MachineSpot() {
        }

        MachineSpot(String dimension, int x, int y, int z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean at(String dim, int bx, int by, int bz) {
            return dimension.equals(dim) && x == bx && y == by && z == bz;
        }
    }

    /** One spin per line, so no pretty printing. */
    private static final com.google.gson.Gson LINE = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    private final Path dir;
    private PlayersFile players = new PlayersFile();
    private MachinesFile machines = new MachinesFile();
    private String broken;

    FortuneStore(Path dir) {
        this.dir = dir;
        try {
            PlayersFile read = AtomicJson.read(playersFile(), PlayersFile.class);
            if (read != null) players = read;
            if (players.players == null) players.players = new LinkedHashMap<>();
            if (players.annals == null) players.annals = new ArrayList<>();
        } catch (IOException e) {
            broken = e.getMessage();
            FortuneMod.LOG.error("players.json is unreadable: REFUSING to overwrite it, and no spins run until it is fixed or removed. {}", broken);
        }
        try {
            MachinesFile read = AtomicJson.read(machinesFile(), MachinesFile.class);
            if (read != null && read.machines != null) machines = read;
        } catch (IOException e) {
            FortuneMod.LOG.error("machines.json is unreadable; starting with no Fortune Machines and not overwriting it until one is added: {}", e.getMessage());
        }
    }

    /** Why the store will not save, or null when it is fine. */
    String broken() {
        return broken;
    }

    PlayerRecord player(UUID id) {
        return players.players.computeIfAbsent(id.toString(), k -> new PlayerRecord());
    }

    PlayerRecord existing(UUID id) {
        return players.players.get(id.toString());
    }

    Map<String, PlayerRecord> allPlayers() {
        return players.players;
    }

    List<Annals.Win> annals() {
        return players.annals;
    }

    void setAnnals(List<Annals.Win> wins) {
        players.annals = new ArrayList<>(wins);
    }

    /** Writes players.json now. Returns false (and logs) when it could not be written. */
    boolean savePlayers() {
        if (broken != null) return false;
        try {
            AtomicJson.write(playersFile(), players);
            return true;
        } catch (IOException e) {
            FortuneMod.LOG.error("Could not write players.json", e);
            return false;
        }
    }

    List<MachineSpot> machines() {
        return machines.machines;
    }

    void saveMachines() {
        try {
            AtomicJson.write(machinesFile(), machines);
        } catch (IOException e) {
            FortuneMod.LOG.error("Could not write machines.json", e);
        }
    }

    /** Appends one JSON line to spins.jsonl. */
    void appendSpin(Object line) {
        try {
            Files.createDirectories(dir);
            Files.writeString(spinsFile(), LINE.toJson(line) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            FortuneMod.LOG.error("Could not append to spins.jsonl", e);
        }
    }

    List<String> spinLines() {
        try {
            return Files.exists(spinsFile()) ? Files.readAllLines(spinsFile(), StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            FortuneMod.LOG.error("Could not read spins.jsonl", e);
            return List.of();
        }
    }

    Path playersFile() {
        return dir.resolve("players.json");
    }

    private Path machinesFile() {
        return dir.resolve("machines.json");
    }

    private Path spinsFile() {
        return dir.resolve("spins.jsonl");
    }
}
