# What SAI Pen for Android is built from

SAI Pen is a changed version of Winlator, with our own pen code and our own `wintab32.dll`. This
file lists every part inside the APK, its licence and where its source is. It was checked file by
file against the APK of version 0.4.12 in November 2026. The full licence texts and copyright
notices are in `LICENSES.txt`, which the app shows under "Licences".

PaintTool SAI itself is **not** included: it is proprietary software by SYSTEMAX Software
Development. Each user downloads it from <https://www.systemax.jp/en/sai/> and uses their own
licence certificate.

## Our code

| Part | Licence | Source |
|---|---|---|
| SAI Pen for Android (`src/`, `patches/`, `tools/`, `build.sh`) | GNU GPL 3 with additional terms (`NOTICE`) | this repository |
| App icon (`src/main/res/mipmap-*`, drawn by `tools/make-icon.swift`) | Flaticon free licence, with attribution: "Pen" by gravisio ([Drawing icons created by gravisio - Flaticon](https://www.flaticon.com/free-icons/drawing)); not under the GPL | <https://www.flaticon.com/free-icon/pen_11502485>, 512 px PNG; not kept in this repository, since the licence does not let the picture be passed on by itself |
| `winhandler.exe` (`winhandler/`), in place of Winlator's | GNU GPL 3 with the same additional terms | this repository; written from the protocol in Winlator's `WinHandler.java`, built with MinGW-w64 by `build.sh` |
| `wintab32.dll` (`third_party/wintab/`) | GNU GPL 3 with the same additional terms | [SAI Pen Pressure for macOS](https://github.com/ametrien/Paint-Tool-SAI-pen-pressure-macOS-fix), commit `c1c5e36`, `wintab-src/`; built with MinGW-w64, whose runtime is linked in (its notices are in `LICENSES.txt`) |

## The Android app it is built on

| Part | Version | Licence | Source |
|---|---|---|---|
| Winlator (app, X server, the host side of the Vortek, Gladio and VirGL renderers, libadrenotools, MIDI handler) | 11.2, `ecd5ab2` | LGPL-2.1 | <https://github.com/brunodev85/winlator-app> |

The combined app is conveyed under GPL 3, which LGPL-2.1 section 3 allows. It was changed from
Winlator 11.2 in November 2026; the changes to Winlator's own files are in
`patches/winlator.patch`, and `build.sh` rebuilds the APK from Winlator's source and that patch.

## Inside Winlator's archives (assets)

Winlator ships these already built, in `.tzst` archives. `build.sh` changes them in two ways only:
it rewrites the data folder path `/data/data/com.winlator/` to `/data/data/sai.penpress/` inside
them (`tools/rewrite_tar.py`), and it leaves out files we may not pass on (see "Left out" below).

Built by Winlator's author, with the source in their own repositories. The commit given is the
last one before Winlator 11.2 (14 November 2026); which commit each file was built from exactly
is not published.

| Part | Version | Licence | Source |
|---|---|---|---|
| Wine (`opt/wine`, the Wine prefix in `container_pattern.tzst`, Wine's fonts) | 10.10, Winlator's build | LGPL-2.1 or later; bundled libraries keep their own licences (Wine's `libs/`) | <https://github.com/brunodev85/wine-10.10-custom>, `2b9afc38d953` |
| Box64 | 0.4.4 | MIT | <https://github.com/brunodev85/box64>, `bae5312b8b44` (fork of <https://github.com/ptitSeb/box64>) |
| Mesa: Turnip, Zink, VirGL drivers | 26.2.0, 22.2.5, 23.1.9 | MIT | <https://github.com/brunodev85/mesa3d-custom>, `fa711f80c79a` |
| Vortek, guest side (`libvulkan_vortek.so`) | 2.1 | LGPL-2.1 | <https://github.com/brunodev85/vortek>, `a7746fbd7288` |
| Gladio, guest side (`libGL.so` in `gladio-1.1.tzst`) | 1.1 | LGPL-2.1 | <https://github.com/brunodev85/gladio>, `3861b0a6ed29` |
| ALSA plugin `libasound_module_pcm_android_aserver.so`, glibc patches | | LGPL-2.1 | <https://github.com/brunodev85/winlator>, `30aafbdb9eea`, folders `android_alsa/` and `glibc_patches/` |

Upstream projects, built by Winlator's author from their releases (build scripts not
published). Versions are read from the files themselves.

| Part | Version | Licence | Source |
|---|---|---|---|
| GNU C Library, with Termux's patches (`ld-linux`, `libc` and the rest, `gconv`, locales, `locale`, `localedef`) | 2.39 | LGPL-2.1 or later; the programs GPL-2.0 or later | <https://www.gnu.org/software/libc/>; Termux's patches: <https://github.com/termux-pacman/glibc-packages>, `5f6b1f58d2ab`, the last commit with glibc 2.39 (which one was used is not published) |
| GCC runtime (`libgcc_s`, also the x86-64 one, `libstdc++`) | 13.2.0 | GPL-3.0 or later with the GCC Runtime Library Exception | <https://gcc.gnu.org> |
| GStreamer: core, base, good, bad, ugly, libav plugins | 1.25.1 | LGPL-2.1 or later; the `mpeg2dec` plugin is GPL through libmpeg2 | <https://gstreamer.freedesktop.org> |
| FFmpeg, built into `libgstlibav.so` | 7.1, built as LGPL | LGPL-2.1 or later | GStreamer's meson port of FFmpeg (GStreamer 1.25.1's `subprojects/FFmpeg.wrap`): <https://gitlab.freedesktop.org/gstreamer/meson-ports/ffmpeg>, `09b728533508` |
| GLib | 2.82.2 | LGPL-2.1 or later | <https://gitlab.gnome.org/GNOME/glib> |
| libmpeg2 | 0.5.1 | GPL-2.0 or later | <https://libmpeg2.sourceforge.io> |
| mpg123 (`libmpg123`, `libout123`, `libsyn123`) | 1.32.7 | LGPL-2.1 | <https://www.mpg123.de> |
| alsa-lib | 1.2.13 | LGPL-2.1 or later | <https://www.alsa-project.org> |
| PulseAudio client libraries, libsndfile | 17.0, 1.2.2 | LGPL-2.1 or later | <https://www.freedesktop.org/wiki/Software/PulseAudio/>, <https://libsndfile.github.io/libsndfile/> |
| GnuTLS | 3.8.5 | LGPL-2.1 or later (`libgnutls-openssl`: GPL-3.0 or later) | <https://gnutls.org> |
| Nettle (`libnettle`, `libhogweed`) | 3.10 | LGPL-3.0 or later, or GPL-2.0 or later | <https://www.lysator.liu.se/~nisse/nettle/> |
| MIT Kerberos (`libkrb5` and the rest) | | MIT Kerberos licence (BSD-like) | <https://web.mit.edu/kerberos/> |
| Vulkan loader | 1.3.301 | Apache-2.0 | <https://github.com/KhronosGroup/Vulkan-Loader> |
| libjpeg-turbo | 3.0.4 | IJG, BSD-3-Clause, zlib | <https://libjpeg-turbo.org> |
| FreeType | | FreeType License | <https://freetype.org> |
| fontconfig, expat, libffi, libdrm, X11 and xcb libraries, libverto | | MIT and similar | their upstream projects |
| libpng, zlib, bzip2, brotli, zstd, libogg | | libpng, zlib, bzip2, MIT, BSD-3-Clause, BSD-3-Clause | their upstream projects |
| DXVK, D8VK, D7VK | 2.4.1 and 1.10.3, 1.0, 1.11 | zlib | <https://github.com/doitsujin/dxvk>, <https://github.com/AlpyneDreams/d8vk>, <https://github.com/WinterSnowfall/d7vk> |
| VKD3D-Proton | 2.14.1 | LGPL-2.1 or later | <https://github.com/HansKristian-Work/vkd3d-proton> |
| cnc-ddraw, with its shaders | 6.6 | MIT | <https://github.com/FunkyFr3sh/cnc-ddraw> |
| DMZ cursor theme (`usr/share/icons/dmz`) | | CC BY-SA 3.0, Novell, Inc. | Novell's DMZ theme, packaged as `dmz-cursor-theme` in Linux distributions |
| SONiVOX EAS GM wavetable (sound font) | | Apache-2.0 | Android Open Source Project |

## Native libraries of the app (`lib/arm64-v8a`)

| Part | Version | Licence | Source |
|---|---|---|---|
| `libwinlator`, `libvortekrenderer`, `libgladiorenderer`, `libvirglrenderer`, `libmidihandler` | | LGPL-2.1 (VirGL renderer: MIT) | built from Winlator's source above |
| libadrenotools and its hooks (`libhook_impl`, `libmain_hook`, `libfile_redirect_hook`, `libgsl_alloc_hook`), linkernsbypass | | BSD-2-Clause | built from Winlator's source, <https://github.com/bylaws/libadrenotools> |
| FluidSynth, libinstpatch, GLib (with GNU libiconv and gettext's libintl built in), libsndfile | 2.4.5, 1.1.6, 2.72.4 (libiconv 1.17, gettext 0.22.5), 1.2.2 | LGPL-2.1 or later | FluidSynth's own Android build, <https://github.com/FluidSynth/fluidsynth>; versions from its `.azure/azure-pipelines-android.yml` at v2.4.5 |
| FLAC, Ogg, Vorbis, Opus, PCRE | 1.4.3, Opus 1.5.2 | BSD-3-Clause | the same build |
| Oboe | 1.9.0 | Apache-2.0 | <https://github.com/google/oboe> |
| PulseAudio (`libpulse`, `libpulsecommon`, `libpulsecore`, the server as `libpulseaudio.so`, its modules in `pulseaudio.tzst`), libsndfile, libltdl | 13.0, 1.0.28 | LGPL-2.1 or later | <https://github.com/brunodev85/pulseaudio-android>, `77c8a7217c57` (its `pulseaudio/` submodule is PulseAudio's tag v13.0) |
| LLVM libc++ (`libc++_shared`), OpenMP runtime (`libomp`) | | Apache-2.0 with LLVM exception | Android NDK, <https://llvm.org> |
| zstd-jni | 1.5.2-3 | BSD-2-Clause | <https://github.com/luben/zstd-jni> |

## Java and Kotlin libraries (in `classes.dex`)

| Part | Licence | Source |
|---|---|---|
| AndroidX, Material Components | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| Kotlin standard library, kotlinx.coroutines (pulled in by AndroidX) | Apache-2.0 | <https://kotlinlang.org> |
| Apache Commons Compress, IO, Lang3, Codec | Apache-2.0 (with their NOTICE files) | <https://commons.apache.org> |
| XZ for Java | 0BSD (public domain) | <https://tukaani.org/xz/java.html> |

## Source code

Every release on GitHub carries, next to the APK, the source archive of each part above that is
under the GPL or the LGPL: 31 archives (about 390 MB) and a `SHA256SUMS` file. Parts under MIT,
BSD, zlib, Apache and similar licences (Box64, Mesa and the others) need no source next to the
APK; the tables above give where their source is. The GPL 2 and the LGPL 2.1 count source offered from the same place as the
program as given with it. `third_party/sources.tsv` lists each archive with its URL and SHA-256,
and `tools/fetch-sources.py` downloads and checks them all. The source of SAI Pen itself is this
repository at the release's tag; `build.sh` builds the APK from it and Winlator 11.2.

## Left out

`build.sh` removes these from Winlator's archives, and the build fails if any of them is found in
the APK:

- **Microsoft's files.** Winlator's "Windows components" (DirectX parts, Visual C++ 2005 and 2010
  runtimes, Windows Media decoder, XAudio), 31 Microsoft fonts and MFC 4.2 (`mfc42.dll`). Their
  licences do not let us pass them on.
- **The Fraunhofer FDK AAC codec** (`libfdk_aac.so`, `libgstfdkaac.so`). Its licence is
  incompatible with the GPL and allows use only with a patent licence for AAC.
- **Winlator's programs SAI does not use:** 7-Zip 19.00 (`opt/apps/7-Zip`), the Winlator File
  Manager (`wfm.exe`) with its `libcdio.dll`, `winaddons.exe`, `TestD3D.exe`, `GPUInfo.exe` and
  `winpreexec`. Only Winlator's Start menu and file manager start them, and SAI Pen's Wine
  desktop has neither. Each would need its source next to the APK, and `winaddons.exe`,
  `TestD3D.exe`, `GPUInfo.exe` and `winpreexec` have none published.
- **Winlator's `winhandler.exe`.** Winlator starts every program through it, but its source is
  not published. SAI Pen ships its own instead (`winhandler/`, see "Our code").
