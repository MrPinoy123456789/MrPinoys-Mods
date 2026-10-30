package charter.mc.chronicle;

import charter.chronicle.Calendar;
import charter.chronicle.ChronicleRenderer;
import charter.chronicle.NameLookup;
import charter.chronicle.TemplateSet;
import charter.contract.Party;
import charter.history.History;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.LandEvents;
import charter.state.CharterState;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the history into the pages of a written book. Core writes every sentence
 * ({@link ChronicleRenderer}); this only chooses which entries belong in a book and
 * lays the sentences out on pages. No Minecraft types, so a plain test checks it.
 *
 * <p>A volume is a title page and up to {@link #CONTENT_PAGES} pages of entries, newest
 * first: {@link #MAX_PAGES} pages at most, each far under {@link #MAX_PAGE_CHARS}
 * characters (PLAN.md risk R4). A lectern holds volume 1; older volumes are given on
 * request. Only as many entries are rendered as the asked volume needs, so a long
 * history costs no more than a short one.
 */
public final class ChronicleBooks {

    /** What the vanilla editor allows on a page; the hard ceiling for every page here. */
    public static final int MAX_PAGE_CHARS = 1024;
    /** What a book may hold. */
    public static final int MAX_PAGES = 100;
    /** Pages in one volume, the title page included (PLAN.md risk R4 says 50). */
    public static final int VOLUME_PAGES = 50;
    /** Entry pages in a volume. */
    public static final int CONTENT_PAGES = VOLUME_PAGES - 1;
    /** What a page can show without clipping in the vanilla book screen: about fourteen short lines. */
    static final int PAGE_TARGET = 230;

    /** One book: its title, its pages as plain text, and whether older volumes exist. */
    public record Volume(String title, int number, boolean hasOlder, List<String> pages) {
    }

    private ChronicleBooks() {
    }

    /** The Chronicle of one organization: the entries that name it or its camps and keeps. */
    public static Volume faction(CharterState state, TemplateSet templates, boolean showAdmin, OrgId org, int volume) {
        String name = state.orgIncludingDissolved(org).map(o -> o.name()).orElse("an unknown organization");
        Map<CoreId, OrgId> owners = coreOwners(state);
        return build(state, templates, showAdmin, "Chronicle of " + name, e -> involves(e.event(), org, owners), volume, name, "/charter chronicle volume ");
    }

    /** The server's Chronicle: every entry that is told. */
    public static Volume realm(CharterState state, TemplateSet templates, boolean showAdmin, int volume) {
        return build(state, templates, showAdmin, "The Realm Chronicle", e -> true, volume, "the realm", "/charter chronicle realm volume ");
    }

    private static Volume build(CharterState state, TemplateSet templates, boolean showAdmin, String title,
                                java.util.function.Predicate<History.Entry> wanted, int volume, String about, String more) {
        int number = Math.max(1, volume);
        List<History.Entry> all = state.history.entries();
        long origin = all.isEmpty() ? 0 : all.getFirst().event().at();
        ChronicleRenderer renderer = new ChronicleRenderer(templates, Calendar.startingAt(origin), NameLookup.from(state), showAdmin);

        // Newest first. Stop as soon as the volume asked for is full and one page more is known to exist.
        int need = CONTENT_PAGES * number + 1;
        Pager pager = new Pager();
        // Told oldest first so one happening that left several entries is told once, then read from the newest back.
        List<History.Entry> chosen = new ArrayList<>();
        for (History.Entry entry : all) {
            if (wanted.test(entry)) chosen.add(entry);
        }
        List<String> told = renderer.render(chosen);
        for (int i = told.size() - 1; i >= 0 && pager.pages.size() < need; i--) pager.add(told.get(i));
        pager.finish();

        int from = CONTENT_PAGES * (number - 1);
        boolean hasOlder = pager.pages.size() > CONTENT_PAGES * number;
        List<String> pages = new ArrayList<>();
        pages.add(titlePage(title, number, hasOlder, more));
        if (number > 1 && from >= pager.pages.size()) {
            pages.add("There is nothing older.");
        } else {
            pages.addAll(pager.pages.subList(from, Math.min(pager.pages.size(), CONTENT_PAGES * number)));
        }
        if (pages.size() == 1) pages.add("Nothing has been written about " + about + " yet.");
        String bookTitle = number == 1 ? title : title + " " + number;
        return new Volume(bookTitle.length() > 32 ? bookTitle.substring(0, 32) : bookTitle, number, hasOlder, List.copyOf(pages));
    }

    private static String titlePage(String title, int number, boolean hasOlder, String more) {
        StringBuilder out = new StringBuilder(title).append("\n\nVolume ").append(number).append(". Newest tales first.");
        if (hasOlder) out.append("\n\nOlder tales are kept in volumes: ").append(more).append(number + 1);
        return out.toString();
    }

    /** Fills pages with whole entries; an entry longer than a page runs on to the next. */
    static final class Pager {
        final List<String> pages = new ArrayList<>();
        private final StringBuilder page = new StringBuilder();

        void add(String entry) {
            String rest = entry.strip();
            while (!rest.isEmpty()) {
                int room = PAGE_TARGET - page.length() - (page.isEmpty() ? 0 : 2);
                if (rest.length() <= room) {
                    append(rest);
                    return;
                }
                // Does the whole entry fit on a page of its own? Then start a new page rather than splitting it.
                if (!page.isEmpty() && rest.length() <= PAGE_TARGET) {
                    flush();
                    continue;
                }
                int cut = cutAt(rest, Math.max(0, room));
                if (cut <= 0) {
                    flush();
                    continue;
                }
                append(rest.substring(0, cut).strip());
                flush();
                rest = rest.substring(cut).strip();
            }
        }

        void finish() {
            flush();
        }

        private void append(String text) {
            if (!page.isEmpty()) page.append("\n\n");
            page.append(text);
        }

        private void flush() {
            if (!page.isEmpty()) pages.add(page.toString());
            page.setLength(0);
        }

        /** Where to cut {@code text} so the first part fits {@code room}: at a space when there is one. */
        private static int cutAt(String text, int room) {
            if (room < 40 && room < text.length()) return -1;
            int space = text.lastIndexOf(' ', Math.min(room, text.length() - 1));
            return space > room / 2 ? space : Math.min(room, text.length());
        }
    }

    /** Who owns each core, including ones that are gone, found again through the history that placed them. */
    static Map<CoreId, OrgId> coreOwners(CharterState state) {
        Map<CoreId, OrgId> out = new HashMap<>();
        for (History.Entry entry : state.history.entries()) {
            if (entry.event() instanceof LandEvents.NexusPlaced e) out.put(e.core(), e.org());
            else if (entry.event() instanceof LandEvents.CampPlanted e) out.put(e.core(), e.org());
        }
        state.cores().forEach(c -> out.put(c.id, c.owner));
        return out;
    }

    /** Whether an event names the organization, directly, in a set or party, or through one of its cores. */
    static boolean involves(Object event, OrgId org, Map<CoreId, OrgId> owners) {
        if (!event.getClass().isRecord()) return false;
        for (RecordComponent c : event.getClass().getRecordComponents()) {
            Object value;
            try {
                c.getAccessor().setAccessible(true);
                value = c.getAccessor().invoke(event);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;
            }
            if (org.equals(value)) return true;
            if (value instanceof Party p && org.equals(p.org())) return true;
            if (value instanceof CoreId core && org.equals(owners.get(core))) return true;
            if (value instanceof Collection<?> items && items.contains(org)) return true;
        }
        return false;
    }
}
