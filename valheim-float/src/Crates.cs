using System;
using System.Collections.Generic;
using UnityEngine;

namespace MrPinoys.Float
{
    /// <summary>What the server remembers about a crate it made, for the trace only.</summary>
    internal sealed class CrateState
    {
        public int InUse;
        public long Owner;
        public float CreatedAt;
        public bool OwnerSeen;
    }

    /// <summary>
    /// The crate prefab's facts (read off the prefab once), creating crate ZDOs the way
    /// ZNetView.Awake plus Container.Awake would on a client, and the bookkeeping that
    /// tells our crates apart from the ones a wrecked ship leaves behind.
    /// </summary>
    internal static class Crates
    {
        /// <summary>Stamped on every crate we create so merges and the trace only touch ours.</summary>
        internal static readonly int OurKey = "mrpinoys_float".GetStableHashCode();

        internal static bool Ready;
        internal static int PrefabHash;
        internal static int Width;
        internal static int Height;
        internal static int Slots => Width * Height;
        private static bool _persistent;
        private static bool _distant;
        private static ZDO.ObjectType _type;

        internal static int CratesCreated;
        internal static int ItemsConverted;
        internal static int ItemsMerged;

        private static readonly Dictionary<ZDOID, CrateState> Tracked = new Dictionary<ZDOID, CrateState>();
        private static readonly List<ZDOID> Gone = new List<ZDOID>();
        private static readonly List<ZDO> Near = new List<ZDO>();
        private static bool _initFailed;

        /// <summary>
        /// Verify the crate prefab once ObjectDB and ZNetScene are up. Returns false while
        /// they are not; after a failure it stays false and the plugin does nothing.
        /// </summary>
        internal static bool TryInit()
        {
            if (Ready || _initFailed) return Ready;
            if (ZNetScene.instance == null || ObjectDB.instance == null) return false;
            if (ObjectDB.instance.m_items == null || ObjectDB.instance.m_items.Count == 0) return false;

            string name = (FloatPlugin.CratePrefab.Value ?? "").Trim();
            var prefab = name.Length > 0 ? ZNetScene.instance.GetPrefab(name) : null;
            if (prefab == null)
            {
                _initFailed = true;
                FloatPlugin.Log.LogError($"Crate prefab '{name}' not found in ZNetScene. Set General.CratePrefab to one of these (prefabs with a Container and a Floating component):");
                int shown = 0;
                foreach (var go in ZNetScene.instance.m_prefabs)
                {
                    if (go == null) continue;
                    var c = go.GetComponentInChildren<Container>(true);
                    var f = go.GetComponentInChildren<Floating>(true);
                    if (c == null || f == null) continue;
                    var nv = go.GetComponent<ZNetView>();
                    FloatPlugin.Log.LogError($"  candidate {go.name}: Container {c.m_width}x{c.m_height}, autoDestroyEmpty {(c.m_autoDestroyEmpty ? "yes" : "no")}, persistent {(nv != null && nv.m_persistent ? "yes" : "no")}, container on root {(go.GetComponent<Container>() != null ? "yes" : "no")}");
                    shown++;
                }
                if (shown == 0) FloatPlugin.Log.LogError("  none found; the plugin is disabled until the config names a valid prefab.");
                return false;
            }

            var container = prefab.GetComponent<Container>();
            bool containerOnRoot = container != null;
            if (container == null) container = prefab.GetComponentInChildren<Container>(true);
            var floating = prefab.GetComponent<Floating>();
            var nview = prefab.GetComponent<ZNetView>();
            var body = prefab.GetComponent<Rigidbody>();
            if (container == null || nview == null)
            {
                _initFailed = true;
                FloatPlugin.Log.LogError($"Crate prefab {prefab.name} has {(container == null ? "no Container" : "a Container")} and {(nview == null ? "no ZNetView" : "a ZNetView")}; it cannot hold items as a synced object. Pick another prefab.");
                return false;
            }

            PrefabHash = prefab.name.GetStableHashCode();
            Width = Mathf.Max(1, container.m_width);
            Height = Mathf.Max(1, container.m_height);
            _distant = nview.m_distant;
            _type = nview.m_type;
            // ReleaseNearbyZDOS only ever hands out Persistent ZDOs, so a non-persistent crate
            // would never get an owner and never float or open. Force it and say so.
            _persistent = true;

            FloatPlugin.Log.LogInfo(
                $"Crate prefab {prefab.name}: Container {Width}x{Height}, Floating {(floating != null ? "yes" : "no")}, " +
                $"autoDestroyEmpty {(container.m_autoDestroyEmpty ? "yes" : "no")}, persistent {(nview.m_persistent ? "yes" : "no (forced on for our crates)")}, " +
                $"distant {(_distant ? "yes" : "no")}, type {_type}, rigidbody {(body != null ? "yes" : "no")}" +
                (containerOnRoot ? "" : ", container on a child object (rootObjectOverride expected)"));
            if (floating == null) FloatPlugin.Log.LogWarning($"Crate prefab {prefab.name} has no Floating component; crates will sink like the items did. Check General.CratePrefab.");
            if (!container.m_autoDestroyEmpty) FloatPlugin.Log.LogWarning($"Crate prefab {prefab.name} does not destroy itself when emptied; empty crates will stay in the water.");
            if (!containerOnRoot && container.m_rootObjectOverride == null) FloatPlugin.Log.LogWarning($"Crate prefab {prefab.name}: the Container sits on a child without m_rootObjectOverride, so it may read a different ZDO than the one we fill.");

            Ready = true;
            return true;
        }

        /// <summary>Is this ZDO a crate we created (the prefab and our stamp)?</summary>
        internal static bool IsOurs(ZDO zdo)
        {
            return zdo != null && zdo.GetPrefab() == PrefabHash && zdo.GetInt(OurKey, 0) == 1;
        }

        /// <summary>
        /// The nearest crate of ours within the merge radius that nobody owns, nobody has
        /// open and that still has a free slot. Owned crates are skipped on purpose: the
        /// owning client bumps the crate's data revision every frame it bobs, so a server
        /// write would lose the revision race and the item with it (NOTES.md finding 6).
        /// </summary>
        internal static ZDO FindMergeTarget(Vector3 pos, ItemRecord record, out List<ItemRecord> contents, out string why)
        {
            contents = null;
            why = "no crate of ours nearby";
            float radius = Mathf.Max(0f, FloatPlugin.MergeRadiusMeters.Value);
            if (radius <= 0f) { why = "merging disabled"; return null; }

            Near.Clear();
            ZDOMan.instance.FindSectorObjects(ZoneSystem.GetZone(pos), new SimulationDistance(1, 0, classic: true), Near);
            ZDO best = null;
            float bestDistance = float.MaxValue;
            var reasons = new List<string>();
            foreach (var zdo in Near)
            {
                if (!IsOurs(zdo)) continue;
                float d = ServerSide.Flat(zdo.GetPosition(), pos);
                if (d > radius) continue;
                if (zdo.GetInt(ZDOVars.s_inUse, 0) != 0) { reasons.Add($"{zdo.m_uid} is open"); continue; }
                if (zdo.HasOwner()) { reasons.Add($"{zdo.m_uid} is owned by {ServerSide.PeerName(zdo.GetOwner())}"); continue; }
                if (d < bestDistance)
                {
                    best = zdo;
                    bestDistance = d;
                }
            }
            if (best == null)
            {
                if (reasons.Count > 0) why = string.Join("; ", reasons);
                return null;
            }
            byte[] blob = best.GetByteArray(ZDOVars.s_items);
            try
            {
                contents = blob != null && blob.Length > 0 ? ItemCodec.Parse(blob, out _) : new List<ItemRecord>();
            }
            catch (Exception e)
            {
                why = $"{best.m_uid} has an items blob we cannot parse ({e.Message})";
                return null;
            }
            if (!CanFit(contents, record))
            {
                why = $"{best.m_uid} is full ({contents.Count}/{Slots} slots, no stack of {Items.PrefabName(record.PrefabHash)} with room)";
                contents = null;
                return null;
            }
            why = $"{best.m_uid} at {ServerSide.Fmt(best.GetPosition())}, {bestDistance:0.#} m away, {contents.Count}/{Slots} slots used";
            return best;
        }

        private static readonly Dictionary<int, int> MaxStackByHash = new Dictionary<int, int>();

        /// <summary>Max stack size off the item prefab's SharedData; 1 when unknown.</summary>
        internal static int MaxStack(int hash)
        {
            if (MaxStackByHash.TryGetValue(hash, out int max)) return max;
            var go = ObjectDB.instance?.GetItemPrefab(hash);
            var drop = go != null ? go.GetComponent<ItemDrop>() : null;
            max = drop != null && drop.m_itemData?.m_shared != null ? Mathf.Max(1, drop.m_itemData.m_shared.m_maxStackSize) : 1;
            MaxStackByHash[hash] = max;
            return max;
        }

        /// <summary>
        /// Same stack as far as vanilla is concerned (Inventory.AddItem merges by
        /// ItemData.IsSameType: name, world level, quality), narrowed to also require the
        /// same variant, cheated flag and custom data so nothing is blurred.
        /// </summary>
        private static bool SameStack(ItemRecord a, ItemRecord b)
        {
            return a.PrefabHash == b.PrefabHash && a.Quality == b.Quality && a.WorldLevel == b.WorldLevel
                && a.Variant == b.Variant && a.Cheated == b.Cheated && a.CustomDataKey() == b.CustomDataKey();
        }

        /// <summary>How much of the record can go into existing stacks of the same item.</summary>
        private static int StackRoom(List<ItemRecord> contents, ItemRecord record)
        {
            int max = MaxStack(record.PrefabHash);
            int room = 0;
            foreach (var r in contents)
            {
                if (SameStack(r, record)) room += Mathf.Max(0, max - r.Stack);
            }
            return room;
        }

        internal static bool CanFit(List<ItemRecord> contents, ItemRecord record)
        {
            return contents.Count < Slots || StackRoom(contents, record) >= record.Stack;
        }

        /// <summary>
        /// Top up existing stacks of the same item first, then put what is left in the
        /// first free grid cell, row by row. The caller checked CanFit.
        /// </summary>
        internal static void Place(List<ItemRecord> contents, ItemRecord record)
        {
            int max = MaxStack(record.PrefabHash);
            foreach (var r in contents)
            {
                if (record.Stack <= 0) break;
                if (!SameStack(r, record) || r.Stack >= max) continue;
                int take = Mathf.Min(max - r.Stack, record.Stack);
                r.Stack += take;
                record.Stack -= take;
            }
            if (record.Stack <= 0) return;

            var used = new HashSet<int>();
            foreach (var r in contents) used.Add(r.GridY * Width + r.GridX);
            for (int i = 0; i < Slots; i++)
            {
                if (used.Contains(i)) continue;
                record.GridX = i % Width;
                record.GridY = i / Width;
                contents.Add(record);
                return;
            }
            throw new InvalidOperationException("no free slot");
        }

        /// <summary>
        /// Create the crate ZDO with everything ZNetView.Awake and Container.Awake would
        /// have set on a client, then release ownership so a nearby peer picks it up.
        /// </summary>
        internal static ZDO Create(Vector3 surfacePos, List<ItemRecord> contents)
        {
            var zdo = ZDOMan.instance.CreateNewZDO(surfacePos, PrefabHash);
            // Flags first: every later Set bumps the revision and marks the sector dirty only when Persistent.
            zdo.Persistent = _persistent;
            zdo.Distant = _distant;
            zdo.Type = _type;
            // CreateNewZDO only uses the hash for the portal check; ZNetView.Awake sets the prefab itself.
            zdo.SetPrefab(PrefabHash);
            zdo.SetRotation(Quaternion.Euler(0f, UnityEngine.Random.Range(0f, 360f), 0f));
            // Container.Awake rolls the default drop table unless this is already set.
            zdo.Set(ZDOVars.s_addedDefaultItems, true);
            zdo.Set(ZDOVars.s_items, ItemCodec.Serialize(contents));
            zdo.Set(OurKey, 1);
            zdo.Set(FishRelease.NoFishKey, 1);
            zdo.SetOwner(0L);
            Tracked[zdo.m_uid] = new CrateState { InUse = 0, Owner = 0L, CreatedAt = Time.realtimeSinceStartup };
            CratesCreated++;
            return zdo;
        }

        /// <summary>
        /// Create a dropped-item ZDO the way ZNetView.Awake plus ItemDrop.Start would:
        /// prefab, flags, rotation and the one-record "itemData" blob. Used to put fish
        /// back in the water. Returns null when the prefab has no ZNetView.
        /// </summary>
        internal static ZDO CreateItem(Vector3 pos, ItemRecord record)
        {
            var prefab = ZNetScene.instance?.GetPrefab(record.PrefabHash);
            var nview = prefab != null ? prefab.GetComponent<ZNetView>() : null;
            if (nview == null) return null;
            var zdo = ZDOMan.instance.CreateNewZDO(pos, record.PrefabHash);
            zdo.Persistent = nview.m_persistent;
            zdo.Distant = nview.m_distant;
            zdo.Type = nview.m_type;
            zdo.SetPrefab(record.PrefabHash);
            zdo.SetRotation(Quaternion.Euler(0f, UnityEngine.Random.Range(0f, 360f), 0f));
            var pkg = new ZPackage();
            pkg.Write((byte)109);
            var copy = record.Clone();
            copy.GridX = 0;
            copy.GridY = 0;
            copy.Equipped = false;
            ItemCodec.WriteCompact(pkg, copy);
            zdo.Set(ZDOVars.s_itemData, pkg.GetArray());
            zdo.Set(ZDOVars.s_spawnTime, ZNet.instance.GetTime().Ticks);
            zdo.SetOwner(0L);
            return zdo;
        }

        /// <summary>Rewrite an unowned crate's contents; the receiving client reloads it by revision.</summary>
        internal static void Rewrite(ZDO crate, List<ItemRecord> contents)
        {
            crate.Set(ZDOVars.s_items, ItemCodec.Serialize(contents));
            if (!Tracked.ContainsKey(crate.m_uid)) Tracked[crate.m_uid] = new CrateState { InUse = 0, Owner = crate.GetOwner(), CreatedAt = Time.realtimeSinceStartup, OwnerSeen = crate.HasOwner() };
        }

        internal static bool IsTracked(ZDOID id) => Tracked.ContainsKey(id);

        internal static CrateState State(ZDOID id)
        {
            Tracked.TryGetValue(id, out var s);
            return s;
        }

        /// <summary>Called by the ZDO.Deserialize postfix for every crate ZDO a client changed.</summary>
        internal static void OnClientChange(ZDO crate)
        {
            if (!Tracked.TryGetValue(crate.m_uid, out var s))
            {
                // A crate of ours from before a restart; start tracking it when a client first touches it.
                s = new CrateState { InUse = -1, Owner = 0L, CreatedAt = Time.realtimeSinceStartup };
                Tracked[crate.m_uid] = s;
            }
            long owner = crate.GetOwner();
            NoteOwner(crate, s, owner);
            int inUse = crate.GetInt(ZDOVars.s_inUse, 0);
            if (inUse == 1 && s.InUse != 1)
            {
                ServerSide.Trace($"Crate {crate.m_uid} opened by {ServerSide.PeerName(owner)}");
            }
            else if (inUse == 0 && s.InUse == 1)
            {
                int stacks = 0;
                try { var blob = crate.GetByteArray(ZDOVars.s_items); stacks = blob != null && blob.Length > 0 ? ItemCodec.Parse(blob, out _).Count : 0; } catch { }
                ServerSide.Trace($"Crate {crate.m_uid} closed by {ServerSide.PeerName(owner)}; {stacks} stack(s) left");
            }
            s.InUse = inUse;
        }

        private static void NoteOwner(ZDO crate, CrateState s, long owner)
        {
            if (owner != 0L && !s.OwnerSeen)
            {
                s.OwnerSeen = true;
                ServerSide.Trace($"Crate {crate.m_uid} now owned by {ServerSide.PeerName(owner)} ({Time.realtimeSinceStartup - s.CreatedAt:0.0} s after creation); the client runs its physics from here");
            }
            s.Owner = owner;
        }

        /// <summary>Every couple of seconds: notice crates that vanished (emptied, or destroyed) and crates that got an owner.</summary>
        internal static void Housekeeping()
        {
            if (ZDOMan.instance == null || Tracked.Count == 0) return;
            Gone.Clear();
            foreach (var kv in Tracked)
            {
                var zdo = ZDOMan.instance.GetZDO(kv.Key);
                if (zdo == null) { Gone.Add(kv.Key); continue; }
                NoteOwner(zdo, kv.Value, zdo.GetOwner());
                if (!kv.Value.OwnerSeen && Time.realtimeSinceStartup - kv.Value.CreatedAt > 15f && (ZNet.instance.GetPeers()?.Count ?? 0) > 0)
                {
                    // Warn once; ReleaseNearbyZDOS should have handed it to a nearby peer within 2 s.
                    kv.Value.OwnerSeen = true;
                    FloatPlugin.Log.LogWarning($"Crate {kv.Key} at {ServerSide.Fmt(zdo.GetPosition())} still has no owner 15 s after creation; is a player within the active area? (Persistent {zdo.Persistent}, sector {zdo.GetSector()})");
                }
            }
            foreach (var id in Gone)
            {
                Tracked.Remove(id);
                ServerSide.Trace($"Crate {id} gone (emptied or destroyed); forgotten");
            }
        }

        /// <summary>For the /point diagnostic: the nearest crate of ours to a point, tracked or not.</summary>
        internal static string Nearest(Vector3 pos)
        {
            if (!Ready || ZDOMan.instance == null) return "crate prefab not ready";
            Near.Clear();
            ZDOMan.instance.FindSectorObjects(ZoneSystem.GetZone(pos), new SimulationDistance(1, 0, classic: true), Near);
            ZDO best = null;
            float bestDistance = float.MaxValue;
            int count = 0;
            foreach (var zdo in Near)
            {
                if (!IsOurs(zdo)) continue;
                count++;
                float d = Vector3.Distance(zdo.GetPosition(), pos);
                if (d < bestDistance) { best = zdo; bestDistance = d; }
            }
            if (best == null) return "no crate of ours within the surrounding zones";
            int stacks = 0;
            try { var blob = best.GetByteArray(ZDOVars.s_items); stacks = blob != null && blob.Length > 0 ? ItemCodec.Parse(blob, out _).Count : 0; } catch { }
            return $"{count} crate(s) of ours in the surrounding zones; nearest {best.m_uid} at {ServerSide.Fmt(best.GetPosition())}, {bestDistance:0.#} m away, {stacks} stack(s), owner {ServerSide.PeerName(best.GetOwner())}, in use {best.GetInt(ZDOVars.s_inUse, 0)}";
        }
    }

    /// <summary>The swap itself: read the item, find or make a crate, then destroy the item.</summary>
    internal static class Converter
    {
        private static readonly HashSet<ZDOID> Refused = new HashSet<ZDOID>();

        internal static bool WasRefused(ZDOID id) => Refused.Contains(id);

        internal static void Convert(ZDOID id)
        {
            if (!FloatPlugin.Enabled.Value || !Crates.Ready) return;
            var item = ZDOMan.instance?.GetZDO(id);
            if (item == null)
            {
                ServerSide.Trace($"Item {id} is gone before conversion (picked up or despawned); nothing to do");
                return;
            }
            // Re-check right before acting: the item may have bounced back up or been fished out.
            if (!Items.IsSunkItem(item, out string skip))
            {
                ServerSide.Trace($"Item {id} ({Items.PrefabName(item.GetPrefab())}) at {ServerSide.Fmt(item.GetPosition())} is no longer a sunk item{(skip != null ? ": " + skip : "")}; left alone");
                return;
            }

            var record = Items.Read(item, out string source, out string error);
            if (record == null)
            {
                Refused.Add(id);
                FloatPlugin.Log.LogWarning($"REFUSED item {id} ({Items.PrefabName(item.GetPrefab())}) at {ServerSide.Fmt(item.GetPosition())}: {error}. Left on the seabed.");
                return;
            }

            var pos = item.GetPosition();
            float depth = Items.Depth(pos);
            long owner = item.GetOwner();
            string what = Items.Describe(record);

            var crate = Crates.FindMergeTarget(pos, record, out var contents, out string why);
            ServerSide.Trace($"Item {id} ({what}) at {ServerSide.Fmt(pos)} is {depth:0.0} m under water, owner {ServerSide.PeerName(owner)}, read from {source}; {(crate != null ? "merging into crate " + why : why)}");

            if (crate != null)
            {
                var before = ItemCodec.Counts(contents, out int totalBefore);
                before.TryGetValue(record.CountKey(), out int sameBefore);
                before[record.CountKey()] = sameBefore + record.Stack;
                int expectedTotal = totalBefore + record.Stack;
                Crates.Place(contents, record);
                byte[] blob = ItemCodec.Serialize(contents);
                // Re-read what is about to be written; refuse on any mismatch rather than lose an item:
                // the crate must hold exactly what it held plus this item, whatever the stacking did.
                var check = ItemCodec.Parse(blob, out _);
                var after = ItemCodec.Counts(check, out int totalAfter);
                if (check.Count != contents.Count || totalAfter != expectedTotal || !ItemCodec.SameCounts(before, after))
                {
                    Refused.Add(id);
                    FloatPlugin.Log.LogWarning($"REFUSED item {id}: the merged crate blob did not read back as old contents plus this item ({ItemCodec.Describe(before)} expected, {ItemCodec.Describe(after)} read). Nothing written.");
                    return;
                }
                Crates.Rewrite(crate, contents);
                Crates.ItemsMerged++;
                ServerSide.Trace($"Crate {crate.m_uid} now holds {contents.Count} stack(s) including {what}; revision {crate.DataRevision}, owner {ServerSide.PeerName(crate.GetOwner())}");
            }
            else
            {
                var single = new List<ItemRecord> { record };
                var check = ItemCodec.Parse(ItemCodec.Serialize(single), out _);
                if (check.Count != 1 || check[0].PrefabHash != record.PrefabHash || check[0].Stack != record.Stack || check[0].Quality != record.Quality)
                {
                    Refused.Add(id);
                    FloatPlugin.Log.LogWarning($"REFUSED item {id}: the crate blob did not read back the same. Nothing written.");
                    return;
                }
                var surface = new Vector3(pos.x, Items.WaterLevel + FloatPlugin.SurfaceOffset.Value, pos.z);
                crate = Crates.Create(surface, single);
                ServerSide.Trace($"Crate {crate.m_uid} created at {ServerSide.Fmt(surface)} with {what}; owner released (persistent {crate.Persistent}, distant {crate.Distant}, type {crate.Type}, revision {crate.DataRevision})");
            }

            // Only now, with the crate written, remove the item. DestroyZDO needs ownership and
            // queues the broadcast; a crash between the two lines duplicates, never loses.
            item.SetOwner(ZDOMan.GetSessionID());
            ZDOMan.instance.DestroyZDO(item);
            Crates.ItemsConverted++;
            ServerSide.Trace($"Item {id} destroyed");
        }

        internal static string Stats()
        {
            return $"{Crates.CratesCreated} crate(s) created, {Crates.ItemsConverted} item(s) converted ({Crates.ItemsMerged} merged), {FloatPlugin.PendingCount} pending, {Refused.Count} refused, {FishRelease.FishReleased} fish released from old crates";
        }
    }
}
