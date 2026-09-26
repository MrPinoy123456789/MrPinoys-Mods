package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Headless room pack validation for the web room editor ({@code gradlew validateRooms}).
 *
 * <p>Runs the mod's own load-time room checks against a resources folder
 * without a server: {@link DungeonRoomMeta#fromJson} parses each
 * {@code dungeon_room} and {@code anomaly_room} file, the template is read and
 * data-fixed exactly as {@code TemplateSource.readStructure} does and loaded
 * into a {@link StructureTemplate}, and the real (private)
 * {@code RoomManifest.buildEntry} is invoked by reflection, so door jigsaw
 * placement, facing and the partial door rule are the game's code, not a copy.
 *
 * <p>What it cannot check without a running server, and says so: that a
 * {@code processors} list exists in the registry (it only looks in this pack's
 * own worldgen folder), and templates or processor lists that another pack or
 * vanilla would supply. The in-game save gate ({@link RoomValidator}) needs a
 * live build room; its template-visible rules are mirrored below and labelled
 * as such.
 *
 * <p>Lives outside {@code src/} on purpose: it is tooling for the editor and
 * is compiled by its own Gradle task against the main source set output.
 */
public final class RoomPackValidatorMain {

    private static int errors;
    private static int warnings;

    private RoomPackValidatorMain() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "src/main/resources").toAbsolutePath().normalize();
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        Bootstrap.bootStrap();

        Method buildEntry = RoomManifest.class.getDeclaredMethod("buildEntry",
                String.class, DungeonRoomMeta.class, StructureTemplate.class);
        buildEntry.setAccessible(true);

        List<Path> namespaces = findNamespaces(root);
        if (namespaces.isEmpty()) {
            System.out.println("[Error] no data/<namespace>/dungeon_room or anomaly_room folder under " + root);
            System.exit(2);
        }
        int rooms = 0;
        for (Path nsDir : namespaces) {
            String ns = nsDir.getFileName().toString();
            for (String kind : List.of("dungeon_room", "anomaly_room")) {
                Path dir = nsDir.resolve(kind);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                List<Path> files;
                try (Stream<Path> s = Files.walk(dir)) {
                    files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
                }
                System.out.println("== " + ns + " " + kind + " (" + files.size() + " files)");
                for (Path file : files) {
                    rooms++;
                    checkRoom(buildEntry, nsDir, ns, kind, dir, file);
                }
            }
        }
        System.out.println();
        System.out.println("validateRooms: " + rooms + " room file(s), " + errors + " error(s), " + warnings + " warning(s)");
        System.exit(errors > 0 ? 1 : 0);
    }

    private static List<Path> findNamespaces(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        if (Files.isDirectory(root.resolve("dungeon_room")) || Files.isDirectory(root.resolve("anomaly_room"))) {
            out.add(root);
            return out;
        }
        Path data = Files.isDirectory(root.resolve("data")) ? root.resolve("data") : root;
        try (Stream<Path> s = Files.list(data)) {
            for (Path p : s.sorted().toList()) {
                if (Files.isDirectory(p.resolve("dungeon_room")) || Files.isDirectory(p.resolve("anomaly_room"))) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static void checkRoom(Method buildEntry, Path nsDir, String ns, String kind, Path kindDir, Path file) {
        String rel = kindDir.relativize(file).toString().replace('\\', '/');
        String id = ns + ":" + rel.substring(0, rel.length() - ".json".length());
        String base = file.getFileName().toString();
        List<String> notes = new ArrayList<>();
        if (base.startsWith("_")) {
            System.out.println("  skip  " + id + " (leading underscore, the manifest ignores it)");
            return;
        }
        if (rel.startsWith("versions/")) {
            warn(notes, "this file sits in versions/ but the manifest lists " + kind
                    + " recursively, so the game loads it as an extra live room");
        }
        DungeonRoomMeta meta;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') {
                text = text.substring(1);
            }
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            meta = DungeonRoomMeta.fromJson(obj, id);
        } catch (Exception e) {
            reject(id, "metadata rejected: " + e.getMessage(), notes);
            return;
        }

        String template = meta.template;
        int colon = template.indexOf(':');
        String tns = colon < 0 ? "minecraft" : template.substring(0, colon);
        String tpath = colon < 0 ? template : template.substring(colon + 1);
        Path nbt = nsDir.getParent().resolve(tns).resolve("structure").resolve(tpath + ".nbt");
        if (!Files.exists(nbt)) {
            reject(id, "template not found: " + template + " (looked for " + nbt + "; another pack could still supply it)", notes);
            return;
        }

        StructureTemplate st;
        try (InputStream in = Files.newInputStream(nbt)) {
            CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            int dataVersion = NbtUtils.getDataVersion(tag, 500);
            tag = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), tag, dataVersion);
            st = new StructureTemplate();
            st.load(BuiltInRegistries.BLOCK, tag);
        } catch (Exception e) {
            reject(id, "template unreadable: " + e, notes);
            return;
        }

        if (meta.processors != null) {
            String p = meta.processors;
            int c = p.indexOf(':');
            Path proc = nsDir.getParent().resolve(c < 0 ? "minecraft" : p.substring(0, c))
                    .resolve("worldgen/processor_list").resolve((c < 0 ? p : p.substring(c + 1)) + ".json");
            if (!Files.exists(proc)) {
                warn(notes, "processor list " + p + " is not in this pack; the game rejects the room if no pack or vanilla provides it");
            }
        }

        // Size contract: 16 wide, 16 deep, CEILING_Y + 1 plus one STORY_HEIGHT per extra story.
        Vec3i size = st.getSize();
        int wantY = RoomGeometry.CEILING_Y + 1 + RoomGeometry.storyOffset(meta.spanY);
        if (size.getX() != RoomGeometry.CELL || size.getZ() != RoomGeometry.CELL || size.getY() != wantY) {
            error(notes, "template is " + size.getX() + "x" + size.getY() + "x" + size.getZ()
                    + " but spanY " + meta.spanY + " needs " + RoomGeometry.CELL + "x" + wantY + "x" + RoomGeometry.CELL);
        }

        // The game's own load gate.
        RoomManifest.Entry entry;
        try {
            entry = (RoomManifest.Entry) buildEntry.invoke(null, id, meta, st);
        } catch (InvocationTargetException e) {
            reject(id, file.getFileName() + " - " + e.getCause().getMessage(), notes);
            return;
        } catch (IllegalAccessException e) {
            reject(id, "could not invoke RoomManifest.buildEntry: " + e, notes);
            return;
        }

        // RoomValidator's template-visible rules (the in-game save gate), mirrored.
        long doorLike = st.getJigsaws(BlockPos.ZERO, Rotation.NONE).stream()
                .filter(j -> j.name().toString().contains("door")).count();
        boolean spawnAnchor = st.getJigsaws(BlockPos.ZERO, Rotation.NONE).stream()
                .anyMatch(j -> j.name().toString().equals("pocketdungeons:spawn"));
        int spawners = count(st, Blocks.TRIAL_SPAWNER);
        int vaults = count(st, Blocks.VAULT);
        int containers = count(st, Blocks.CHEST) + count(st, Blocks.TRAPPED_CHEST) + count(st, Blocks.BARREL)
                + count(st, Blocks.HOPPER) + count(st, Blocks.DISPENSER) + count(st, Blocks.DROPPER)
                + count(st, Blocks.DECORATED_POT);
        if (doorLike == 0 && !meta.roles.contains(RoleIds.ENTRANCE)) {
            error(notes, "RoomValidator: no door jigsaws and not an entrance room; unreachable in a dungeon");
        }
        if (meta.roles.contains(RoleIds.ENCOUNTER) && spawners == 0 && !spawnAnchor) {
            warn(notes, "RoomValidator: encounter room has no trial spawner or spawn anchor");
        }
        if (meta.roles.contains(RoleIds.LOOT) && vaults == 0 && containers == 0) {
            warn(notes, "RoomValidator: loot room has no vault or chest");
        }

        boolean bad = notes.stream().anyMatch(n -> n.startsWith("[Error]"));
        System.out.println((bad ? "  FAIL  " : "  ok    ") + id + "  mask " + DoorMask.toLetters(entry.maskAtRotation0)
                + ", " + size.getX() + "x" + size.getY() + "x" + size.getZ() + ", spanY " + meta.spanY);
        notes.forEach(n -> System.out.println("          " + n));
    }

    private static int count(StructureTemplate st, Block block) {
        return st.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), block).size();
    }

    private static void reject(String id, String reason, List<String> notes) {
        errors++;
        System.out.println("  FAIL  " + id + "  rejected: " + reason);
        notes.forEach(n -> System.out.println("          " + n));
    }

    private static void error(List<String> notes, String msg) {
        errors++;
        notes.add("[Error] " + msg);
    }

    private static void warn(List<String> notes, String msg) {
        warnings++;
        notes.add("[Warning] " + msg);
    }
}
