# Contributing

This is a small project maintained by one person. **Issues are welcome; pull requests are not
accepted.** If you have a fix or an idea, describe it in an issue. That is the most useful way
to help, and the change itself is made here.

SAI Pen is a free hobby project, not a paid job. I look at issues when I have free time, so
replies can take a while. **I'm currently on a break**, so it may be longer than usual.

A few things that keep it pleasant for everyone:

- **Be kind and polite.** Everyone here is a volunteer or a fellow artist.
- **Write in English.** A machine translation is fine, and you are welcome to paste your
  original text underneath it.
- **Stay on topic.** One issue per problem or idea, about SAI Pen. Questions about SAI itself,
  your licence or other apps are better asked elsewhere.

## Reports that help most

- **Other devices and pens.** SAI Pen is built and tested on Samsung devices with the S Pen
  (see [Tested on](README.md#tested-on)). Your device model, its pen, your Android version,
  and whether pressure, hover and palm rejection work: that is worth a lot, even when everything
  works.
- **Bugs.** Please give as much detail as you can. I usually can't reproduce a bug on my own
  devices, so your report is often all I have to go on. Include:
  - **SAI Pen version** (on SAI Pen's screen)
  - **SAI2 version** (Help → About in SAI), e.g. Alpha.2026.10.19
  - **Device** (exact model, e.g. Galaxy S25 or Galaxy Tab S9 FE)
  - **Android version** and, if you know it, the maker's version, e.g. One UI 8
    (Settings → About phone / About tablet → Software information)
  - **Pen** (e.g. the S Pen that came with the device, or another pen and its model)
  - **When it happens:** the exact steps that lead to it, whether it happens every time or only
    sometimes, and since when (after an update, from the first start, …)
  - **Expected vs actual:** what you expected to happen and what happened instead. An example
    helps a lot, like "brush size 25 before switching apps, back to 10 after".
  - whether SAI was still open when it went wrong, or started up again from scratch
  - what you have already tried, and whether it helped
  - a screenshot or a short screen recording, if you can
- **Pressure problems.** Do the one-minute check in
  [Check a pen in a minute, without SAI](README.md#check-a-pen-in-a-minute-without-sai) and say
  whether `Prs` changes with how hard you press, whether it starts low on the first touch or
  jumps to 1.0, and whether the crosshair follows the pen above the screen. That tells whether
  the problem is the pen itself or SAI Pen.
- **Ideas.** Open an issue saying what you would like and what problem it solves for you.

Please don't post your SAI licence, its System ID or key files in an issue.

## Building it yourself

The code is under the GNU GPL version 3, so you are free to build, change and run your own copy.
If you share it, see [Licences](README.md#licences). Building needs macOS, JDK 17, the Android SDK
(NDK 24.0.8215888, cmake 3.22.1, platform 35) and `mingw-w64`. The details are in
[Build from source (macOS)](README.md#build-from-source-macos).

```bash
# tests, then the APK into dist/
./build.sh

# the tests only (no device needed)
./build.sh test
```

Never add SAI itself, its installers or its key files to anything you share. The `.gitignore`
blocks them for a reason.
