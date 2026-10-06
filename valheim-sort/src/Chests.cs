using System;
using System.Collections.Generic;
using UnityEngine;

namespace MrPinoys.Sort
{
    /// <summary>What we need to know about a chest prefab: its grid size, read off the prefab's Container component.</summary>
    internal sealed class ChestKind
    {
        public string Name;
        public int Width;
        public int Height;
    }

    /// <summary>
    /// Which prefabs count as sortable chests, and how to find chest ZDOs. The server
    /// holds no Container instances, so everything here works from the prefab list
    /// (ZNetScene) and the ZDOs (ZDOMan).
    /// </summary>
    internal static class Chests
    {
        private static Dictionary<int, ChestKind> _kinds;
        private static readonly List<ZDO> SweepList = new List<ZDO>();

        /// <summary>The chest prefab table, built on first use once ZNetScene exists. Null until then.</summary>
        internal static Dictionary<int, ChestKind> Kinds()
        {
            if (_kinds != null) return _kinds;
            if (ZNetScene.instance == null) return null;

            var kinds = new Dictionary<int, ChestKind>();
            string configured = SortPlugin.Prefabs.Value?.Trim();
            if (!string.IsNullOrEmpty(configured))
            {
                foreach (string raw in configured.Split(','))
                {
                    string name = raw.Trim();
                    if (name.Length == 0) continue;
                    var prefab = ZNetScene.instance.GetPrefab(name);
                    var container = prefab != null ? prefab.GetComponent<Container>() : null;
                    if (container == null)
                    {
                        SortPlugin.Log.LogWarning($"Configured prefab '{name}' {(prefab == null ? "does not exist" : "has no Container component on its root")}; ignored.");
                        continue;
                    }
                    kinds[name.GetStableHashCode()] = new ChestKind { Name = name, Width = container.m_width, Height = container.m_height };
                }
            }
            else
            {
                foreach (var prefab in ZNetScene.instance.m_prefabs)
                {
                    if (prefab == null) continue;
                    var container = prefab.GetComponent<Container>();
                    if (container == null) continue;
                    string why = null;
                    if (prefab.GetComponent<Piece>() == null) why = "not a Piece (dungeon or loot container)";
                    else if (prefab.GetComponent<Vagon>() != null || container.m_wagon != null) why = "cart";
                    else if (container.m_rootObjectOverride != null) why = "container belongs to a parent object";
                    if (why != null)
                    {
                        ServerSide.Trace($"Prefab {prefab.name} has a Container but is skipped: {why}.");
                        continue;
                    }
                    kinds[prefab.name.GetStableHashCode()] = new ChestKind { Name = prefab.name, Width = container.m_width, Height = container.m_height };
                }
            }

            var names = new List<string>();
            foreach (var k in kinds.Values) names.Add($"{k.Name} ({k.Width}x{k.Height})");
            names.Sort(StringComparer.Ordinal);
            SortPlugin.Log.LogInfo($"Sortable chest prefabs ({kinds.Count}): {string.Join(", ", names)}");
            if (kinds.Count == 0) SortPlugin.Log.LogWarning("No chest prefabs found; nothing will ever be sorted. Set General.Prefabs in the config.");
            _kinds = kinds;
            return _kinds;
        }

        internal static ChestKind KindOf(ZDO zdo)
        {
            var kinds = Kinds();
            if (kinds == null || zdo == null) return null;
            kinds.TryGetValue(zdo.GetPrefab(), out var kind);
            return kind;
        }

        internal static bool IsChest(ZDO zdo) => KindOf(zdo) != null;

        internal static bool InUse(ZDO zdo) => zdo.GetInt(ZDOVars.s_inUse, 0) == 1;

        /// <summary>Chest ZDOs within radius (horizontal) of a point. Looks at the 3x3 zones around it, so radius must stay under 64 m.</summary>
        internal static List<ZDO> Near(Vector3 point, float radius)
        {
            var found = new List<ZDO>();
            if (ZDOMan.instance == null || Kinds() == null) return found;
            SweepList.Clear();
            ZDOMan.instance.FindSectorObjects(ZoneSystem.GetZone(point), new SimulationDistance(1, 0, classic: true), SweepList);
            foreach (var zdo in SweepList)
            {
                if (!IsChest(zdo)) continue;
                if (Flat(zdo.GetPosition(), point) <= radius) found.Add(zdo);
            }
            SweepList.Clear();
            return found;
        }

        internal static float Flat(Vector3 a, Vector3 b)
        {
            a.y = 0f; b.y = 0f;
            return Vector3.Distance(a, b);
        }
    }
}
