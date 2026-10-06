using System;
using System.Collections.Generic;
using UnityEngine;

namespace MrPinoys.Back
{
    /// <summary>
    /// Tombstones are the source of truth. Each one is a ZDO the server keeps, stamped
    /// by the vanilla client with the owner's player ID, name and time of death, and it
    /// disappears on its own once looted.
    /// </summary>
    internal static class Tombstones
    {
        private const string Prefab = "Player_tombstone";

        internal static List<ZDO> All()
        {
            var list = new List<ZDO>();
            if (ZDOMan.instance == null) return list;
            int index = 0;
            while (!ZDOMan.instance.GetAllZDOsWithPrefabIterative(Prefab, list, ref index)) { }
            return list;
        }

        internal static long OwnerOf(ZDO zdo) => zdo.GetLong(ZDOVars.s_owner, 0L);
        internal static string OwnerNameOf(ZDO zdo) => zdo.GetString(ZDOVars.s_ownerName, "");

        /// <summary>
        /// Tombstones belonging to the player: matched by player ID when we know it,
        /// otherwise by the owner name the client stamped on the stone.
        /// </summary>
        internal static List<ZDO> Mine(long playerId, string playerName)
        {
            var mine = new List<ZDO>();
            bool byName = playerId == 0L && !string.IsNullOrEmpty(playerName);
            if (playerId == 0L && !byName) return mine;
            foreach (var zdo in All())
            {
                bool match = byName
                    ? string.Equals(OwnerNameOf(zdo), playerName, System.StringComparison.OrdinalIgnoreCase)
                    : OwnerOf(zdo) == playerId;
                if (match) mine.Add(zdo);
            }
            return mine;
        }

        /// <summary>Newest tombstone owned by the player, or null.</summary>
        internal static ZDO Newest(long playerId, string playerName)
        {
            ZDO best = null;
            long bestTime = long.MinValue;
            foreach (var zdo in Mine(playerId, playerName))
            {
                long t = zdo.GetLong(ZDOVars.s_timeOfDeath, 0L);
                if (t > bestTime) { bestTime = t; best = zdo; }
            }
            return best;
        }

        /// <summary>Closest own tombstone to a point (horizontal distance), inside radius, or null.</summary>
        internal static ZDO Nearest(long playerId, string playerName, Vector3 point, float radius)
        {
            ZDO best = null;
            float bestDist = radius;
            foreach (var zdo in Mine(playerId, playerName))
            {
                float d = Flat(zdo.GetPosition(), point);
                if (d <= bestDist) { bestDist = d; best = zdo; }
            }
            return best;
        }

        internal static float Flat(Vector3 a, Vector3 b)
        {
            a.y = 0f; b.y = 0f;
            return Vector3.Distance(a, b);
        }

        /// <summary>Age of the tombstone in in-game days, or -1 when unknown.</summary>
        internal static double AgeDays(ZDO zdo)
        {
            long ticks = zdo.GetLong(ZDOVars.s_timeOfDeath, 0L);
            if (ticks == 0L || ZNet.instance == null) return -1;
            try { return (ZNet.instance.GetTime() - new DateTime(ticks)).TotalDays; }
            catch { return -1; }
        }
    }
}
