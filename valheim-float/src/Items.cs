using System;
using System.Collections.Generic;
using UnityEngine;

namespace MrPinoys.Float
{
    /// <summary>
    /// Which ZDOs are dropped items, whether they are under water, and how to read the
    /// one item stack an item ZDO carries. Everything comes off prefabs and ZDOs; the
    /// server has no ItemDrop instances (see VALHEIM-SERVER-SIDE.md section 2).
    /// </summary>
    internal static class Items
    {
        // Water level is global in 1.0 (ZoneSystem.c_WaterLevel = 30). WaterVolume.GetWaterLevel
        // only differs beyond 10500 m from the centre, where it drops by 100 m; we skip out there.
        private const float WorldEdgeRadius = 10500f;
        // An item this far below the generated terrain has clipped through the ground; vanilla's
        // ItemDrop.TerrainCheck lifts it back within 10 s (its own threshold is 0.5 m).
        private const float ThroughGroundMargin = 1f;

        private static HashSet<int> _itemHashes;
        private static readonly Dictionary<int, bool> FloatsByHash = new Dictionary<int, bool>();
        private static readonly Dictionary<int, bool> FishByHash = new Dictionary<int, bool>();
        private static HashSet<int> _ignored;
        private static readonly Dictionary<int, string> NameByHash = new Dictionary<int, string>();

        internal static float WaterLevel => ZoneSystem.instance != null ? ZoneSystem.instance.m_waterLevel : ZoneSystem.c_WaterLevel;

        /// <summary>Every prefab hash in ObjectDB.m_items, built once on first use.</summary>
        internal static bool IsItemPrefab(int hash)
        {
            if (_itemHashes == null)
            {
                if (ObjectDB.instance == null || ObjectDB.instance.m_items == null || ObjectDB.instance.m_items.Count == 0) return false;
                var set = new HashSet<int>();
                foreach (var go in ObjectDB.instance.m_items)
                {
                    if (go == null) continue;
                    int h = go.name.GetStableHashCode();
                    set.Add(h);
                    NameByHash[h] = go.name;
                }
                _itemHashes = set;
                FloatPlugin.Log.LogInfo($"Item prefabs known: {set.Count} (from ObjectDB).");
            }
            return _itemHashes.Contains(hash);
        }

        internal static string PrefabName(int hash)
        {
            if (NameByHash.TryGetValue(hash, out var name)) return name;
            var go = ZNetScene.instance?.GetPrefab(hash);
            name = go != null ? go.name : $"hash {hash}";
            NameByHash[hash] = name;
            return name;
        }

        /// <summary>Items whose prefab already has a Floating component float in vanilla and are left alone.</summary>
        internal static bool PrefabFloats(int hash)
        {
            if (FloatsByHash.TryGetValue(hash, out bool floats)) return floats;
            var go = ZNetScene.instance?.GetPrefab(hash);
            floats = go != null && go.GetComponent<Floating>() != null;
            FloatsByHash[hash] = floats;
            return floats;
        }

        /// <summary>
        /// Live fish are ItemDrop prefabs too (they can be picked up) and swim under water;
        /// dropping a caught fish into water is how vanilla releases it. Never crate them.
        /// </summary>
        internal static bool PrefabIsFish(int hash)
        {
            if (FishByHash.TryGetValue(hash, out bool fish)) return fish;
            var go = ZNetScene.instance?.GetPrefab(hash);
            fish = go != null && go.GetComponent<Fish>() != null;
            FishByHash[hash] = fish;
            return fish;
        }

        /// <summary>Prefab names from General.IgnorePrefabs, hashed once.</summary>
        internal static bool PrefabIgnored(int hash)
        {
            if (_ignored == null)
            {
                _ignored = new HashSet<int>();
                foreach (var raw in (FloatPlugin.IgnorePrefabs.Value ?? "").Split(','))
                {
                    string name = raw.Trim();
                    if (name.Length > 0) _ignored.Add(name.GetStableHashCode());
                }
            }
            return _ignored.Contains(hash);
        }

        /// <summary>How far below the trigger depth the item is; positive means sunk.</summary>
        internal static float Depth(Vector3 pos)
        {
            return WaterLevel - pos.y;
        }

        /// <summary>
        /// The cheap test both hooks run on every ZDO they see: an item prefab, under
        /// water by more than the trigger depth, not a placed piece, not a floating item,
        /// not at the world edge where the water level differs.
        /// </summary>
        internal static bool IsSunkItem(ZDO zdo, out string why)
        {
            why = null;
            if (zdo == null || !zdo.IsValid()) return false;
            int prefab = zdo.GetPrefab();
            if (!IsItemPrefab(prefab)) return false;
            var pos = zdo.GetPosition();
            if (Depth(pos) < Mathf.Max(0f, FloatPlugin.TriggerDepthMeters.Value)) return false;
            if (Utils.LengthXZ(pos) > WorldEdgeRadius) { why = "beyond the world edge, water level differs there"; return false; }
            if (zdo.GetBool(ZDOVars.s_piece)) { why = "placed as a piece"; return false; }
            // Being below 30 is not enough: items on land clip through the terrain now and then and
            // fall until TerrainCheck rescues them. The generated terrain height (pure math off the
            // world seed, no colliders needed on the server) says whether there is water here at all.
            if (WorldGenerator.instance != null)
            {
                float terrain = WorldGenerator.instance.GetHeight(pos.x, pos.z);
                if (terrain >= WaterLevel) { why = $"on land, terrain at {terrain:0.0} (fell through the ground; vanilla lifts it back)"; return false; }
                if (pos.y < terrain - ThroughGroundMargin) { why = $"below the seabed at {terrain:0.0} (fell through; vanilla lifts it back, then it is caught)"; return false; }
            }
            if (PrefabFloats(prefab)) { why = "prefab floats on its own"; return false; }
            if (PrefabIsFish(prefab)) { why = "it is a fish"; return false; }
            if (PrefabIgnored(prefab)) { why = "listed in IgnorePrefabs"; return false; }
            return true;
        }

        /// <summary>
        /// Read the stack off an item ZDO exactly as ItemDrop.LoadFromZDO would: the
        /// "itemData" byte array is one version byte (109) followed by one
        /// ItemDrop.ItemData.Save record, the same layout ItemCodec.ReadCompact parses.
        /// Falls back to the pre-ChunkedSave per-field values (which ZDOMan.ConvertInventories
        /// migrates at world load, so they should never be met on a 1.0 server) and finally
        /// to the prefab's defaults, which is what a client loads when the blob is absent.
        /// Returns null, with the reason, for anything it cannot read completely.
        /// </summary>
        internal static ItemRecord Read(ZDO zdo, out string source, out string error)
        {
            source = null;
            error = null;
            int prefab = zdo.GetPrefab();
            byte[] blob = zdo.GetByteArray(ZDOVars.s_itemData);
            ItemRecord r;
            if (blob != null && blob.Length > 2)
            {
                try
                {
                    var pkg = new ZPackage(blob);
                    int version = pkg.ReadByte();
                    r = ItemCodec.ReadCompact(pkg, version);
                    if (pkg.GetPos() != pkg.Size())
                    {
                        error = $"itemData has {pkg.Size() - pkg.GetPos()} trailing byte(s) after the record (format {version})";
                        return null;
                    }
                    source = $"itemData v{version}";
                }
                catch (Exception e)
                {
                    error = $"itemData ({blob.Length} bytes) could not be parsed: {e.Message}";
                    return null;
                }
            }
            else if (zdo.GetInt(ZDOVars.s_stack, -1) >= 0 || zdo.GetFloat(ZDOVars.s_durability, -1f) >= 0f)
            {
                // Legacy per-field layout, mirrored from ZDOMan.ConvertInventories.
                r = Defaults(prefab, out error);
                if (r == null) return null;
                r.DurabilityRaw = (int)(zdo.GetFloat(ZDOVars.s_durability, r.DurabilityRaw / 100f) * 100f);
                r.Stack = zdo.GetInt(ZDOVars.s_stack, r.Stack);
                r.Quality = zdo.GetInt(ZDOVars.s_quality, r.Quality);
                r.Variant = zdo.GetInt(ZDOVars.s_variant, r.Variant);
                r.CrafterId = zdo.GetLong(ZDOVars.s_crafterID, r.CrafterId);
                r.CrafterName = zdo.GetString(ZDOVars.s_crafterName, r.CrafterName);
                int dataCount = zdo.GetInt(ZDOVars.s_dataCount, 0);
                for (int i = 0; i < dataCount; i++)
                {
                    r.CustomData.Add(new KeyValuePair<string, string>(zdo.GetString($"data_{i}", ""), zdo.GetString($"data__{i}", "")));
                }
                r.WorldLevel = zdo.GetInt(ZDOVars.s_worldLevel, r.WorldLevel);
                r.PickedUp = zdo.GetBool(ZDOVars.s_pickedUp, r.PickedUp);
                r.Cheated = zdo.GetBool(ZDOVars.s_cheated, r.Cheated);
                source = "legacy per-field vars";
            }
            else
            {
                // No item data at all: ItemDrop.Load leaves the prefab's ItemData untouched, so the
                // client sees the prefab defaults. Copy exactly those.
                r = Defaults(prefab, out error);
                if (r == null) return null;
                source = "prefab defaults (no itemData on the ZDO)";
            }

            if (r.PrefabHash == 0) r.PrefabHash = prefab;
            if (r.PrefabHash != prefab)
            {
                // The record names a different item than the ZDO's prefab. ItemDrop.Awake always
                // sets m_dropPrefab from the ZDO's own prefab name, so this should not happen; keep
                // the record's hash (that is what the client would show) but say so.
                source += $" (record prefab {PrefabName(r.PrefabHash)} differs from ZDO prefab {PrefabName(prefab)})";
            }
            if (ObjectDB.instance.GetItemPrefab(r.PrefabHash) == null)
            {
                error = $"ObjectDB has no item for prefab {PrefabName(r.PrefabHash)}; a chest could not hold it";
                return null;
            }
            if (r.Stack <= 0)
            {
                error = $"stack is {r.Stack}";
                return null;
            }
            r.GridX = 0;
            r.GridY = 0;
            r.Equipped = false;
            return r;
        }

        private static ItemRecord Defaults(int prefab, out string error)
        {
            error = null;
            var go = ObjectDB.instance.GetItemPrefab(prefab);
            var drop = go != null ? go.GetComponent<ItemDrop>() : null;
            if (drop == null)
            {
                error = $"ObjectDB has no ItemDrop prefab for {PrefabName(prefab)}";
                return null;
            }
            var d = drop.m_itemData;
            return new ItemRecord
            {
                PrefabHash = prefab,
                DurabilityRaw = (int)(d.m_durability * 100f),
                Stack = Mathf.Max(1, d.m_stack),
                Quality = Mathf.Max(1, d.m_quality),
                Variant = d.m_variant,
                WorldLevel = d.m_worldLevel,
                CrafterId = d.m_crafterID,
                CrafterName = d.m_crafterName ?? "",
            };
        }

        internal static string Describe(ItemRecord r)
        {
            string s = $"{PrefabName(r.PrefabHash)} x{r.Stack}";
            if (r.Quality > 1) s += $" q{r.Quality}";
            if (r.WorldLevel > 0) s += $" wl{r.WorldLevel}";
            return s;
        }
    }
}
