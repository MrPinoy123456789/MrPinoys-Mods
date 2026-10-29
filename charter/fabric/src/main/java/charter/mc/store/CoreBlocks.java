package charter.mc.store;

import charter.engine.Action;
import charter.gov.Governance;
import charter.gov.Office;
import charter.gov.Preset;
import charter.gov.Vote;
import charter.gov.VoteMethod;
import charter.grant.Grant;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.id.ClaimId;
import charter.id.CoreId;
import charter.id.GrantId;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.land.CampPhase;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.land.CoreKind;
import charter.id.ChunkKey;
import charter.org.Invite;
import charter.org.MemberRecord;
import charter.org.Organization;
import charter.org.OrgStatus;
import charter.org.People;
import charter.org.PlayerRecord;
import charter.siege.SiegeBook;
import charter.siege.Store;
import charter.food.Provisions;
import charter.state.CharterState;
import charter.treasury.Currency;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The blocks of M1 to M5: clocks and ids, players, organizations with their
 * members and invites, offices, grants, votes, land, and the siege book.
 *
 * <p>Records are written field by field rather than by reflection over the core
 * classes, because their invariants live in constructors and in CharterState's
 * adders (the chunk index of claims, the core order counter, the tree version).
 * Reading puts each record back through those same doors.
 */
public final class CoreBlocks {

    private CoreBlocks() {
    }

    private static final Type BALLOTS = new TypeToken<Map<PlayerId, Vote.Ballot>>() { }.getType();
    private static final Type PLAYER_SET = new TypeToken<Set<PlayerId>>() { }.getType();
    private static final Type ORG_SET = new TypeToken<Set<OrgId>>() { }.getType();
    private static final Type METHODS = new TypeToken<Map<Power, VoteMethod>>() { }.getType();
    private static final Type BALANCES = new TypeToken<Map<Currency, Long>>() { }.getType();
    private static final Type ITEMS = new TypeToken<Map<String, Integer>>() { }.getType();
    private static final Type DEBTS = new TypeToken<Map<String, Double>>() { }.getType();
    private static final Type KEEPS = new TypeToken<Map<CoreId, SiegeBook.KeepSite>>() { }.getType();
    private static final Type TRUCES = new TypeToken<List<SiegeBook.Truce>>() { }.getType();

    static void register(StateCodec codec) {
        codec.register(block("clocks", CoreBlocks::writeClocks, CoreBlocks::readClocks))
                .register(block("players", CoreBlocks::writePlayers, CoreBlocks::readPlayers))
                .register(block("orgs", CoreBlocks::writeOrgs, CoreBlocks::readOrgs))
                .register(block("offices", CoreBlocks::writeOffices, CoreBlocks::readOffices))
                .register(block("grants", CoreBlocks::writeGrants, CoreBlocks::readGrants))
                .register(block("votes", CoreBlocks::writeVotes, CoreBlocks::readVotes))
                .register(block("land", CoreBlocks::writeLand, CoreBlocks::readLand))
                .register(block("siege", CoreBlocks::writeSiege, CoreBlocks::readSiege));
    }

    public interface Writer {
        JsonElement write(CharterState state, Gson gson);
    }

    public interface Reader {
        void read(JsonObject json, CharterState state, Gson gson, Repairs repairs);
    }

    /** A block from a write and a read function. Later milestones can build theirs the same way. */
    public static StateBlock block(String key, Writer writer, Reader reader) {
        return new StateBlock() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public JsonElement write(CharterState state, Gson gson) {
                return writer.write(state, gson);
            }

            @Override
            public void read(JsonElement json, CharterState state, Gson gson, Repairs repairs) {
                reader.read(json.getAsJsonObject(), state, gson, repairs);
            }
        };
    }

    // ---- small helpers ----

    private static <T> T get(JsonObject o, String key, Class<T> type, Gson gson) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : gson.fromJson(e, type);
    }

    private static <T> T get(JsonObject o, String key, Type type, Gson gson) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : gson.fromJson(e, type);
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static double dbl(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonArray() ? new JsonArray() : e.getAsJsonArray();
    }

    // ---- clocks and ids ----

    private static JsonElement writeClocks(CharterState state, Gson gson) {
        JsonObject o = new JsonObject();
        o.addProperty("nextId", state.ids.peek());
        o.addProperty("siegeClock", state.siegeClock.now());
        return o;
    }

    private static void readClocks(JsonObject o, CharterState state, Gson gson, Repairs repairs) {
        state.ids.restore(num(o, "nextId"));
        state.siegeClock.restore(num(o, "siegeClock"));
    }

    // ---- players ----

    private static JsonElement writePlayers(CharterState state, Gson gson) {
        JsonArray players = new JsonArray();
        for (PlayerRecord p : state.allPlayers()) {
            JsonObject o = new JsonObject();
            o.add("id", gson.toJsonTree(p.id));
            o.addProperty("name", p.name);
            o.addProperty("lastSeen", p.lastSeen);
            o.addProperty("lastFoundedAt", p.lastFoundedAt);
            players.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("players", players);
        return root;
    }

    private static void readPlayers(JsonObject root, CharterState state, Gson gson, Repairs repairs) {
        for (JsonElement e : array(root, "players")) {
            JsonObject o = e.getAsJsonObject();
            PlayerRecord p = state.player(get(o, "id", PlayerId.class, gson));
            p.name = str(o, "name");
            p.lastSeen = num(o, "lastSeen");
            p.lastFoundedAt = num(o, "lastFoundedAt");
        }
    }

    // ---- organizations, members, invites ----

    private static JsonElement writeOrgs(CharterState state, Gson gson) {
        JsonArray orgs = new JsonArray();
        for (Organization org : state.allOrgs()) {
            JsonObject o = new JsonObject();
            o.add("id", gson.toJsonTree(org.id));
            o.addProperty("name", org.name());
            o.addProperty("description", org.description());
            if (org.parent() != null) o.add("parent", gson.toJsonTree(org.parent()));
            o.addProperty("status", org.status().name());
            o.addProperty("foundedAt", org.foundedAt);
            if (org.npc) o.addProperty("npc", true);

            JsonObject gov = new JsonObject();
            gov.addProperty("preset", org.governance.preset.name());
            gov.addProperty("defaultMethod", org.governance.defaultMethod.name());
            gov.addProperty("quorumShare", org.governance.quorumShare);
            if (org.governance.deciders != null) gov.add("deciders", gson.toJsonTree(org.governance.deciders));
            gov.add("methods", gson.toJsonTree(org.governance.methods(), METHODS));
            o.add("governance", gov);

            o.add("ledger", gson.toJsonTree(org.ledger.snapshot(), BALANCES));
            o.add("people", gson.toJsonTree(org.people));
            orgs.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("orgs", orgs);
        root.add("memberships", gson.toJsonTree(List.copyOf(state.allMemberships())));
        root.add("invites", gson.toJsonTree(state.invites()));
        return root;
    }

    private static void readOrgs(JsonObject root, CharterState state, Gson gson, Repairs repairs) {
        Map<OrgId, OrgId> parents = new LinkedHashMap<>();
        for (JsonElement e : array(root, "orgs")) {
            JsonObject o = e.getAsJsonObject();
            OrgId id = get(o, "id", OrgId.class, gson);
            JsonObject g = o.getAsJsonObject("governance");
            Governance gov = new Governance(Preset.valueOf(str(g, "preset")));
            gov.defaultMethod = VoteMethod.valueOf(str(g, "defaultMethod"));
            gov.quorumShare = dbl(g, "quorumShare");
            gov.deciders = get(g, "deciders", OfficeId.class, gson);
            Map<Power, VoteMethod> methods = get(g, "methods", METHODS, gson);
            if (methods != null) methods.forEach(gov::setMethod);

            // Added at the top first: CharterState refuses a parent it has not seen yet.
            Organization org = new Organization(id, str(o, "name"), null, gov,
                    OrgStatus.valueOf(str(o, "status")), num(o, "foundedAt"));
            org.describe(str(o, "description"));
            org.npc = bool(o, "npc");
            Map<Currency, Long> balances = get(o, "ledger", BALANCES, gson);
            if (balances != null) balances.forEach(org.ledger::add);
            People people = get(o, "people", People.class, gson);
            if (people != null) copyPeople(people, org.people);
            state.addOrg(org);
            OrgId parent = get(o, "parent", OrgId.class, gson);
            if (parent != null) parents.put(id, parent);
        }
        for (Map.Entry<OrgId, OrgId> e : parents.entrySet()) {
            if (state.orgIncludingDissolved(e.getValue()).isPresent()) {
                state.orgIncludingDissolved(e.getKey()).orElseThrow().setParent(e.getValue());
            } else {
                repairs.note("org_reparented", e.getKey() + " had parent " + e.getValue() + ", which is gone; moved to the top level");
            }
        }
        for (Organization org : List.copyOf(state.allOrgs())) {
            if (inCycle(state, org.id)) {
                org.setParent(null);
                repairs.note("org_reparented", org.id + " was in a cycle of parents; moved to the top level");
            }
        }

        for (MemberRecord m : gson.<List<MemberRecord>>fromJson(array(root, "memberships"), new TypeToken<List<MemberRecord>>() { }.getType())) {
            if (state.orgIncludingDissolved(m.org()).isEmpty()) {
                repairs.note("membership_dropped", m.player() + " was a member of " + m.org() + ", which is gone");
                continue;
            }
            state.restoreMembership(m);
        }
        for (Invite i : gson.<List<Invite>>fromJson(array(root, "invites"), new TypeToken<List<Invite>>() { }.getType())) {
            if (state.org(i.org()).isEmpty()) {
                repairs.note("invite_dropped", "an invite of " + i.player() + " to " + i.org() + ", which is gone");
                continue;
            }
            state.addInvite(i);
        }
    }

    private static boolean inCycle(CharterState state, OrgId start) {
        Set<OrgId> seen = new HashSet<>();
        OrgId current = start;
        while (current != null) {
            if (!seen.add(current)) return true;
            current = state.orgIncludingDissolved(current).map(Organization::parent).orElse(null);
        }
        return false;
    }

    private static void copyPeople(People from, People to) {
        to.name = from.name;
        to.originStory = from.originStory;
        to.description = from.description;
        to.traditions = from.traditions;
        to.beliefs = from.beliefs;
        to.banner = from.banner;
        to.revision = from.revision;
        to.derivedFrom = from.derivedFrom;
    }

    // ---- offices ----

    private static JsonElement writeOffices(CharterState state, Gson gson) {
        JsonArray offices = new JsonArray();
        for (Office office : state.allOffices()) {
            JsonObject o = new JsonObject();
            o.add("id", gson.toJsonTree(office.id));
            o.add("org", gson.toJsonTree(office.org));
            o.addProperty("name", office.name);
            o.addProperty("reserved", office.reserved);
            o.addProperty("vacateWhenAbsent", office.vacateWhenAbsent);
            o.addProperty("seats", office.seats());
            o.addProperty("deciding", office.deciding().name());
            JsonArray holders = new JsonArray();
            for (Office.Seat seat : office.holders()) {
                JsonObject h = new JsonObject();
                h.add("player", gson.toJsonTree(seat.player()));
                h.addProperty("since", seat.since());
                holders.add(h);
            }
            o.add("holders", holders);
            offices.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("offices", offices);
        return root;
    }

    private static void readOffices(JsonObject root, CharterState state, Gson gson, Repairs repairs) {
        for (JsonElement e : array(root, "offices")) {
            JsonObject o = e.getAsJsonObject();
            OfficeId id = get(o, "id", OfficeId.class, gson);
            OrgId org = get(o, "org", OrgId.class, gson);
            if (state.orgIncludingDissolved(org).isEmpty()) {
                repairs.note("office_dropped", "office " + str(o, "name") + " (" + id + ") of " + org + ", which is gone");
                continue;
            }
            // Before seats an office had one holder and no "seats": it reads as a one seat office.
            Office office = new Office(id, org, str(o, "name"), bool(o, "reserved"), bool(o, "vacateWhenAbsent"),
                    o.has("seats") ? (int) num(o, "seats") : 1);
            if (o.has("deciding")) office.setDeciding(Office.Deciding.valueOf(str(o, "deciding")));
            if (o.has("holders")) {
                for (JsonElement h : o.getAsJsonArray("holders")) {
                    JsonObject seat = h.getAsJsonObject();
                    PlayerId player = get(seat, "player", PlayerId.class, gson);
                    if (player != null) office.seat(player, num(seat, "since"));
                }
            } else {
                PlayerId holder = get(o, "holder", PlayerId.class, gson);
                if (holder != null) office.seat(holder, num(o, "heldSince"));
            }
            state.addOffice(office);
        }
    }

    // ---- grants ----

    private static JsonElement writeGrants(CharterState state, Gson gson) {
        JsonArray grants = new JsonArray();
        for (Grant g : state.grants()) {
            JsonObject o = new JsonObject();
            o.add("id", gson.toJsonTree(g.id));
            o.add("subject", gson.toJsonTree(g.subject));
            o.addProperty("power", g.power.name());
            o.add("recipient", gson.toJsonTree(g.recipient, Holder.class));
            o.add("grantor", gson.toJsonTree(g.grantor, Holder.class));
            if (g.parent != null) o.add("parent", gson.toJsonTree(g.parent));
            o.add("scope", gson.toJsonTree(g.scope));
            o.addProperty("redelegable", g.redelegable);
            o.addProperty("createdAt", g.createdAt);
            o.addProperty("expiresAt", g.expiresAt);
            o.addProperty("revoked", g.revoked());
            o.addProperty("revokedAt", g.revokedAt());
            grants.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("grants", grants);
        return root;
    }

    private static void readGrants(JsonObject root, CharterState state, Gson gson, Repairs repairs) {
        Map<GrantId, Grant> read = new LinkedHashMap<>();
        for (JsonElement e : array(root, "grants")) {
            JsonObject o = e.getAsJsonObject();
            // A power this version no longer has (CHOOSE_FALL, removed with the falls): the grant goes.
            if (powerOrNull(str(o, "power")) == null) {
                repairs.note("grant_dropped", "a grant of " + str(o, "power") + ", a power that no longer exists");
                continue;
            }
            Grant g = new Grant(get(o, "id", GrantId.class, gson), get(o, "subject", OrgId.class, gson),
                    Power.valueOf(str(o, "power")), get(o, "recipient", Holder.class, gson), get(o, "grantor", Holder.class, gson),
                    get(o, "parent", GrantId.class, gson), get(o, "scope", GrantScope.class, gson),
                    bool(o, "redelegable"), num(o, "createdAt"), num(o, "expiresAt"));
            if (bool(o, "revoked")) g.revoke(num(o, "revokedAt"));
            read.put(g.id, g);
        }
        // A grant whose source is gone goes, and so does every grant derived from it.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Grant g : List.copyOf(read.values())) {
                String why = brokenGrant(state, g, read.keySet());
                if (why != null) {
                    read.remove(g.id);
                    repairs.note("grant_dropped", g.id + " (" + g.power + " over " + g.subject + "): " + why);
                    changed = true;
                }
            }
        }
        read.values().forEach(state::addGrant);
    }

    private static Power powerOrNull(String name) {
        try {
            return Power.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Why a grant cannot stand, or null if its subject, holders and parent all still exist. */
    static String brokenGrant(CharterState state, Grant g, Set<GrantId> grants) {
        if (g.subject == null || state.orgIncludingDissolved(g.subject).isEmpty()) return "its organization is gone";
        if (g.scope == null) return "it has no scope";
        if (g.parent != null && !grants.contains(g.parent)) return "the grant it came from is gone";
        if (!holderExists(state, g.recipient)) return "its recipient is gone";
        if (!holderExists(state, g.grantor)) return "its grantor is gone";
        return null;
    }

    private static boolean holderExists(CharterState state, Holder holder) {
        return switch (holder) {
            case null -> false;
            case Holder.OfPlayer p -> true;
            case Holder.OfOffice o -> state.allOffices().stream().anyMatch(x -> x.id.equals(o.office()));
            case Holder.OfOrg o -> state.orgIncludingDissolved(o.org()).isPresent();
        };
    }

    // ---- votes ----

    private static JsonElement writeVotes(CharterState state, Gson gson) {
        JsonArray votes = new JsonArray();
        for (Vote v : state.votes()) {
            JsonObject o = new JsonObject();
            o.add("id", gson.toJsonTree(v.id));
            o.add("org", gson.toJsonTree(v.org));
            o.addProperty("kind", v.kind.name());
            if (v.motion != null) o.add("motion", gson.toJsonTree(v.motion, Action.class));
            if (v.office != null) o.add("office", gson.toJsonTree(v.office));
            if (v.seatsOf != null) o.add("seatsOf", gson.toJsonTree(v.seatsOf));
            if (v.restricted()) o.addProperty("restricted", true);
            if (v.proposer != null) o.add("proposer", gson.toJsonTree(v.proposer));
            if (v.power != null) o.addProperty("power", v.power.name());
            o.addProperty("method", v.method.name());
            o.addProperty("quorumShare", v.quorumShare);
            o.add("electorate", gson.toJsonTree(v.electorate, PLAYER_SET));
            o.add("activeAtOpen", gson.toJsonTree(v.activeAtOpen, PLAYER_SET));
            o.addProperty("openedAt", v.openedAt);
            o.addProperty("closesAt", v.closesAt);
            o.add("ballots", gson.toJsonTree(v.ballots(), BALLOTS));
            o.addProperty("state", v.state().name());
            o.addProperty("result", v.result());
            votes.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("votes", votes);
        return root;
    }

    private static void readVotes(JsonObject root, CharterState state, Gson gson, Repairs repairs) {
        for (JsonElement e : array(root, "votes")) {
            JsonObject o = e.getAsJsonObject();
            VoteId id = get(o, "id", VoteId.class, gson);
            OrgId org = get(o, "org", OrgId.class, gson);
            Vote.Kind kind = Vote.Kind.valueOf(str(o, "kind"));
            Action motion = null;
            String unreadable = null;
            if (o.has("motion")) {
                try {
                    motion = gson.fromJson(o.get("motion"), Action.class);
                } catch (CharterJson.UnknownType unknown) {
                    unreadable = unknown.tag;
                }
            }
            String power = str(o, "power");
            Vote vote = new Vote(id, org, kind, motion, get(o, "office", OfficeId.class, gson),
                    get(o, "proposer", PlayerId.class, gson), power.isEmpty() ? null : powerOrNull(power),
                    VoteMethod.valueOf(str(o, "method")), dbl(o, "quorumShare"),
                    orEmpty(get(o, "electorate", PLAYER_SET, gson)), orEmpty(get(o, "activeAtOpen", PLAYER_SET, gson)),
                    num(o, "openedAt"), num(o, "closesAt"), get(o, "seatsOf", OfficeId.class, gson));
            if (bool(o, "restricted")) vote.restrict();
            Map<PlayerId, Vote.Ballot> ballots = get(o, "ballots", BALLOTS, gson);
            if (ballots != null) ballots.forEach(vote::cast);
            vote.settle(Vote.State.valueOf(str(o, "state")), str(o, "result"));
            if (unreadable != null && vote.open()) {
                vote.settle(Vote.State.FAILED, "motion unknown");
                repairs.note("vote_closed", id + " held a motion of type " + unreadable + ", which this jar does not know");
            }
            state.addVote(vote);
        }
    }

    private static <T> Set<T> orEmpty(Set<T> set) {
        return set == null ? Set.of() : set;
    }

    // ---- land ----

    private static JsonElement writeLand(CharterState state, Gson gson) {
        JsonArray cores = new JsonArray();
        for (Core c : state.cores()) {
            JsonObject o = new JsonObject();
            o.add("id", gson.toJsonTree(c.id));
            o.addProperty("kind", c.kind.name());
            o.add("owner", gson.toJsonTree(c.owner));
            o.add("center", gson.toJsonTree(c.center));
            o.addProperty("order", c.order);
            o.addProperty("placedAt", c.placedAt);
            if (c.phase() != null) o.addProperty("phase", c.phase().name());
            o.addProperty("phaseSince", c.phaseSince());
            if (c.deposit > 0) o.addProperty("deposit", c.deposit);
            cores.add(o);
        }
        JsonArray claims = new JsonArray();
        for (Claim c : state.claims()) {
            JsonObject o = new JsonObject();
            o.add("id", gson.toJsonTree(c.id));
            o.add("chunk", gson.toJsonTree(c.chunk));
            o.add("owner", gson.toJsonTree(c.owner));
            o.addProperty("kind", c.kind().name());
            if (c.core() != null) o.add("core", gson.toJsonTree(c.core()));
            o.addProperty("hops", c.hops());
            o.addProperty("claimedAt", c.claimedAt);
            o.addProperty("contestedUntil", c.contestedUntil());
            claims.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("nextCoreOrder", state.nextCoreOrder());
        root.add("cores", cores);
        root.add("claims", claims);
        return root;
    }

    private static void readLand(JsonObject root, CharterState state, Gson gson, Repairs repairs) {
        for (JsonElement e : array(root, "cores")) {
            JsonObject o = e.getAsJsonObject();
            CoreId id = get(o, "id", CoreId.class, gson);
            OrgId owner = get(o, "owner", OrgId.class, gson);
            if (state.orgIncludingDissolved(owner).isEmpty()) {
                repairs.note("core_dropped", id + " belonged to " + owner + ", which is gone");
                continue;
            }
            String phase = str(o, "phase");
            Core read = new Core(id, CoreKind.valueOf(str(o, "kind")), owner, get(o, "center", ChunkKey.class, gson),
                    num(o, "order"), num(o, "placedAt"), phase.isEmpty() ? null : CampPhase.valueOf(phase), num(o, "phaseSince"));
            read.deposit = o.has("deposit") ? num(o, "deposit") : 0;
            state.addCore(read);
        }
        for (JsonElement e : array(root, "claims")) {
            JsonObject o = e.getAsJsonObject();
            ClaimId id = get(o, "id", ClaimId.class, gson);
            ChunkKey chunk = get(o, "chunk", ChunkKey.class, gson);
            OrgId owner = get(o, "owner", OrgId.class, gson);
            if (state.orgIncludingDissolved(owner).isEmpty()) {
                repairs.note("claim_dropped", "claim of " + chunk + ": its owner " + owner + " is gone");
                continue;
            }
            if (state.claimAt(chunk).isPresent()) {
                repairs.note("claim_dropped", "a second claim of " + chunk + " (" + id + ")");
                continue;
            }
            Claim claim = new Claim(id, chunk, owner, ClaimKind.valueOf(str(o, "kind")), get(o, "core", CoreId.class, gson),
                    (int) num(o, "hops"), num(o, "claimedAt"));
            claim.restoreContested(num(o, "contestedUntil"));
            state.addClaim(claim);
        }
        state.restoreCoreOrder(num(root, "nextCoreOrder"));
    }

    // ---- the siege book ----

    private static JsonElement writeSiege(CharterState state, Gson gson) {
        SiegeBook book = state.siege;
        JsonObject stores = new JsonObject();
        for (Map.Entry<CoreId, Store> e : book.stores.entrySet()) {
            JsonObject s = new JsonObject();
            s.add("provisions", gson.toJsonTree(e.getValue().provisions, Provisions.class));
            s.addProperty("eatDebt", e.getValue().eatDebt);
            s.add("spoilDebts", gson.toJsonTree(e.getValue().spoilDebts, DEBTS));
            stores.add(e.getKey().value().toString(), s);
        }
        JsonObject root = new JsonObject();
        root.add("stores", stores);
        root.add("keeps", gson.toJsonTree(book.keeps, KEEPS));
        root.add("truces", gson.toJsonTree(book.truces, TRUCES));
        return root;
    }

    private static void readSiege(JsonObject root, CharterState state, Gson gson, Repairs repairs) {
        SiegeBook book = state.siege;
        JsonObject stores = root.has("stores") ? root.getAsJsonObject("stores") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : stores.entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject();
            Store store = book.store(gson.fromJson(new com.google.gson.JsonPrimitive(e.getKey()), CoreId.class));
            Provisions provisions = get(s, "provisions", Provisions.class, gson);
            store.provisions = provisions == null ? new Provisions() : provisions;
            store.eatDebt = dbl(s, "eatDebt");
            Map<String, Double> debts = get(s, "spoilDebts", DEBTS, gson);
            if (debts != null) store.spoilDebts.putAll(new TreeMap<>(debts));
        }
        putAll(book.keeps, get(root, "keeps", KEEPS, gson));
        List<SiegeBook.Truce> truces = get(root, "truces", TRUCES, gson);
        if (truces != null) book.truces.addAll(truces);
        // A save from before protected keeps may still carry falls, occupations, holds, notoriety and
        // waived protection. They are not read: the keeps they describe are back with their owners.
    }

    private static <K, V> void putAll(Map<K, V> into, Map<K, V> from) {
        if (from != null) into.putAll(from);
    }
}
