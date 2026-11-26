# Idle wait in the pen DLL: tablet test

Tested on 2026-11-26 on a Galaxy Tab S6 Lite (SM-P613) with SAI Pen 0.4.38.
The two builds differ only in `wintab32.dll`.

## What changed

Before this change, the DLL's pen thread woke every 15 ms, even with SAI open
and nothing happening. Each wake-up runs Wine socket code and makes wineserver
round trips, all emulated under Box64.

Now the thread wakes every 15 ms only while the pen is in use, for 2 s after
the pen goes quiet, and while something is waiting for the tick. Otherwise it
wakes every 250 ms. A pen sample still wakes the thread at once. The number of
pen packets did not change; only the empty wake-ups are gone.

## Idle: SAI open, 60 s, no pen or touch input

| | Old DLL | New DLL |
|---|---|---|
| Pen thread wake-ups/s | 241 | 18 |
| SAI (sai2.exe) | 9.6 % of a core | 6.8 % |
| wineserver | 11.0 % | 6.5 % |
| Whole app | **29.0 %** | **21.2 %** |

## Drawing: 60 s of drawing by hand

Brushes and pace were mixed and differed a little between the two runs.

| | Old DLL | New DLL |
|---|---|---|
| Pen samples/s | 208 | 223 |
| Pen thread wake-ups/s | 545 | 553 |
| SAI (sai2.exe) | 69.9 % of a core | 70.8 % |
| wineserver | 19.4 % | 19.3 % |
| Whole app | 113.9 % | 115.5 % |
| **Core-seconds per 1000 pen samples** | **5.47** | **5.18** |

The 5 % gap is within the noise of hand drawing.

## Result

- **Idle:** the whole app uses about a quarter less CPU (29 % → 21 % of a
  core). That means less heat and battery drain while SAI is just open.
- **Drawing:** no change. While the pen is in use the new DLL behaves exactly
  like the old one, so drawing is neither faster nor slower, and lag is the
  same.
- While drawing, the CPU goes to SAI itself: its main thread wakes about
  2,400 times per second. Further speed work for drawing should look there.

## How it was measured

Measurements were read-only: two snapshots of `/proc/<pid>/stat` (CPU ticks)
and `/proc/<pid>/task/*/status` (voluntary context switches = wake-ups)
taken 60 s apart for every process of the app. Pen samples were counted with
`getevent` on the pen device (one sample per `SYN_REPORT`). No input events
were injected.
