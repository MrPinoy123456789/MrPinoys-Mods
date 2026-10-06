using System;
using System.Collections.Generic;

namespace MrPinoys.Sort
{
    /// <summary>
    /// Reads a chest ZDO's items blob, groups and merges it, and writes it back. Refuses
    /// to write when the item counts before and after differ in any way.
    /// </summary>
    internal static class Sorter
    {
        private static Dictionary<ItemDrop.ItemData.ItemType, int> _typeRank;
        private static readonly Dictionary<int, ItemDrop.ItemData.SharedData> SharedCache = new Dictionary<int, ItemDrop.ItemData.SharedData>();
        private static readonly HashSet<int> UnknownPrefabsLogged = new HashSet<int>();

        // Hash of the blob we last wrote per chest, so the echo of our own write is not mistaken for a client change.
        internal static readonly Dictionary<ZDOID, ulong> LastWritten = new Dictionary<ZDOID, ulong>();

        internal static void SortChest(ZDOID id, string reason)
        {
            if (!ServerSide.IsServer() || ZDOMan.instance == null) return;
            var zdo = ZDOMan.instance.GetZDO(id);
            if (zdo == null)
            {
                ServerSide.Trace($"Chest {id} ({reason}): ZDO is gone; skipped.");
                return;
            }
            SortChest(zdo, reason);
        }

        internal static bool SortChest(ZDO zdo, string reason)
        {
            var kind = Chests.KindOf(zdo);
            string tag = $"Chest {zdo.m_uid} ({(kind != null ? kind.Name : zdo.GetPrefab().ToString())}, {reason})";
            if (kind == null)
            {
                ServerSide.Trace($"{tag}: not a sortable chest prefab; skipped.");
                return false;
            }
            if (Chests.InUse(zdo))
            {
                ServerSide.Trace($"{tag}: in use by {ServerSide.PeerName(zdo.GetOwner())}; skipped.");
                return false;
            }
            if (ObjectDB.instance == null)
            {
                SortPlugin.Log.LogWarning($"{tag}: ObjectDB.instance is null on this server, cannot resolve items; skipped.");
                return false;
            }

            byte[] before = zdo.GetByteArray(ZDOVars.s_items);
            if (before == null || before.Length == 0)
            {
                ServerSide.Trace($"{tag}: no items blob; skipped.");
                return false;
            }

            List<ItemRecord> items;
            int version;
            try
            {
                items = ItemCodec.Parse(before, out version);
            }
            catch (Exception e)
            {
                SortPlugin.Log.LogWarning($"{tag}: could not parse the items blob ({before.Length} bytes): {e.Message}; skipped.");
                return false;
            }
            if (items.Count == 0)
            {
                ServerSide.Trace($"{tag}: empty; skipped.");
                return false;
            }

            int unresolved = 0;
            foreach (var r in items)
            {
                r.Shared = Resolve(r, tag);
                r.ResolvedName = r.Shared != null ? r.Shared.m_name : (r.PrefabName ?? ("#" + r.PrefabHash));
                if (r.Shared == null) unresolved++;
            }

            var beforeCounts = ItemCodec.Counts(items, out int beforeTotal);
            var sorted = Arrange(items, kind, out int rows);
            var afterCounts = ItemCodec.Counts(sorted, out int afterTotal);
            if (afterTotal != beforeTotal || !ItemCodec.SameCounts(beforeCounts, afterCounts))
            {
                SortPlugin.Log.LogError($"{tag}: REFUSED, sorting changed the item counts. before {beforeTotal}: {ItemCodec.Describe(beforeCounts)} | after {afterTotal}: {ItemCodec.Describe(afterCounts)}");
                return false;
            }

            byte[] after;
            try
            {
                after = ItemCodec.Serialize(sorted);
                var check = ItemCodec.Parse(after, out _);
                var checkCounts = ItemCodec.Counts(check, out int checkTotal);
                if (checkTotal != beforeTotal || !ItemCodec.SameCounts(beforeCounts, checkCounts))
                {
                    SortPlugin.Log.LogError($"{tag}: REFUSED, the re-read blob does not match. before {beforeTotal}: {ItemCodec.Describe(beforeCounts)} | re-read {checkTotal}: {ItemCodec.Describe(checkCounts)}");
                    return false;
                }
            }
            catch (Exception e)
            {
                SortPlugin.Log.LogError($"{tag}: REFUSED, could not serialize or re-read the sorted blob: {e}");
                return false;
            }

            if (version >= 108 && SameBytes(before, after))
            {
                ServerSide.Trace($"{tag}: already sorted ({items.Count} stacks, {beforeTotal} items); nothing written.");
                return false;
            }
            if (Chests.InUse(zdo))
            {
                ServerSide.Trace($"{tag}: opened while sorting; skipped.");
                return false;
            }

            uint revBefore = zdo.DataRevision;
            zdo.Set(ZDOVars.s_items, after);
            LastWritten[zdo.m_uid] = ItemCodec.Hash(after);
            long owner = zdo.GetOwner();
            string ownerNote;
            if (owner != 0L && owner == ZDOMan.GetSessionID())
            {
                // Never leave the server owning a chest: nobody could open it (RPC_RequestOpen is answered by the owner, and the server has no Container).
                zdo.SetOwner(0L);
                ownerNote = "owner was the server, released";
            }
            else
            {
                ownerNote = owner == 0L ? "no owner" : $"owner {ServerSide.PeerName(owner)} kept";
            }
            string extra = rows > kind.Height ? $", {rows} rows (prefab has {kind.Height})" : "";
            string unresolvedNote = unresolved > 0 ? $", {unresolved} unknown item(s) left in place" : "";
            SortPlugin.Log.LogInfo($"Sorted {zdo.m_uid} ({kind.Name}, {reason}): {items.Count} -> {sorted.Count} stacks, {beforeTotal} items, format {version} -> 109, wrote {after.Length / 1024f:0.0} KB, revision {revBefore} -> {zdo.DataRevision}, {ownerNote}{extra}{unresolvedNote}");

            if (SortPlugin.Notify.Value && reason == "closed" && owner != 0L && owner != ZDOMan.GetSessionID())
            {
                ServerSide.Message(owner, items.Count == sorted.Count ? "Chest sorted" : $"Chest sorted, {items.Count} stacks merged into {sorted.Count}");
            }
            return true;
        }

        // ---- arrangement ----

        /// <summary>Group, merge and lay out. Never changes the identity or total of anything; unknown items go last, untouched.</summary>
        private static List<ItemRecord> Arrange(List<ItemRecord> items, ChestKind kind, out int rows)
        {
            var known = new List<ItemRecord>();
            var unknown = new List<ItemRecord>();
            foreach (var r in items) (r.Shared != null ? known : unknown).Add(r);

            var merged = SortPlugin.MergeStacks.Value ? Merge(known) : Copy(known);
            merged.Sort(Compare);
            merged.AddRange(Copy(unknown));

            int width = Math.Max(1, kind.Width);
            rows = Math.Max(kind.Height, (merged.Count + width - 1) / width);
            for (int i = 0; i < merged.Count; i++)
            {
                merged[i].GridX = i % width;
                merged[i].GridY = i / width;
            }
            return merged;
        }

        private static List<ItemRecord> Copy(List<ItemRecord> items)
        {
            var list = new List<ItemRecord>(items.Count);
            foreach (var r in items) list.Add(r.Clone());
            return list;
        }

        private static List<ItemRecord> Merge(List<ItemRecord> items)
        {
            var result = new List<ItemRecord>();
            var groups = new Dictionary<string, List<ItemRecord>>();
            var order = new List<string>();
            foreach (var r in items)
            {
                if (r.Shared.m_maxStackSize <= 1)
                {
                    result.Add(r.Clone());
                    continue;
                }
                string key = r.CountKey() + "|" + r.CustomDataKey();
                if (!groups.TryGetValue(key, out var group))
                {
                    group = new List<ItemRecord>();
                    groups[key] = group;
                    order.Add(key);
                }
                group.Add(r);
            }
            foreach (string key in order)
            {
                var group = groups[key];
                int max = Math.Max(1, group[0].Shared.m_maxStackSize);
                long total = 0;
                foreach (var r in group) total += r.Stack;
                // A stack over the max (mods, old data) is kept as it is rather than split into something the client would not have written.
                bool oversized = false;
                foreach (var r in group) if (r.Stack > max) oversized = true;
                if (oversized)
                {
                    foreach (var r in group) result.Add(r.Clone());
                    continue;
                }
                while (total > 0)
                {
                    var c = group[0].Clone();
                    c.Stack = (int)Math.Min(max, total);
                    total -= c.Stack;
                    result.Add(c);
                }
            }
            return result;
        }

        private static int Compare(ItemRecord a, ItemRecord b)
        {
            int c = Rank(a.Shared.m_itemType).CompareTo(Rank(b.Shared.m_itemType));
            if (c != 0) return c;
            c = string.CompareOrdinal(a.Shared.m_name, b.Shared.m_name);
            if (c != 0) return c;
            c = a.PrefabHash.CompareTo(b.PrefabHash);
            if (c != 0) return c;
            c = b.Quality.CompareTo(a.Quality);
            if (c != 0) return c;
            c = a.WorldLevel.CompareTo(b.WorldLevel);
            if (c != 0) return c;
            c = a.Variant.CompareTo(b.Variant);
            if (c != 0) return c;
            c = b.Stack.CompareTo(a.Stack);
            if (c != 0) return c;
            return b.DurabilityRaw.CompareTo(a.DurabilityRaw);
        }

        private static int Rank(ItemDrop.ItemData.ItemType type)
        {
            if (_typeRank == null)
            {
                _typeRank = new Dictionary<ItemDrop.ItemData.ItemType, int>();
                string list = SortPlugin.TypeOrder.Value ?? "";
                int i = 0;
                foreach (string raw in list.Split(','))
                {
                    string name = raw.Trim();
                    if (name.Length == 0) continue;
                    if (Enum.TryParse(name, true, out ItemDrop.ItemData.ItemType parsed))
                    {
                        if (!_typeRank.ContainsKey(parsed)) _typeRank[parsed] = i++;
                    }
                    else
                    {
                        SortPlugin.Log.LogWarning($"TypeOrder: '{name}' is not an ItemType; ignored. Known: {string.Join(", ", Enum.GetNames(typeof(ItemDrop.ItemData.ItemType)))}");
                    }
                }
            }
            return _typeRank.TryGetValue(type, out int rank) ? rank : int.MaxValue;
        }

        // ---- prefab lookup ----

        private static ItemDrop.ItemData.SharedData Resolve(ItemRecord r, string tag)
        {
            if (r.PrefabHash == 0)
            {
                ServerSide.Trace($"{tag}: an item has no prefab hash (stack {r.Stack}); left in place.");
                return null;
            }
            if (SharedCache.TryGetValue(r.PrefabHash, out var cached)) return cached;
            var prefab = ObjectDB.instance.GetItemPrefab(r.PrefabHash);
            var drop = prefab != null ? prefab.GetComponent<ItemDrop>() : null;
            var shared = drop != null ? drop.m_itemData?.m_shared : null;
            if (shared == null)
            {
                if (UnknownPrefabsLogged.Add(r.PrefabHash))
                    SortPlugin.Log.LogWarning($"{tag}: ObjectDB has no item for prefab hash {r.PrefabHash}{(r.PrefabName != null ? " (" + r.PrefabName + ")" : "")}; such stacks are left in place.");
                return null;
            }
            SharedCache[r.PrefabHash] = shared;
            return shared;
        }

        private static bool SameBytes(byte[] a, byte[] b)
        {
            if (a.Length != b.Length) return false;
            for (int i = 0; i < a.Length; i++) if (a[i] != b[i]) return false;
            return true;
        }
    }
}
