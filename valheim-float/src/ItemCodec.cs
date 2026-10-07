using System;
using System.Collections.Generic;
using System.Text;

namespace MrPinoys.Float
{
    /// <summary>
    /// One item stack exactly as the chest ZDO stores it. Every field survives a
    /// round trip untouched; sorting only ever changes GridX, GridY and Stack.
    /// </summary>
    internal sealed class ItemRecord
    {
        public int PrefabHash;          // 0 when the client saved an item without a drop prefab (vanilla drops those on load)
        public string PrefabName;       // only known for the pre-1.0 name based format
        public int DurabilityRaw;       // hundredths, as stored
        public int GridX;
        public int GridY;
        public int WorldLevel;
        public bool PickedUp;
        public bool Equipped;
        public int Quality = 1;
        public int Stack = 1;
        public int Variant;
        public long CrafterId;
        public string CrafterName = "";
        public List<KeyValuePair<string, string>> CustomData = new List<KeyValuePair<string, string>>();
        public bool Cheated;

        // Filled in from ObjectDB by the sorter; null when the prefab is unknown.
        public ItemDrop.ItemData.SharedData Shared;
        public string ResolvedName;

        public ItemRecord Clone()
        {
            var c = (ItemRecord)MemberwiseClone();
            c.CustomData = new List<KeyValuePair<string, string>>(CustomData);
            return c;
        }

        /// <summary>Identity for the count assertion: everything that makes two stacks "the same item" in vanilla, plus the flags we must not blur.</summary>
        public string CountKey()
        {
            var sb = new StringBuilder();
            sb.Append(PrefabHash != 0 ? PrefabHash.ToString() : "name:" + PrefabName);
            sb.Append('|').Append(Quality).Append('|').Append(WorldLevel).Append('|').Append(Variant).Append('|').Append(Cheated ? 1 : 0);
            return sb.ToString();
        }

        public string CustomDataKey()
        {
            if (CustomData.Count == 0) return "";
            var sb = new StringBuilder();
            foreach (var kv in CustomData) sb.Append(kv.Key).Append('=').Append(kv.Value).Append(';');
            return sb.ToString();
        }
    }

    /// <summary>
    /// Reads and writes the byte array Container.Save puts under ZDOVars.s_items.
    /// Mirrors Inventory.Save / Inventory.Load and ItemDrop.ItemData.Save / Load from
    /// the 1.0 assembly. Output is always the current format (version 109).
    /// </summary>
    internal static class ItemCodec
    {
        // Version.Item values from the decompile.
        private const int VersionQuality = 101;
        private const int VersionVariant = 102;
        private const int VersionCrafterId = 103;
        private const int VersionCustomData = 104;
        private const int VersionWorldLevel = 105;
        private const int VersionPickedUp = 106;
        private const int VersionAbandonedDN = 107;
        private const int VersionSmaller = 108;
        private const int VersionChunksNCheats = 109;

        internal static List<ItemRecord> Parse(byte[] bytes, out int version)
        {
            var pkg = new ZPackage(bytes);
            var items = new List<ItemRecord>();
            version = pkg.ReadInt();
            if (version >= VersionSmaller)
            {
                int count = pkg.ReadUShort();
                for (int i = 0; i < count; i++) items.Add(ReadCompact(pkg, version));
            }
            else
            {
                int count = pkg.ReadInt();
                for (int i = 0; i < count; i++) items.Add(ReadOld(pkg, version));
            }
            if (pkg.GetPos() != pkg.Size())
                throw new InvalidOperationException($"items blob has {pkg.Size() - pkg.GetPos()} trailing byte(s) after {items.Count} item(s) (format {version})");
            return items;
        }

        internal static ItemRecord ReadCompact(ZPackage pkg, int version)
        {
            var r = new ItemRecord();
            r.DurabilityRaw = pkg.ReadInt();
            r.GridX = pkg.ReadByte();
            r.GridY = pkg.ReadByte();
            r.WorldLevel = pkg.ReadByte();
            byte flags = pkg.ReadByte();
            r.PickedUp = (flags & 1) != 0;
            r.Equipped = (flags & 2) != 0;
            r.Quality = (flags & 4) != 0 ? pkg.ReadUShort() : 1;
            r.Stack = (flags & 8) != 0 ? pkg.ReadUShort() : 1;
            r.Variant = (flags & 0x10) != 0 ? pkg.ReadInt() : 0;
            r.CrafterId = (flags & 0x20) != 0 ? pkg.ReadLong() : 0L;
            r.CrafterName = (flags & 0x20) != 0 ? pkg.ReadString() : "";
            r.PrefabHash = (flags & 0x40) != 0 ? pkg.ReadInt() : 0;
            int custom = (flags & 0x80) != 0 ? pkg.ReadNumItems() : 0;
            for (int i = 0; i < custom; i++)
            {
                string key = pkg.ReadString();
                string value = pkg.ReadString();
                r.CustomData.Add(new KeyValuePair<string, string>(key, value));
            }
            if (version >= VersionChunksNCheats || version == VersionAbandonedDN)
            {
                r.Cheated = (pkg.ReadByte() & 1) != 0;
            }
            return r;
        }

        private static ItemRecord ReadOld(ZPackage pkg, int version)
        {
            var r = new ItemRecord();
            r.PrefabName = pkg.ReadString();
            r.Stack = pkg.ReadInt();
            float durability = pkg.ReadSingle();
            r.DurabilityRaw = (int)(durability * 100f);
            var pos = pkg.ReadVector2i();
            r.GridX = pos.x;
            r.GridY = pos.y;
            r.Equipped = pkg.ReadBool();
            if (version >= VersionQuality) r.Quality = pkg.ReadInt();
            if (version >= VersionVariant) r.Variant = pkg.ReadInt();
            if (version >= VersionCrafterId)
            {
                r.CrafterId = pkg.ReadLong();
                r.CrafterName = pkg.ReadString();
            }
            if (version >= VersionCustomData)
            {
                int n = pkg.ReadInt();
                for (int i = 0; i < n; i++)
                {
                    string key = pkg.ReadString();
                    string value = pkg.ReadString();
                    r.CustomData.Add(new KeyValuePair<string, string>(key, value));
                }
            }
            if (version >= VersionWorldLevel) r.WorldLevel = pkg.ReadInt();
            if (version >= VersionPickedUp) r.PickedUp = pkg.ReadBool();
            if (version == VersionAbandonedDN) r.Cheated = pkg.ReadBool();
            r.PrefabHash = string.IsNullOrEmpty(r.PrefabName) ? 0 : r.PrefabName.GetStableHashCode();
            return r;
        }

        internal static byte[] Serialize(List<ItemRecord> items)
        {
            var pkg = new ZPackage();
            pkg.Write(VersionChunksNCheats);
            pkg.Write((ushort)items.Count);
            foreach (var r in items) WriteCompact(pkg, r);
            return pkg.GetArray();
        }

        internal static void WriteCompact(ZPackage pkg, ItemRecord r)
        {
            int flags = 0;
            flags |= r.PickedUp ? 1 : 0;
            flags |= r.Equipped ? 2 : 0;
            flags |= r.Quality != 1 ? 4 : 0;
            flags |= r.Stack != 1 ? 8 : 0;
            flags |= r.Variant != 0 ? 0x10 : 0;
            flags |= r.CrafterId != 0L ? 0x20 : 0;
            flags |= r.PrefabHash != 0 ? 0x40 : 0;
            flags |= r.CustomData.Count != 0 ? 0x80 : 0;
            pkg.Write(r.DurabilityRaw);
            pkg.Write((byte)r.GridX);
            pkg.Write((byte)r.GridY);
            pkg.Write((byte)r.WorldLevel);
            pkg.Write((byte)flags);
            if ((flags & 4) != 0) pkg.Write((ushort)r.Quality);
            if ((flags & 8) != 0) pkg.Write((ushort)r.Stack);
            if ((flags & 0x10) != 0) pkg.Write(r.Variant);
            if ((flags & 0x20) != 0)
            {
                pkg.Write(r.CrafterId);
                pkg.Write(r.CrafterName ?? "");
            }
            if ((flags & 0x40) != 0) pkg.Write(r.PrefabHash);
            if ((flags & 0x80) != 0) pkg.WriteNumItems(r.CustomData.Count);
            foreach (var kv in r.CustomData)
            {
                pkg.Write(kv.Key);
                pkg.Write(kv.Value);
            }
            pkg.Write((byte)(r.Cheated ? 1 : 0));
        }

        /// <summary>Total items per identity plus the grand total; the sorter refuses to write unless before and after agree.</summary>
        internal static Dictionary<string, int> Counts(List<ItemRecord> items, out int total)
        {
            var counts = new Dictionary<string, int>();
            total = 0;
            foreach (var r in items)
            {
                counts.TryGetValue(r.CountKey(), out int n);
                counts[r.CountKey()] = n + r.Stack;
                total += r.Stack;
            }
            return counts;
        }

        internal static bool SameCounts(Dictionary<string, int> a, Dictionary<string, int> b)
        {
            if (a.Count != b.Count) return false;
            foreach (var kv in a)
            {
                if (!b.TryGetValue(kv.Key, out int n) || n != kv.Value) return false;
            }
            return true;
        }

        internal static string Describe(Dictionary<string, int> counts)
        {
            var parts = new List<string>();
            foreach (var kv in counts) parts.Add($"{kv.Key}x{kv.Value}");
            parts.Sort(StringComparer.Ordinal);
            return string.Join(" ", parts);
        }

        /// <summary>FNV-1a over the blob, used to notice content changes without keeping a copy of every chest.</summary>
        internal static ulong Hash(byte[] bytes)
        {
            if (bytes == null) return 0UL;
            ulong h = 14695981039346656037UL;
            foreach (byte b in bytes)
            {
                h ^= b;
                h *= 1099511628211UL;
            }
            return h;
        }
    }
}
