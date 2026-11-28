# Making SAI draw faster: what we tried

SAI Pen runs PaintTool SAI Ver.2, a Windows program for PC processors, on Android through Wine and
Box64 (which translates SAI's x86-64 code for ARM while it runs). The ink trails the pen a little,
mostly because SAI's own brush code needs more time when it is translated. This page lists what
was tried to make it faster, what helped, and what did not, so nobody has to try the same things
again.

Rule for every change: **nothing may look worse for the artist.** The ink on the screen is always
SAI's own drawing (no temporary "wet ink" line drawn by the app), and the cursor keeps moving once
per pen event.

Most measurements were made on a budget tablet with 2 fast and 6 slow cores, by drawing the same
strokes over adb and reading SAI's processor time from `/proc`. Hand-drawn runs are noisy: only
differences above about 10–15 % count.

## Where the time goes

| Part | Share while drawing | Note |
|---|---|---|
| SAI's brush threads (SAI's own code, translated by Box64) | about half | the ink trails the pen because these cannot keep up; after the pen is lifted SAI still paints for 80–250 ms |
| The app itself (X server, putting the picture on screen) | about a quarter | runs natively, on the slow cores |
| SAI's main thread (windows, messages, Wine inside SAI) | about a sixth | |
| wineserver (Wine's go-between process) | about a tenth | also translated by Box64 |

Android itself adds about 26 ms after the app's frame (its queue and compositor), and the pen
input 2–6 ms.

## What helped

| Change | Since | Gain |
|---|---|---|
| SAI sees only the fast cores (*Processors SAI sees*, or `BOX64_MAXCPU`) | 0.4.35 | the biggest one: about 30 % less processor per stroke, and SAI finishes a stroke much sooner |
| SAI's drawing threads on the fast cores (*Fast processor cores: Drawing*) | before 0.4.37 | Off needs about twice the processor |
| The app's own threads on the slow cores | before 0.4.37 | leaves both fast cores to SAI |
| Only the changed part of SAI's window goes to the graphics chip, not the whole 2000 × 1200 window | before 0.4.37 | about 3 ms a frame less |
| winhandler no longer looks for SAI's window 10 times a second | before 0.4.37 | together with the previous row, clearly less processor |
| Front buffer (*Screen drawing*): the picture goes straight to the screen instead of waiting in Android's queue | 0.4.29 | the ink shows sooner |
| Our wintab32.dll answers SAI's window-property lookups itself and filters messages without a Wine hook | 0.5.0 | SAI's main thread −21 %, wineserver −33 %, whole app −7 % |
| Our DLL's pen thread sleeps when the pen is idle | 0.4.39 | idle: whole app 29 % → 21 % of a core |
| Front buffer copies only the changed piece of the screen (no stripes from the brush circle on slow graphics chips) | next release | makes Front buffer usable on weak devices |

## What did not help

| Tried | Result |
|---|---|
| Box64 flags: `SAFEFLAGS=0`, `AVX=0`, `CALLRET=2`, `DYNACACHE=1` | SAI's processor time the same within 2 % |
| `WINE_CPU_TOPOLOGY` | not in the Wine version SAI Pen uses |
| Each of SAI's two heavy threads pinned to its own fast core | no gain |
| The app's threads moved to the slow cores (an earlier variant) | frames took longer |
| Android performance hints (ADPF) | not supported on the test tablet |
| Higher priority for SAI | a few ms at most, not proven |
| Timelapse off | 0–7 %, within noise: the timelapse is not the cause |
| Samsung Game Booster, heat | not limiting: the fast cores ran at full speed |
| Fewer cursor moves while drawing | no processor saved, and it would look worse: dropped |
| A temporary "wet ink" line under the pen | rejected: it cannot match SAI's brushes, colours and textures |

## Wine on ARM (Arm64EC + FEX), tried 2026-11-28

In SAI Pen all of Wine, wineserver included, is x86-64 code translated by Box64. Some other Winlator
builds run Wine itself natively on ARM (Arm64EC) and translate only the Windows program, with FEX
instead of Box64. If that were faster, SAI Pen could move to it.

Test: Winlator Bionic (a Winlator fork) with Proton 11.0 arm64ec and FEX 2611, SAI only on the two
fast cores, SAI Pen's wintab32.dll for pressure (it needed `WINEDLLOVERRIDES=wintab32=n,b` and the
X11 display server) and a small script that sent the pen samples to the DLL. Then one minute of
drawing by hand with the same brush (Brush, size 300, 1024 × 1024 canvas), compared with SAI Pen
0.5.2 on **Standard** screen drawing (no front buffer):

| | SAI's processor time per minute | Whole app per minute | To the eye |
|---|---|---|---|
| Wine on ARM + FEX | 10.4 core-seconds | 27.6 core-seconds | much slower |
| SAI Pen 0.5.2 (Wine x86-64 + Box64) | 11.7 core-seconds | 22.0 core-seconds | as usual |

- SAI's own brush costs about the same under FEX as under Box64.
- The whole app costs more there, although Wine runs natively: its way of putting the picture on
  the screen is heavier than SAI Pen's.
- It looked much slower. So moving SAI Pen to Arm64EC + FEX would not bring the ink closer to the
  pen. **Not pursued.** It may be worth another look with a later FEX.

## What is left

- **A profile** of where SAI's brush time goes (a profileable build with Box64's perf map and
  `simpleperf`). Never done yet. If part of it is Wine code inside SAI or Box64's own overhead,
  that can be changed; SAI's own code cannot.
- **Newer Box64.** The Box64 authors plan faster SSE translation on ARM64 for a later release.
- **wineserver built natively for ARM** in place of the translated one: maybe 6–9 % less processor
  in total, but hardly any change under the pen.
- After that, the limit is the device: more fast cores and a faster processor lag less.
