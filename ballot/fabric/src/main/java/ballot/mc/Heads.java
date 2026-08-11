package ballot.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.world.item.component.ResolvableProfile;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Custom head skins, configured rather than compiled in.
 *
 * <p>A player head's texture travels in its profile data, not in a resource pack, so a
 * custom skin shows up on a completely vanilla client. The values are the base64 blobs
 * published by head databases — each one encodes a URL pointing at a skin file on
 * Mojang's texture server.
 *
 * <p>Only one texture, not one per state. {@code SkullBlockEntity} exposes its profile
 * but has no setter for it, so the mod cannot restyle an already-placed head; changing
 * the look as a poll progressed would mean breaking and replacing the block. The sign
 * and the confirmation message carry state better than a subtle skin difference would
 * anyway.
 */
public final class Heads {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * A fixed id keeps the profile stable between restarts. It is never resolved
     * against Mojang — the texture is already attached, so nothing needs looking up.
     */
    private static final UUID BALLOT_BOX_ID =
            UUID.nameUUIDFromBytes("ballot:box".getBytes(StandardCharsets.UTF_8));

    private final Path file;
    private Config config = new Config();

    public Heads(Path dir) {
        this.file = dir.resolve("heads.json");
    }

    /**
     * @param ballotBox base64 texture value, or blank for a plain head
     */
    private static final class Config {
        String ballotBox = "";
        String note = "Paste a base64 texture value from a head database "
                + "(the long string labelled Value). Blank means a plain head. "
                + "Run /ballot reload after editing.";
    }

    public void reload() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(new Config(), w);
                }
                BallotMod.LOG.info("Created default heads.json");
                return;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Config parsed = GSON.fromJson(r, Config.class);
                if (parsed != null) {
                    config = parsed;
                }
            }
        } catch (IOException | RuntimeException e) {
            BallotMod.LOG.warn("Could not read heads.json; using plain heads: {}",
                    e.getMessage());
        }
    }

    public boolean hasBallotBoxTexture() {
        return config.ballotBox != null && !config.ballotBox.isBlank();
    }

    /**
     * The profile to hang on a ballot box item, if one is configured.
     *
     * <p>Isolated here on purpose: this is the corner of the API that renamed
     * {@code GameProfile}'s accessors and introduced {@code NameAndId}, so if anything
     * in this mod breaks on a future version, it will be this method. Everything else
     * works fine with plain heads.
     */
    public Optional<ResolvableProfile> ballotBoxProfile() {
        if (!hasBallotBoxTexture()) {
            return Optional.empty();
        }
        try {
            // GameProfile is a record and PropertyMap wraps a multimap, so both are
            // built up front rather than mutated after the fact.
            PropertyMap properties = new PropertyMap(ImmutableMultimap.of(
                    "textures", new Property("textures", config.ballotBox.trim())));

            GameProfile profile = new GameProfile(BALLOT_BOX_ID, "BallotBox", properties);
            return Optional.of(ResolvableProfile.createResolved(profile));
        } catch (RuntimeException e) {
            BallotMod.LOG.warn("Couldn't build the ballot box skin; "
                    + "check the texture value in heads.json: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
