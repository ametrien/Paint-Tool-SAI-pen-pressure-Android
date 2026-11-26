# Where the CPU goes while drawing, and what is left to try

Measured on 2026-11-26 on a Galaxy Tab S6 Lite (SM-P613) with SAI Pen 0.4.38,
during 60 s of drawing by hand (about 220 pen samples per second). Percentages
are shares of one CPU core. See [IDLE-WAIT-TEST.md](IDLE-WAIT-TEST.md) for the
measurement itself.

## The path of one pen sample

```
 S Pen, ~220 samples/s                        [not touched]
   │                                          every sample, as Android gives it
   ├──► cursor: Winlator's X server           [checked: cannot]
   │    Android app, 25 %                     once per frame already; fewer
   │                                          would be visible
   │
   └──► point + pressure: our wintab32.dll    [checked today: gain when idle]
        very little CPU                       idle -27 %, drawing unchanged
              │
              ▼
        wineserver, Wine's go-between, 19 %   [not checked]
              │                               esync/fsync? fewer round trips?
              ▼
        SAI: brush and canvas, 71 %           [checked: 2 cores = -30 %]
        x86 code run by Box64                 Box64 flags, timelapse off: nothing
              │
              ▼
        picture to the screen                 [not checked]
        GDI → X server → texture              is the whole screen redrawn
        (part of the app's 25 %)              for a small change?
```

Total while drawing: about 115 % of a core (SAI 71 %, the Android app 25 %,
wineserver 19 %).

## Why CPU decides how smooth the brush feels

Pressure reaches SAI quickly: each point arrives with its pressure in a
fraction of a millisecond. The lag comes later, because SAI cannot paint as
fast as the points arrive. At 220 points per second, if painting a point takes
longer than the gap to the next one, a queue builds up and the line trails the
pen. We already saw that tail: after pen-up SAI keeps painting for another
80–240 ms.

So the goal is not "less CPU in general" but **less CPU per pen point while
drawing**. Then SAI keeps up, the line stays under the pen, and pressure
changes show sooner.

Rule for every change: nothing may look worse for the artist. The cursor keeps
moving once per pen event, and there is no temporary "wet ink" line.

## Already checked

| Part | What we tried | Result |
|---|---|---|
| S Pen input | — | Not touched: every sample goes on as Android gives it |
| Cursor (X server) | Fewer cursor moves | Not allowed: visible to the artist |
| Our DLL | Idle wait (250 ms after 2 s of quiet) | Idle: whole app 29 % → 21 %; drawing unchanged |
| SAI | SAI on 2 cores (CPU Cores Assignment C, or `BOX64_MAXCPU=2`) | About 30 % less CPU per stroke: the biggest win so far |
| SAI | Box64 flags (`SAFEFLAGS`, `AVX`, `CALLRET`, `DYNACACHE`), nice, core pinning | No measurable gain |
| SAI | Timelapse off | 0–7 %, within noise |
| wineserver | Is Wine's fast synchronisation (esync) on? | Yes: Winlator sets `WINEESYNC=1` by default (`XServerDisplayActivity`), and SAI is not on its list of apps that get it turned off. Not the easy win |
| Our DLL | What it does per pen point (source of `wintab32.c`) | Little: one `recv` and at most one `PostMessage(WT_PACKET)`, conflated when SAI is behind; the screen-layout check runs at most once a second. Not where the 13 wakeups per point come from |
| Cursor | How often it moves while drawing | Once per Android event, not per sample: there is no `requestUnbufferedDispatch()`, so Android batches the pen into about one event per screen frame (`PenInput`, `winlator.patch`). The pen's ~220 samples/s still all reach the DLL |

## Not checked yet, by expected value

1. **What SAI's 71 % really is.** Profile one minute of drawing (a
   profileable build plus Box64's perf map). Part of it may be Wine code
   running inside SAI's process, for example the way Wine hands the canvas
   picture to the X server, rather than the brush itself. Wine code can be
   changed; SAI's cannot.
2. **wineserver, 19 %.** While drawing it wakes about 2,900 times per second,
   roughly 13 times per pen point (about 410 per second when idle). SAI's main
   thread wakes about 2,340 times per second, about 10 per pen point: mostly
   its own wineserver round trips. esync is already on and the DLL sends little
   (see above), so the question is which calls they are: the cursor and mouse
   messages Wine makes from each X motion, SAI's own message loop, or the
   picture going out. Removing round trips changes nothing visually.
   Next step, no new build needed: Winlator settings → Wine debug on with only
   the `server` channel, logs saved to a file (`Documents/Winlator/logs.txt`),
   10 s of drawing. The log names every request with its thread, so they can be
   counted per pen point. Drawing is very slow while it is on; turn it off after.
   The log stays local: it can contain file names (see the licence rule).
3. **Picture to the screen.** Check whether the X server and renderer upload
   and redraw the whole screen when SAI only changed a small patch.

## How to measure progress

- **CPU per 1000 pen samples** while drawing by hand (read-only `/proc`
  snapshots plus `getevent` sample count). Today: about 5.2–5.5 core-seconds.
  Hand drawing is noisy, so only differences above about 10–15 % count.
- **The tail:** how long SAI keeps painting after pen-up. This is closest to
  the lag the artist feels. Today: 80–240 ms.
- **Per-thread split** while drawing: which threads of SAI, wineserver and the
  app take the time. This is the cheapest next step, read-only, and tells
  which of the three unchecked parts to dig into first. Script (local, not in
  the repo): `~/Documents/sai-android-build-idle/draw-threads.sh <label> [s]`.
  It waits for SAI and the first pen touch, then takes two read-only snapshots
  of every thread of the app: CPU, voluntary and involuntary context switches,
  thread name, last core. Results in `draw-runs/<label>.threads.txt`. Written
  on 2026-11-26, first run waiting for a drawing session.
