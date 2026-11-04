package chartercurrency.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Banks standing in the world and the rules for placing one (owner, 2026-11-03).
 *
 * <p>A Bank is a block a kingdom's Lord places on that kingdom's own land; a kingdom has
 * one. Any smithing table in the same chunk is its Mint. Every Bank works the same for
 * every player: the Mint tax still goes to the striker's own kingdom, not the Bank's.
 * An operator may place one anywhere, and one on nobody's land belongs to no kingdom
 * and does not count against any.
 *
 * <p>A site is a dimension and a block, {@code namespace:dimension x y z}, the same form
 * as the bound Mint tables. No inventory and no clock here: the fabric side asks.
 */
public final class Banks {

    /** Why a Bank may not be placed, or {@link #NONE}. */
    public enum Refusal {
        NONE,
        /** The player is in no kingdom. */
        NO_KINGDOM,
        /** The player is not one of their kingdom's Lords. */
        NOT_A_LORD,
        /** Nothing on this server knows who holds land, so only an operator may place one. */
        NO_LAND,
        /** The chunk is not the player's own kingdom's land. */
        NOT_YOUR_LAND,
        /** The kingdom already has its Bank. */
        ALREADY_HAS_ONE
    }

    /**
     * The answer to a placement.
     *
     * @param refusal why not, or {@link Refusal#NONE}
     * @param kingdom the kingdom the Bank would belong to, empty for nobody's
     */
    public record Placement(Refusal refusal, Optional<String> kingdom) {
        public boolean allowed() {
            return refusal == Refusal.NONE;
        }
    }

    /** The treasury Take buttons on the Bank menu, in Sovereigns. */
    public static final int[] TREASURY_TAKES = {1, 10, 100};

    /** The treasury Give buttons on the Bank menu, in Sovereigns ("all" is a fourth). */
    public static final int[] TREASURY_GIVES = {1, 10};

    /**
     * Whether the Bank menu shows this player a Take button for {@code marks} of their kingdom's treasury. An
     * operator always sees it. Anyone else needs the spend right that whoever governs spending recognises
     * ({@link KingdomSource#maySpend}); the button then runs that governor's own withdrawal, so a shown button
     * is one that will work and a hidden one was never a door.
     */
    public static boolean showsTreasuryTake(KingdomSource kingdoms, String kingdomId, UUID player, boolean operator,
                                            long marks) {
        if (marks <= 0) return false;
        if (operator) return true;
        if (kingdoms == null || kingdomId == null || player == null) return false;
        return kingdoms.maySpend(kingdomId, player, marks);
    }

    /** site key to kingdom id ("" for a Bank that belongs to no kingdom), in placing order. */
    private final Map<String, String> sites = new LinkedHashMap<>();

    public Banks() {}

    /** Loads the stored sites; a blank kingdom means nobody's. */
    public Banks(Map<String, String> stored) {
        if (stored != null) stored.forEach((k, v) -> sites.put(k, v == null ? "" : v));
    }

    /** The site key for a block. */
    public static String key(String dimension, int x, int y, int z) {
        return dimension + " " + x + " " + y + " " + z;
    }

    /**
     * May this player place a Bank here.
     *
     * @param operator      an operator places anywhere; the Bank belongs to whoever holds the land
     * @param playerKingdom the player's kingdom, if any
     * @param lord          whether the player is one of that kingdom's Lords
     * @param landKnown     whether anything knows who holds land
     * @param landOwner     the kingdom holding the chunk, if any
     */
    public Placement check(boolean operator, Optional<String> playerKingdom, boolean lord,
                           boolean landKnown, Optional<String> landOwner) {
        Optional<String> owner;
        if (operator) {
            owner = landKnown ? landOwner : playerKingdom;
        } else {
            if (playerKingdom.isEmpty()) return new Placement(Refusal.NO_KINGDOM, Optional.empty());
            if (!lord) return new Placement(Refusal.NOT_A_LORD, playerKingdom);
            if (!landKnown) return new Placement(Refusal.NO_LAND, playerKingdom);
            if (!landOwner.equals(playerKingdom)) return new Placement(Refusal.NOT_YOUR_LAND, playerKingdom);
            owner = playerKingdom;
        }
        if (owner.isPresent() && siteOf(owner.get()).isPresent()) {
            return new Placement(Refusal.ALREADY_HAS_ONE, owner);
        }
        return new Placement(Refusal.NONE, owner);
    }

    /** Records a Bank. Returns false if the site already was one. */
    public boolean add(String site, Optional<String> kingdom) {
        if (sites.containsKey(site)) return false;
        sites.put(site, kingdom.orElse(""));
        return true;
    }

    /** Forgets a Bank. Returns false if the site was not one. */
    public boolean remove(String site) {
        return sites.remove(site) != null;
    }

    public boolean isBank(String site) {
        return sites.containsKey(site);
    }

    /** The kingdom a Bank belongs to, empty for nobody's or for no Bank. */
    public Optional<String> kingdomOf(String site) {
        String k = sites.get(site);
        return k == null || k.isEmpty() ? Optional.empty() : Optional.of(k);
    }

    /** Where a kingdom's Bank stands, if it has one. */
    public Optional<String> siteOf(String kingdom) {
        for (Map.Entry<String, String> e : sites.entrySet()) {
            if (e.getValue().equals(kingdom)) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    /** Whether a Bank stands in this chunk, which makes a smithing table there a Mint. */
    public boolean inChunk(String dimension, int chunkX, int chunkZ) {
        for (String site : sites.keySet()) {
            String[] p = site.split(" ");
            if (p.length != 4 || !p[0].equals(dimension)) continue;
            try {
                if (Math.floorDiv(Integer.parseInt(p[1]), 16) == chunkX
                        && Math.floorDiv(Integer.parseInt(p[3]), 16) == chunkZ) return true;
            } catch (NumberFormatException ignored) {
                // A hand-edited line that is not a site; it makes no Mint.
            }
        }
        return false;
    }

    /** Every site and its kingdom ("" for nobody's), for saving and listing. */
    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(sites);
    }

    public List<String> siteList() {
        return List.copyOf(sites.keySet());
    }
}
