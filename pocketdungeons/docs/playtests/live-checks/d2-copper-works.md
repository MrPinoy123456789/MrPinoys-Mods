# d2 copper works (T32)

Play a full Copper Works run and check:

- On floor one, a title line explains the clock ("The furnace is counting down."
  plus "Every cleared spawner buys it time."), and a timer rides the action bar
  counting down from 20:00.
- Clear a spawner: the clock jumps up by 60 seconds (config: copperClockBonusSeconds).
- The action bar timer turns dark red inside the last two minutes.
- Let the clock hit zero (or set copperClockMinutes low): nobody takes damage or
  dies; the title reads "The furnace blows." / "The Foreman does not wait." Ride
  to the final floor: the finale starts on its own with no five-second drum and
  the wave stands almost at once. Clearing more spawners there does not restart
  the countdown.
- With the clock blown, the vents pulse noticeably faster (about 2x).
- On master_furnace: six floor grates in a serpentine band hiss and smoke for a
  full two seconds, then burst into flame and ignite whoever stands on the tile
  (about three seconds of burn). The sweep order is the same every pulse.
  Standing on the south exit row never burns.
- Two water cauldrons stand in the room. Lure or knock the Foreman into one: he
  splashes, takes a point of drown damage each second, and carries Weakness plus
  Slowness for about four seconds. The first time, chat reads "The water bites
  him."
- The reward pad and the exit door stay reachable for the whole fight.
- Time the run before zero instead: the normal spawner-clear finale still works,
  countdown included.
