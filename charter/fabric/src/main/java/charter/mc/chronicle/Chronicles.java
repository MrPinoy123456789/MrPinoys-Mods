package charter.mc.chronicle;

import charter.chronicle.TemplateSet;
import charter.id.OrgId;
import charter.land.Core;
import charter.mc.CharterMod;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Chronicle in the world (M13, DESIGN.md section 17): lecterns that hold it as a written book.
 *
 * <ul>
 * <li><b>Charter lecterns.</b> Any lectern standing in a keep's core chunk holds that
 * faction's Chronicle.</li>
 * <li><b>Realm Chronicle.</b> Lecterns an operator placed with {@code /charter admin
 * realm-chronicle here} hold the server's.</li>
 * </ul>
 *
 * <p>Books are regenerated shortly after a new history entry arrives, and a lectern whose
 * book was taken gets a fresh one within ten seconds. A lectern holding a book this mod did
 * not write is left alone. Core writes every sentence; this only turns entries into pages.
 */
public final class Chronicles {

    /** The tag on every book this mod writes: its scope, so a lectern can tell its own book from a player's. */
    static final String SCOPE_KEY = "charter_chronicle";
    static final String HASH_KEY = "charter_chronicle_hash";
    /** How often a changed history is looked at, and how often every lectern is checked anyway. */
    public static final int CHANGE_CHECK_TICKS = 20;
    static final int FULL_CHECK_TICKS = 200;

    private static TemplateSet templates = charter.chronicle.DefaultTemplates.get();
    private static int seenHistory = -1;
    private static long ticks;

    private Chronicles() {
    }

    /** M13: one call from {@code CharterMod.onInitialize}. */
    public static void register() {
        templates = ChronicleVoice.readOrCreate(FabricLoader.getInstance().getConfigDir().resolve(CharterMod.MOD_ID),
                CharterMod.LOG::info, CharterMod.LOG::warn);
        RealmLecterns.registerBlock();
        ChronicleCommands.register();
        ServerTickEvents.END_SERVER_TICK.register(Chronicles::tick);
    }

    /** The voice in use: the built in templates with the server's file over them. */
    public static TemplateSet templates() {
        return templates;
    }

    private static void tick(MinecraftServer server) {
        if (!CharterServer.running()) return;
        ticks++;
        CharterRuntime rt = CharterServer.runtime();
        boolean changed = rt.state.history.size() != seenHistory;
        if (ticks % FULL_CHECK_TICKS == 0 || (changed && ticks % CHANGE_CHECK_TICKS == 0)) {
            seenHistory = rt.state.history.size();
            refresh(server, rt);
        }
    }

    /** Writes the current Chronicle into every lectern that should hold it. Returns how many books were replaced. */
    public static int refresh(MinecraftServer server, CharterRuntime rt) {
        boolean admin = rt.knobs.chronicleShowAdmin;
        Map<OrgId, ChronicleBooks.Volume> factions = new HashMap<>();
        ChronicleBooks.Volume[] realm = new ChronicleBooks.Volume[1];
        int replaced = 0;

        for (Core core : List.copyOf(rt.state.cores())) {
            if (!core.isKeep()) continue;
            ServerLevel level = level(server, core.center.dimension());
            if (level == null) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(core.center.x(), core.center.z());
            if (chunk == null) continue;
            for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                if (!(be instanceof LecternBlockEntity lectern)) continue;
                ChronicleBooks.Volume volume = factions.computeIfAbsent(core.owner,
                        o -> ChronicleBooks.faction(rt.state, templates, admin, o, 1));
                if (install(level, lectern, "org:" + core.owner.value(), volume)) replaced++;
            }
        }

        RealmLecterns realms = RealmLecterns.of(rt.state);
        for (RealmLecterns.Spot spot : realms.all()) {
            ServerLevel level = level(server, spot.dimension());
            if (level == null || level.getChunkSource().getChunkNow(spot.x() >> 4, spot.z() >> 4) == null) continue;
            if (!(level.getBlockEntity(new BlockPos(spot.x(), spot.y(), spot.z())) instanceof LecternBlockEntity lectern)) {
                realms.remove(spot);
                continue;
            }
            if (realm[0] == null) realm[0] = ChronicleBooks.realm(rt.state, templates, admin, 1);
            if (install(level, lectern, "realm", realm[0])) replaced++;
        }
        return replaced;
    }

    /** Puts the volume in the lectern unless it already holds exactly this book or one a player wrote. */
    static boolean install(ServerLevel level, LecternBlockEntity lectern, String scope, ChronicleBooks.Volume volume) {
        if (lectern.hasBook()) {
            CustomData data = lectern.getBook().get(DataComponents.CUSTOM_DATA);
            CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
            boolean ours = !tag.getStringOr(SCOPE_KEY, "").isEmpty();
            if (!ours) return false;
            if (tag.getStringOr(SCOPE_KEY, "").equals(scope) && tag.getIntOr(HASH_KEY, 0) == volume.pages().hashCode()) return false;
        }
        BlockPos pos = lectern.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(LecternBlock.HAS_BOOK) && !state.getValue(LecternBlock.HAS_BOOK)) {
            level.setBlock(pos, state.setValue(LecternBlock.HAS_BOOK, true), 3);
        }
        lectern.setBook(book(volume, scope));
        return true;
    }

    /** The written book for a volume. */
    public static ItemStack book(ChronicleBooks.Volume volume, String scope) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String text : volume.pages()) pages.add(Filterable.passThrough(Component.literal(text)));
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough(volume.title()), "The Chronicle", 0, pages, true));
        CompoundTag tag = new CompoundTag();
        tag.putString(SCOPE_KEY, scope);
        tag.putInt(HASH_KEY, volume.pages().hashCode());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    static ServerLevel level(MinecraftServer server, String dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
    }
}
