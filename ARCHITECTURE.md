# How SAI Pen for Android works

SAI Pen runs PaintTool SAI Ver.2, a Windows program, on an Android tablet or phone, with the S Pen
as a real pen: the cursor sits under the tip, touching draws, and pressure reaches SAI.

## The parts, from the pen to SAI

```
 S Pen on the glass
   │  Android MotionEvent: x, y, pressure, tool type STYLUS, batched samples
   ▼
 TouchpadView (Winlator's touch view, patched)
   │  PenInput: the pen is a pen, not a finger
   ├─────────────► Winlator's X server: cursor under the tip, left button held while touching
   │                 │ X11 protocol
   │                 ▼
   │               Wine (x11 driver, wineserver) ──► SAI: mouse moves and the click that starts a stroke
   │
   └─ PenSampleSender: every sample as a text line over UDP to 127.0.0.1:47800
                     │
                     ▼
                   our wintab32.dll, loaded inside SAI ──► SAI: WinTab packets with x, y and pressure
```

Everything right of Android runs in one Android app: Winlator's X server and renderer are Java
and native code of the app; Wine and SAI are x86-64 Linux and Windows code, run by the Box64
emulator as child processes of the app. All of them share the app's network namespace, which is
why a loopback datagram from the app reaches the DLL inside SAI.

### The pen (`src/main/java/com/winlator/saipen/PenInput.java`)

- A `TOOL_TYPE_STYLUS` or `TOOL_TYPE_ERASER` pointer takes the pen path; fingers keep theirs.
- Pen down: cursor to the tip, then the sample, then the left button (the S Pen's side button
  held at touch-down gives the right button instead, without ink).
- Every sample goes to the DLL, including the ones Android batched into one event, so a quick
  stroke keeps its whole pressure curve.
- The Windows cursor moves once per event, to the newest sample. Each cursor move travels the X
  server, Wine and SAI, emulated; SAI takes the stroke's points from the DLL anyway.
- Hovering (pen near the glass) moves the cursor and sends pressure 0, repeated every 50 ms while
  the pen is still, or SAI loses its brush cursor.

### Fingers (`TwoFingerGesture.java`, patched `TouchpadView`)

- One finger: the cursor jumps under it; a tap clicks there.
- Two fingers moving: the canvas scrolls in any direction. They go out as X wheel buttons
  (4/5 vertical, 6/7 horizontal); the DLL turns every wheel inside SAI into SAI's own Scroll View
  arrow keys.
- Two fingers spreading or pinching: SAI's own zoom, at the point where the pinch began, with the
  DLL's control line `Z <steps>` (the one the Mac trackpad's pinch uses). One step per SAI zoom
  step (x1.41); steps that pile up go together, at most every 200 ms, because SAI redraws its
  canvas after every step, emulated. Scaling the picture on the tablet instead (0.3.0) scaled the
  whole window, panels too: only SAI knows where its canvas is.
- A two-finger gesture is a scroll or a zoom, whichever starts first.

### The wire to the DLL (`PenWire.java`, `PenSampleSender.java`)

One UDP datagram per sample, the same text line the Mac helper sends:
`seq p x y w h ev tx n evok`. `p` is 0 for hover and 1..4095 while touching; `x/w`, `y/h` are
the point as a fraction of the Windows desktop with y counted up. The DLL is told the full scale
(4095) through `C:\wt_pmax.txt`. The address is `127.0.0.1` written out: on Android
`InetAddress.getLoopbackAddress()` is `::1`, which the DLL (IPv4 only) never hears.

`tests/fixtures/android-pen-wire.txt` pins the format on both sides: a JVM test checks the app
writes these exact lines, a C test checks the DLL's own parser reads them back.

## What the emulator runs with

| | Value | Where it comes from |
|---|---|---|
| Base | Winlator 11.2, commit `ecd5ab2` | `UPSTREAM` |
| Wine | 10.10, x86-64 | Winlator's `rootfs.tzst` |
| x86 emulator | Box64 0.4.4, preset Performance | Winlator default (`Box64Preset.DEFAULT`) |
| Box64 preset in numbers | `SAFEFLAGS=1 FASTNAN=1 FASTROUND=1 X87DOUBLE=0 BIGBLOCK=3 STRONGMEM=0 FORWARD=512 CALLRET=1 WAIT=1 NATIVEFLAGS=1 WEAKBARRIER=2` | `Box64PresetManager` |
| Wine screen | the whole display's proportions; 720, 900 (default) or the display's own height, chosen as Sharpness on the app's screen (1500x900 on the Galaxy Tab S6 Lite) | `SaiLocator.wineScreenSize`, set at every start |
| Wine desktop | `nogui`: no taskbar, no Start menu | Winlator, for a session started with a program |
| Drives | `S:` SAI's folder, `D:` Download, `E:` the app's own storage | `SaiLocator.drives` |
| Graphics | Winlator's choice for the device (Turnip on Adreno); SAI draws with GDI, not 3D | container |
| CPU cores | all | container |
| `WINEDEBUG` | `-all` (Wine's log off) | Winlator |

Before every session (`SaiPenSetup`, launcher):

- `wintab32.dll` from the APK goes to `C:\windows\system32\` and
  `HKCU\Software\Wine\DllOverrides` gets `wintab32=native,builtin`, so Wine loads ours, not its own;
- `C:\wt_pmax.txt` says 4095;
- a SAI licence certificate (`sai*.slc` in Downloads or SAI's folder, or one picked on the screen)
  goes to `C:\users\xuser\Documents\SYSTEMAX Software Development\SAIv2`, where SAI Ver.2 reads it;
- `C:\users\xuser\SAI Drawings` and `C:\users\xuser\Downloads` become links to the tablet's
  `Documents/SAI Drawings` and `Download` (`PrefixLinks`). SAI's save dialog opens in
  `C:\users\xuser`, which is inside the app, out of reach of Android's file managers. SAI2's own
  file browser ("Always Use FileViewer") starts there and keeps its last folder in SAI's
  encrypted settings, so it cannot be pointed at SAI Drawings; Wine's remembered folder
  (`ComDlg32\LastVisitedMRU`) only reaches Windows' standard dialog;
- drawings (SAI's file types) lying right in `C:\users\xuser`, `Desktop`, `Documents`,
  `Pictures` (and `SAI Drawings` or `Downloads` if they are real folders, not links) move to
  `Documents/SAI Drawings` (`DeviceDrawings`), nothing left in their place; also done when SAI's
  process has gone (`SaiSession`). A taken name gives `name (2)`, nothing on the device is
  overwritten. A link to the single file was tried: SAI opened through it but could not save
  again ("Invalid parameter"), while saving into the linked folder works; such links are removed;
- `C:\users\xuser\Documents\SYSTEMAX Software Development\SAIv2\settings` (brushes, textures,
  presets) becomes a link to the same path under the tablet's `Documents`, so a PC's settings
  folder can be copied in. A folder SAI filled inside the app before 0.4.21 is copied out first
  and kept as `SAIv2\settings-inside-the-app`; a folder already on the tablet wins
  (`PrefixLinks.moveIntoAndLink`). The licence stays inside the app. SAI Pen's screen links it
  too (`SaiPenSetup.linkSettings`) before it counts what SAI has or adds to it: `BrushImport`
  sorts a pack's `.bmp`/`.ini` files into SAI's folders by their folder names, and swaps in a
  whole settings folder (the one with `settings2.ssd`) with the old one kept beside it.

## Starting

`SaiLauncherActivity` is the only screen.

1. First run: files permission, then Winlator unpacks Wine (`RootFSInstaller`), then one Wine
   container is made.
2. SAI is looked for on the device: a folder with `sai2.exe`, `sai2.ini` and `init` (Download,
   Telegram's folders, Documents, `Android/media`). A `sai2*.zip` can be unpacked from the screen.
3. When SAI is found, opening the app starts SAI right away (`XServerDisplayActivity` with
   `exec_path`). The very first time without a SAI licence it asks once: choose the file, open
   the SYSTEMAX site, or later. The screen shows only when something is missing, and after SAI
   exits.
4. "Starting SAI" covers the session until SAI's window is up and has stopped drawing
   (`SaiStartup`, `StartupCover`), never longer than 12 s after the window appears. Its bar
   fills by an estimate (to 80 % until SAI's window, then to 100 %) and it says what is happening
   and for how many seconds.

While SAI runs, Winlator's menu (the back gesture) has "SAI Pen: licence and settings", which
closes SAI and opens the app's screen. Winlator's own screens (containers, settings) stay behind
the small "Winlator settings" link there.

## Building

`build.sh` clones Winlator at `UPSTREAM`, applies `patches/winlator.patch`, adds `src/` and
the DLL, and builds with Gradle.

The package name is `sai.penpress` on purpose: exactly as long as `com.winlator`. Winlator's
prebuilt Linux files have `/data/data/com.winlator/` compiled into about 170 binaries (the dynamic
loader, libc, Wine, Box64). `tools/rewrite_tar.py` rewrites that path byte for byte inside the
archives, which only works when the new path has the same length. `build.sh` checks that nothing
of the old path is left.

## When something is wrong

| File (inside Wine) | Written by | Says |
|---|---|---|
| `C:\wt_status.txt` | the DLL, every second | its build, `open=1` once SAI opened the tablet, `recv=` samples received |
| `C:\saipen_status.txt` | the app, every second | `sent=` samples sent, `failed=` and the last send error |

`sent` growing while `recv` stays 0 means the samples do not reach the DLL; `recv` growing
without pressure in SAI means the problem is further on, in SAI.

Over USB (`adb`, the APK is debuggable):

```bash
adb shell run-as sai.penpress cat files/rootfs/home/xuser-1/.wine/drive_c/wt_status.txt
```
