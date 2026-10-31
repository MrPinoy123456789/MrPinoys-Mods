package chartercurrency.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The stand-in kingdom source for a server without Charter: ops create kingdoms and
 * set members and Lords by command. It is also the in-memory fake the core tests use.
 *
 * <p>A Lord is always a member of their own kingdom; naming a Lord moves them in, and
 * moving a Lord out of their kingdom vacates the office. A player is in at most one
 * kingdom. Thread safe; {@code onChange} runs after every mutation so the fabric side
 * can persist {@code kingdoms.json}.
 */
public final class ManualKingdomSource implements KingdomSource {

    /** One kingdom, in the shape {@code kingdoms.json} stores it. */
    public static final class Kingdom {
        public String name;
        public String lord;
        public List<String> members = new ArrayList<>();

        public Kingdom() {}

        Kingdom(Kingdom other) {
            name = other.name;
            lord = other.lord;
            members = new ArrayList<>(other.members);
        }
    }

    private final Map<String, Kingdom> kingdoms = new LinkedHashMap<>();
    private final Runnable onChange;
    private final Object lock = new Object();

    public ManualKingdomSource() {
        this(Map.of(), () -> { });
    }

    public ManualKingdomSource(Map<String, Kingdom> initial, Runnable onChange) {
        this.onChange = onChange;
        for (Map.Entry<String, Kingdom> e : initial.entrySet()) {
            Kingdom k = new Kingdom(e.getValue());
            if (k.members == null) k.members = new ArrayList<>();
            kingdoms.put(e.getKey().toLowerCase(Locale.ROOT), k);
        }
    }

    // ---- KingdomSource ------------------------------------------------------

    @Override
    public Optional<String> kingdomOf(UUID player) {
        if (player == null) return Optional.empty();
        String id = player.toString();
        synchronized (lock) {
            for (Map.Entry<String, Kingdom> e : kingdoms.entrySet()) {
                if (e.getValue().members.contains(id)) return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<UUID> lordOf(String kingdomId) {
        synchronized (lock) {
            Kingdom k = get(kingdomId);
            if (k == null || k.lord == null || k.lord.isBlank()) return Optional.empty();
            try {
                return Optional.of(UUID.fromString(k.lord));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    @Override
    public String displayName(String kingdomId) {
        synchronized (lock) {
            Kingdom k = get(kingdomId);
            return k == null || k.name == null ? String.valueOf(kingdomId) : k.name;
        }
    }

    @Override
    public Collection<String> kingdomIds() {
        synchronized (lock) {
            return List.copyOf(kingdoms.keySet());
        }
    }

    // ---- edits --------------------------------------------------------------

    /** Create a kingdom. False if the id is blank or already taken. */
    public boolean create(String id, String name) {
        if (id == null || id.isBlank()) return false;
        // "commons" is the ledger for Renown earned outside any kingdom.
        if (id.equalsIgnoreCase(Treasury.COMMONS)) return false;
        synchronized (lock) {
            String key = id.toLowerCase(Locale.ROOT);
            if (kingdoms.containsKey(key)) return false;
            Kingdom k = new Kingdom();
            k.name = name == null || name.isBlank() ? id : name;
            kingdoms.put(key, k);
        }
        onChange.run();
        return true;
    }

    /** Remove a kingdom and release its members. False if it does not exist. */
    public boolean remove(String id) {
        synchronized (lock) {
            if (kingdoms.remove(id == null ? "" : id.toLowerCase(Locale.ROOT)) == null) return false;
        }
        onChange.run();
        return true;
    }

    /**
     * Put a player in a kingdom, or in none when {@code kingdomId} is null. A player
     * leaving a kingdom they were Lord of vacates the office.
     *
     * @return false if the kingdom does not exist
     */
    public boolean setMember(UUID player, String kingdomId) {
        if (player == null) return false;
        synchronized (lock) {
            Kingdom target = null;
            if (kingdomId != null) {
                target = get(kingdomId);
                if (target == null) return false;
            }
            removeEverywhere(player);
            if (target != null) target.members.add(player.toString());
        }
        onChange.run();
        return true;
    }

    /** Name a Lord, moving them into the kingdom. False if the kingdom is unknown. */
    public boolean setLord(String kingdomId, UUID player) {
        if (player == null) return false;
        synchronized (lock) {
            Kingdom k = get(kingdomId);
            if (k == null) return false;
            removeEverywhere(player);
            k.members.add(player.toString());
            k.lord = player.toString();
        }
        onChange.run();
        return true;
    }

    /** Copy of the whole state, for persistence. */
    public Map<String, Kingdom> snapshot() {
        synchronized (lock) {
            Map<String, Kingdom> out = new LinkedHashMap<>();
            for (Map.Entry<String, Kingdom> e : kingdoms.entrySet()) {
                out.put(e.getKey(), new Kingdom(e.getValue()));
            }
            return out;
        }
    }

    /** Member count of a kingdom. */
    public int memberCount(String kingdomId) {
        synchronized (lock) {
            Kingdom k = get(kingdomId);
            return k == null ? 0 : k.members.size();
        }
    }

    private Kingdom get(String id) {
        return id == null ? null : kingdoms.get(id.toLowerCase(Locale.ROOT));
    }

    private void removeEverywhere(UUID player) {
        String id = player.toString();
        for (Kingdom k : kingdoms.values()) {
            k.members.remove(id);
            if (id.equals(k.lord)) k.lord = null;
        }
    }
}
