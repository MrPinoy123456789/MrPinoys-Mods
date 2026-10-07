using System;
using System.Collections.Generic;
using UnityEngine;

namespace MrPinoys.Float
{
    /// <summary>
    /// One-time cleanup for crates made before fish were excluded: every crate of ours
    /// that holds fish gets the fish taken out and put back in the water as live fish
    /// ZDOs. Runs from the ReleaseNearbyZDOS sweep, so only crates near a player are
    /// touched (the player's client then instantiates the fish). Each crate is handled
    /// once and stamped so a restart does not repeat it.
    /// </summary>
    internal static class FishRelease
    {
        /// <summary>Stamped on a crate once its fish are out; crates made after the fix carry it from birth.</summary>
        internal static readonly int NoFishKey = "mrpinoys_float_nofish".GetStableHashCode();

        // How far ahead of the client the crate's data revision is pushed so its stale
        // updates (it bumps the revision every frame it bobs) are all ignored.
        private const uint RevisionJump = 1000u;

        private static readonly HashSet<ZDOID> Checked = new HashSet<ZDOID>();
        internal static int FishReleased;

        internal static void Consider(ZDO crate)
        {
            if (!FloatPlugin.ReleaseFishFromCrates.Value) return;
            if (Checked.Contains(crate.m_uid)) return;
            if (crate.GetInt(NoFishKey, 0) == 1) { Checked.Add(crate.m_uid); return; }
            if (crate.GetInt(ZDOVars.s_inUse, 0) != 0) return; // try again on the next sweep

            byte[] blob = crate.GetByteArray(ZDOVars.s_items);
            List<ItemRecord> contents;
            try
            {
                contents = blob != null && blob.Length > 0 ? ItemCodec.Parse(blob, out _) : new List<ItemRecord>();
            }
            catch (Exception e)
            {
                Checked.Add(crate.m_uid);
                FloatPlugin.Log.LogWarning($"Fish release: crate {crate.m_uid} has an items blob we cannot parse ({e.Message}); left alone.");
                return;
            }

            var fish = new List<ItemRecord>();
            var keep = new List<ItemRecord>();
            foreach (var r in contents)
            {
                if (Items.PrefabIsFish(r.PrefabHash)) fish.Add(r); else keep.Add(r);
            }
            if (fish.Count == 0)
            {
                Checked.Add(crate.m_uid);
                return;
            }

            // Re-read the crate blob that is about to be written; refuse rather than lose anything.
            byte[] newBlob = ItemCodec.Serialize(keep);
            var check = ItemCodec.Parse(newBlob, out _);
            if (!ItemCodec.SameCounts(ItemCodec.Counts(check, out _), ItemCodec.Counts(keep, out _)))
            {
                Checked.Add(crate.m_uid);
                FloatPlugin.Log.LogWarning($"REFUSED fish release on crate {crate.m_uid}: the remaining contents did not read back the same. Nothing written.");
                return;
            }

            // Fish first, so a crash here duplicates fish rather than losing them.
            var cratePos = crate.GetPosition();
            var names = new List<string>();
            foreach (var r in fish)
            {
                var pos = new Vector3(cratePos.x + UnityEngine.Random.Range(-1f, 1f), Items.WaterLevel - 1.5f, cratePos.z + UnityEngine.Random.Range(-1f, 1f));
                var zdo = Crates.CreateItem(pos, r);
                if (zdo == null)
                {
                    Checked.Add(crate.m_uid);
                    FloatPlugin.Log.LogWarning($"REFUSED fish release on crate {crate.m_uid}: could not create a {Items.PrefabName(r.PrefabHash)} ZDO. Fish already created stay; the crate is untouched.");
                    return;
                }
                names.Add(Items.Describe(r));
                FishReleased += r.Stack;
                ServerSide.Trace($"Fish {zdo.m_uid} ({Items.Describe(r)}) created at {ServerSide.Fmt(pos)}; owner released");
            }

            // Authoritative rewrite: the owning client bumps the revision every frame the crate
            // bobs, so a plain write would be overwritten. Take the crate, write, push the
            // revision far ahead so every in-flight client update is stale, then release it.
            long previousOwner = crate.GetOwner();
            crate.SetOwner(ZDOMan.GetSessionID());
            crate.Set(ZDOVars.s_items, newBlob);
            crate.Set(NoFishKey, 1);
            crate.DataRevision += RevisionJump;
            crate.SetOwner(0L);
            Checked.Add(crate.m_uid);
            ServerSide.Trace($"Crate {crate.m_uid} at {ServerSide.Fmt(cratePos)}: released {string.Join(", ", names)} back into the water; {keep.Count} stack(s) left; was owned by {ServerSide.PeerName(previousOwner)}, revision now {crate.DataRevision}, owner released{(keep.Count == 0 ? "; the client will destroy the empty crate" : "")}");
        }
    }
}
