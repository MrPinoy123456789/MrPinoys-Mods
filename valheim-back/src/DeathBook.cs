using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using UnityEngine;

namespace MrPinoys.Back
{
    internal sealed class DeathRecord
    {
        public long PlayerId;
        public string PlayerName;
        public Vector3 Position;
        public float Yaw;
        public DateTime When;

        public Quaternion Rotation => Quaternion.Euler(0f, Yaw, 0f);
    }

    /// <summary>
    /// Fallback for deaths that leave no tombstone (keep-inventory worlds, or an empty
    /// inventory): the last death position per player, keyed by the profile's player
    /// ID. One line per player so it survives a restart. A record is consumed on use.
    /// </summary>
    internal sealed class DeathBook
    {
        private readonly string _path;
        private readonly Dictionary<long, DeathRecord> _records = new Dictionary<long, DeathRecord>();

        public DeathBook(string path) { _path = path; }

        public int Count => _records.Count;

        public void Record(long playerId, string name, Vector3 pos, float yaw)
        {
            _records[playerId] = new DeathRecord
            {
                PlayerId = playerId,
                PlayerName = name ?? "",
                Position = pos,
                Yaw = yaw,
                When = DateTime.UtcNow,
            };
            Save();
        }

        public DeathRecord Get(long playerId)
        {
            return _records.TryGetValue(playerId, out var r) ? r : null;
        }

        public IEnumerable<DeathRecord> All() => _records.Values;

        public DeathRecord FindByName(string name)
        {
            foreach (var r in _records.Values)
            {
                if (string.Equals(r.PlayerName, name, StringComparison.OrdinalIgnoreCase)) return r;
            }
            return null;
        }

        public void Forget(long playerId)
        {
            if (_records.Remove(playerId)) Save();
        }

        public void Load()
        {
            _records.Clear();
            if (!File.Exists(_path)) return;
            var inv = CultureInfo.InvariantCulture;
            foreach (var line in File.ReadAllLines(_path))
            {
                if (line.Length == 0) continue;
                var f = line.Split('\t');
                if (f.Length < 7) continue;
                try
                {
                    long id = long.Parse(f[0], inv);
                    _records[id] = new DeathRecord
                    {
                        PlayerId = id,
                        PlayerName = f[1],
                        Position = new Vector3(float.Parse(f[2], inv), float.Parse(f[3], inv), float.Parse(f[4], inv)),
                        Yaw = float.Parse(f[5], inv),
                        When = new DateTime(long.Parse(f[6], inv), DateTimeKind.Utc),
                    };
                }
                catch (Exception e)
                {
                    BackPlugin.Log.LogWarning($"Skipping unreadable death record '{line}': {e.Message}");
                }
            }
        }

        private void Save()
        {
            try
            {
                var inv = CultureInfo.InvariantCulture;
                var lines = new List<string>(_records.Count);
                foreach (var r in _records.Values)
                {
                    lines.Add(string.Join("\t",
                        r.PlayerId.ToString(inv),
                        r.PlayerName.Replace('\t', ' '),
                        r.Position.x.ToString(inv), r.Position.y.ToString(inv), r.Position.z.ToString(inv),
                        r.Yaw.ToString(inv),
                        r.When.Ticks.ToString(inv)));
                }
                Directory.CreateDirectory(Path.GetDirectoryName(_path));
                File.WriteAllLines(_path, lines);
            }
            catch (Exception e)
            {
                BackPlugin.Log.LogError($"Could not save death records: {e}");
            }
        }
    }
}
