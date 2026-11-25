/*
 * test_android_wire.c: the DLL's half of the Android pen wire check.
 *
 * tests/fixtures/android-pen-wire.txt holds datagrams exactly as the Android
 * app builds them (PenWireTest.java proves that half), each with the Windows
 * screen point and pressure it stands for. Here the DLL's own parser and
 * mapping read them back. If the app and the DLL ever disagree on the format
 * or on which way y points, one of the two tests fails.
 *
 * Run:  ./build.sh test
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
/* wintab_core.h is all static functions; this test uses only two of them */
#pragma GCC diagnostic ignored "-Wunused-function"
#include "../third_party/wintab/wintab_core.h"

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "tests/fixtures/android-pen-wire.txt";
    FILE *f = fopen(path, "r");
    if (!f) { printf("  FAIL cannot open %s\n", path); return 1; }
    char line[256];
    int failures = 0, cases = 0;
    while (fgets(line, sizeof line, f)) {
        if (line[0] == '#' || line[0] == '\n') continue;
        char *bar = strchr(line, '|');
        if (!bar) { printf("  FAIL no '|' in: %s", line); failures++; continue; }
        *bar = '\0';
        int ex, ey, ep;
        if (sscanf(bar + 1, "%d %d %d", &ex, &ey, &ep) != 3) {
            printf("  FAIL bad expectation after '|'\n"); failures++; continue;
        }
        cases++;
        WTC_DGRAM d;
        if (!wtc_parse_datagram(line, &d)) {
            printf("  FAIL case %d: DLL refused the datagram \"%s\"\n", cases, line);
            failures++; continue;
        }
        /* The X screen IS the Windows desktop in Winlator (explorer
         * /desktop=...,WxH), so the virtual screen starts at 0,0 with the
         * datagram's own size. */
        WTC_RECT virt = { 0, 0, d.w / 8, d.h / 8 };
        WTC_SAMPLE s;
        memset(&s, 0, sizeof s);
        s.press = d.p; s.has_pos = 1; s.x = d.x; s.y = d.y; s.w = d.w; s.h = d.h;
        int32_t sx, sy;
        wtc_sample_to_screen(&s, virt, &sx, &sy);
        int ok = sx == ex && sy == ey && d.p == ep && d.tm.has;
        printf("  %s case %d: p=%d -> screen %d,%d (want %d,%d p=%d)\n",
               ok ? "ok  " : "FAIL", cases, d.p, (int)sx, (int)sy, ex, ey, ep);
        if (!ok) failures++;
    }
    fclose(f);
    if (cases == 0) { printf("  FAIL no cases in %s\n", path); failures++; }
    printf("%d android wire case(s), %d failure(s)\n", cases, failures);
    return failures ? 1 : 0;
}
