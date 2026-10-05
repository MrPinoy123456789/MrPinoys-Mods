package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The floor history (2026-10-02): every floor a player clears, fails or quits,
 * newest first, on the board where the echo shard engine used to be. Each
 * entry keeps what matters at a glance: when, the keystone level, the zone and
 * floor, its affixes, how long it took, and how it ended (for a failure, the
 * room it ended in and what did it).
 *
 * <p>Stored per player in {@link DungeonLog} (codec key {@code floor_history}),
 * the newest {@link #KEPT} entries; the board shows the room owner's newest
 * {@link #SHOWN}.
 */
final class FloorHistory {

    /** How many entries a player keeps. */
    static final int KEPT = 20;
    /** How many the board shows. */
    static final int SHOWN = 10;

    static final String CLEARED = "cleared";
    static final String FAILED = "failed";
    static final String QUIT = "quit";

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MMM d", Locale.ROOT).withZone(ZoneId.systemDefault());

    private FloorHistory() {}

    /**
     * One floor.
     *
     * @param timestamp when it ended, epoch milliseconds
     * @param level     the keystone level of the run
     * @param theme     the zone's theme id
     * @param floor     the floor's number in its trip, from 1
     * @param affixes   the floor's affix ids
     * @param seconds   from the commit to the end
     * @param outcome   {@link #CLEARED}, {@link #FAILED} or {@link #QUIT}
     * @param room      the room id it ended in, for a failure or a quit; empty otherwise
     * @param cause     what killed the player, for a failure; empty otherwise
     * @param layers    how many layers the trip's dungeon has, so the final floor is known;
     *                  0 outside a dungeon (an Endless Mine) and on entries saved before it
     */
    record Entry(long timestamp, int level, String theme, int floor, List<String> affixes,
                 long seconds, String outcome, String room, String cause, int layers) {

        /** An entry with no dungeon layer count. */
        Entry(long timestamp, int level, String theme, int floor, List<String> affixes,
              long seconds, String outcome, String room, String cause) {
            this(timestamp, level, theme, floor, affixes, seconds, outcome, room, cause, 0);
        }

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("t").forGetter(Entry::timestamp),
                Codec.INT.optionalFieldOf("level", 0).forGetter(Entry::level),
                Codec.STRING.optionalFieldOf("theme", "").forGetter(Entry::theme),
                Codec.INT.optionalFieldOf("floor", 0).forGetter(Entry::floor),
                Codec.STRING.listOf().optionalFieldOf("affixes", List.of()).forGetter(Entry::affixes),
                Codec.LONG.optionalFieldOf("seconds", 0L).forGetter(Entry::seconds),
                Codec.STRING.optionalFieldOf("outcome", CLEARED).forGetter(Entry::outcome),
                Codec.STRING.optionalFieldOf("room", "").forGetter(Entry::room),
                Codec.STRING.optionalFieldOf("cause", "").forGetter(Entry::cause),
                Codec.INT.optionalFieldOf("layers", 0).forGetter(Entry::layers)
        ).apply(instance, Entry::new));

        Entry {
            theme = theme == null ? "" : theme;
            affixes = affixes == null ? List.of() : List.copyOf(affixes);
            outcome = outcome == null ? CLEARED : outcome;
            room = room == null ? "" : room;
            cause = cause == null ? "" : cause;
            layers = Math.max(0, layers);
        }
    }

    // ---- recording ---------------------------------------------------------------------

    /** {@code player} cleared floor {@code floor} of {@code record}'s run. */
    static void cleared(ServerPlayer player, InstanceRecord record, int floor) {
        add(player.level().getServer(), player.getUUID(), record, floor, CLEARED, "", "");
    }

    /**
     * The run failed at max omen: every member gets the entry, naming the room
     * {@code dead} fell in and what killed them.
     */
    static void failed(MinecraftServer server, InstanceRecord record, ServerPlayer dead, DamageSource source) {
        String room = FloorRooms.roomAt(record, dead.blockPosition());
        String cause = source == null ? "" : source.typeHolder().unwrapKey()
                .map(key -> key.identifier().getPath()).orElse("");
        for (UUID member : record.members.keySet()) {
            add(server, member, record, record.interval.floorIndex + 1, FAILED, room, cause);
        }
    }

    /** {@code player} quit the floor with {@code /dungeon quit}, standing where they stand. */
    static void quit(ServerPlayer player, InstanceRecord record) {
        add(player.level().getServer(), player.getUUID(), record, record.interval.floorIndex + 1, QUIT,
                FloorRooms.roomAt(record, player.blockPosition()), "");
    }

    private static void add(MinecraftServer server, UUID player, InstanceRecord record, int floor,
                            String outcome, String room, String cause) {
        if (server == null || record == null || record.layout == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        long seconds = record.floor.startedAtTick == 0 ? 0 : Math.max(0, (now - record.floor.startedAtTick) / 20);
        List<String> affixes = record.floor.affixes == null ? List.of() : new ArrayList<>(record.floor.affixes);
        DungeonDef def = TripView.def(record);
        DungeonLog.forServer(server).addFloorHistory(player, new Entry(System.currentTimeMillis(),
                record.layout.keystoneLevel(), record.floor.theme, floor, affixes, seconds, outcome, room, cause,
                def == null ? 0 : def.layers()));
    }

    /** Repaints the board in {@code record}'s staging room, if it has one. */
    static void refresh(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level != null && record != null) {
            DungeonScreen.updateHistory(level, record);
        }
    }

    // ---- the board ---------------------------------------------------------------------

    /**
     * The board: a large heading and a body of fixed-width rows. The body uses
     * the uniform font, whose letters all share one width, so padding each
     * cell to its column width lines the columns up (a proportional font
     * cannot, and a text display cannot place left-aligned columns exactly).
     */
    record Board(Component heading, Component body) {}

    /** Column widths in characters: date, level, zone, progress, affixes, time, ending. */
    static final int[] COLUMNS = {6, 3, 14, 3, 10, 5, 12};

    private static final Style UNIFORM = Style.EMPTY.withFont(
            new FontDescription.Resource(Identifier.withDefaultNamespace("uniform")));

    /** The board for {@code owner}'s room: a title and their newest {@link #SHOWN} floors. */
    static Board board(MinecraftServer server, UUID owner) {
        Component heading = Component.literal("FLOOR HISTORY").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        List<Entry> entries = server == null || owner == null ? List.of()
                : DungeonLog.forServer(server).floorHistoryOf(owner);
        MutableComponent body = Component.empty();
        int deepest = server == null || owner == null ? 0
                : DungeonLog.forServer(server).get(owner).campaign().deepestMineFloor();
        if (entries.isEmpty()) {
            body.append(Component.literal("No floors yet.\nEvery floor you clear or lose shows here.")
                    .withStyle(ChatFormatting.GRAY));
            if (deepest > 0) {
                body.append(Component.literal("\n" + deepestLine(deepest)).withStyle(ChatFormatting.AQUA));
            }
            return new Board(heading, body);
        }
        for (int i = 0; i < Math.min(SHOWN, entries.size()); i++) {
            if (i > 0) {
                body.append(Component.literal("\n"));
            }
            body.append(line(entries.get(i)));
        }
        if (deepest > 0) {
            body.append(Component.literal("\n" + deepestLine(deepest)).withStyle(ChatFormatting.AQUA));
        }
        return new Board(heading, body);
    }

    /** The board's Endless Mine record line: {@code "Deepest Mine floor: 7 (Deepslate)"}. */
    static String deepestLine(int floor) {
        return "Deepest Mine floor: " + floor + " ("
                + EndlessMineRules.layerName(EndlessMineRules.layerOf(floor)) + ")";
    }

    /** One entry as padded cells: date, level, zone, progress, affixes, time, ending. */
    static Component line(Entry e) {
        MutableComponent line = Component.empty();
        cell(line, WHEN.format(Instant.ofEpochMilli(e.timestamp())), COLUMNS[0], ChatFormatting.GRAY);
        cell(line, "L" + e.level(), COLUMNS[1], ChatFormatting.WHITE);
        cell(line, DungeonScreen.themeName(e.theme()), COLUMNS[2], ChatFormatting.WHITE);
        cell(line, progress(e.floor(), e.layers()), COLUMNS[3], progressColour(e.floor(), e.layers()));
        cell(line, affixNames(e.affixes()), COLUMNS[4], ChatFormatting.LIGHT_PURPLE);
        cell(line, duration(e.seconds()), COLUMNS[5], ChatFormatting.WHITE);
        cell(line, endingShort(e), COLUMNS[6] - 1, switch (e.outcome()) {
            case FAILED -> ChatFormatting.RED;
            case QUIT -> ChatFormatting.YELLOW;
            default -> ChatFormatting.GREEN;
        });
        return line;
    }

    /** Appends {@code text} cut or padded to {@code width}, plus one space of gutter. */
    private static void cell(MutableComponent line, String text, int width, ChatFormatting colour) {
        line.append(Component.literal(fit(text, width) + " ").withStyle(UNIFORM).withStyle(colour));
    }

    /** {@code text} cut to {@code width} characters or padded with spaces to it. */
    static String fit(String text, int width) {
        String t = text == null ? "" : text;
        if (t.length() > width) {
            return t.substring(0, width);
        }
        return t + " ".repeat(width - t.length());
    }

    /**
     * "2/5": the floor's place among the layers of its dungeon, or just "2" when the
     * floor was not in a dungeon ({@code layers} 0). Narrow on purpose; the board's
     * progress column is three wide. {@link #progressWords} spells it out.
     */
    static String progress(int floor, int layers) {
        return layers > 0 ? floor + "/" + layers : String.valueOf(floor);
    }

    /** "Floor 2, final at layer 5", or "Floor 2" outside a dungeon. */
    static String progressWords(int floor, int layers) {
        return layers > 0 ? "Floor " + floor + ", final at layer " + layers : "Floor " + floor;
    }

    /** White with no dungeon, yellow short of the final layer, green on it, orange past it. */
    static ChatFormatting progressColour(int floor, int layers) {
        if (layers <= 0) {
            return ChatFormatting.WHITE;
        }
        if (floor > layers) {
            return ChatFormatting.GOLD;
        }
        return floor == layers ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
    }

    /** "CLEARED", "FAILED Sump", "QUIT Thicket": the ending with its room, for the board's narrow column. */
    static String endingShort(Entry e) {
        return switch (e.outcome()) {
            case FAILED -> "FAILED" + (e.room().isEmpty() ? "" : " " + words(e.room()));
            case QUIT -> "QUIT" + (e.room().isEmpty() ? "" : " " + words(e.room()));
            default -> "CLEARED";
        };
    }

    /** "CLEARED", "FAILED in Sump (lava)", "QUIT in Thicket". */
    static String ending(Entry e) {
        return switch (e.outcome()) {
            case FAILED -> "FAILED" + where(e.room()) + (e.cause().isEmpty() ? "" : " (" + words(e.cause()) + ")");
            case QUIT -> "QUIT" + where(e.room());
            default -> "CLEARED";
        };
    }

    /** Minutes and seconds: "7:05". */
    static String duration(long seconds) {
        return (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    private static String where(String room) {
        return room == null || room.isEmpty() ? "" : " in " + words(room);
    }

    /** {@code pocketdungeons:kennel_crossing} or {@code in_fire} as "Kennel Crossing", "In Fire". */
    static String words(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        StringBuilder out = new StringBuilder();
        for (String part : path.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static String affixNames(List<String> ids) {
        List<String> names = new ArrayList<>();
        for (String id : ids) {
            String name = words(id);
            for (AffixDefinition def : AffixManifest.current().definitions()) {
                if (def.id.equals(id)) {
                    name = def.shortName;
                    break;
                }
            }
            names.add(name);
        }
        return String.join(",", names);
    }
}
