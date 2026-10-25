package charter.mc.cmd;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One-shot tokens behind every clickable link (PLAN.md §7.3). A page binds each
 * link to the player it was shown to and to one thing to do; the link runs
 * {@code /charter act <token>}. A token works once, only for that player, and
 * only for a while, so a token someone else types does nothing.
 *
 * <p>Held in memory only: links from before a restart simply stop working.
 *
 * @param <P> the player type (a ServerPlayer in game)
 */
public final class Tokens<P> {

    public static final long LIFETIME_MILLIS = 15 * 60 * 1000L;
    /** Per player, the oldest tokens are forgotten past this many. */
    public static final int PER_PLAYER = 200;
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    private record Bound<P>(UUID player, String label, long expires, Consumer<P> run) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Bound<P>> tokens = new LinkedHashMap<>();

    public String issue(UUID player, long now, Consumer<P> run) {
        return issue(player, "", now, run);
    }

    /** Issues a token with the link text it sits behind, so a test can find the link a player would click. */
    public String issue(UUID player, String label, long now, Consumer<P> run) {
        prune(now, player);
        String token;
        do {
            StringBuilder b = new StringBuilder(10);
            for (int i = 0; i < 10; i++) b.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            token = b.toString();
        } while (tokens.containsKey(token));
        tokens.put(token, new Bound<>(player, label, now + LIFETIME_MILLIS, run));
        return token;
    }

    /** The action bound to this token, if it is this player's and still fresh. Taking it uses it up. */
    public Optional<Consumer<P>> take(String token, UUID player, long now) {
        Bound<P> bound = tokens.get(token);
        if (bound == null || !bound.player.equals(player)) return Optional.empty();
        tokens.remove(token);
        return bound.expires < now ? Optional.empty() : Optional.of(bound.run);
    }

    /** The newest live token issued to a player behind a link with this text, for tests. */
    public Optional<String> newest(UUID player, String label) {
        String found = null;
        for (Map.Entry<String, Bound<P>> e : tokens.entrySet()) {
            if (e.getValue().player.equals(player) && e.getValue().label.equals(label)) found = e.getKey();
        }
        return Optional.ofNullable(found);
    }

    /** Every live token issued to a player, oldest first. For tests. */
    public List<String> issuedTo(UUID player) {
        List<String> out = new ArrayList<>();
        tokens.forEach((token, bound) -> {
            if (bound.player.equals(player)) out.add(token);
        });
        return out;
    }

    private void prune(long now, UUID player) {
        int mine = 0;
        for (Bound<P> b : tokens.values()) if (b.player.equals(player)) mine++;
        Iterator<Map.Entry<String, Bound<P>>> it = tokens.entrySet().iterator();
        while (it.hasNext()) {
            Bound<P> b = it.next().getValue();
            if (b.expires < now) {
                if (b.player.equals(player)) mine--;
                it.remove();
            } else if (b.player.equals(player) && mine >= PER_PLAYER) {
                mine--;
                it.remove();
            }
        }
    }
}
