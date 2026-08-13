package hearsay;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Player-level opt-out for all non-command Hearsay output.
 */
public final class Mute {

    private final Set<UUID> muted = new HashSet<>();

    public boolean isMuted(UUID player) {
        return muted.contains(player);
    }

    public boolean toggle(UUID player) {
        if (muted.contains(player)) {
            muted.remove(player);
            return false;
        }
        muted.add(player);
        return true;
    }

    public Set<UUID> allMuted() {
        return Collections.unmodifiableSet(muted);
    }
}
