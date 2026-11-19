/*
 * Tests for winhandler/protocol.h: the app's requests are built here byte by
 * byte the way WinHandler.java builds them (a little-endian ByteBuffer of 256
 * bytes), and our answers are read back the way WinHandler.handleRequest reads
 * them. Runs on the Mac: ./build.sh builds it with the sanitizers.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms (GPL 3, section 7): see NOTICE
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../winhandler/protocol.h"

static int failures, cases;

#define CHECK(cond, what) do { cases++; if (cond) printf("  ok   %s\n", what); \
    else { failures++; printf("  FAIL %s (%s:%d)\n", what, __FILE__, __LINE__); } } while (0)

/* a Java-style request: 256 bytes, filled as ByteBuffer.put* would */
struct req { unsigned char d[256]; int pos; };

static void r_start(struct req *r, int code) { memset(r->d, 0xAA, sizeof(r->d)); r->d[0] = (unsigned char)code; r->pos = 1; }
static void r_u8(struct req *r, int v) { r->d[r->pos++] = (unsigned char)v; }
static void r_i16(struct req *r, int v) { wh_put_i16(r->d + r->pos, (int16_t)v); r->pos += 2; }
static void r_i32(struct req *r, int32_t v) { wh_put_i32(r->d + r->pos, v); r->pos += 4; }
static void r_i64(struct req *r, int64_t v) { wh_put_i64(r->d + r->pos, v); r->pos += 8; }
static void r_bytes(struct req *r, const char *s) { int n = (int)strlen(s); r_i32(r, n); memcpy(r->d + r->pos, s, (size_t)n); r->pos += n; }

static struct wh_reader reader(struct req *r) { struct wh_reader w = { r->d, 256, 1 }; return w; }

/* reading back as Java's little-endian ByteBuffer does */
static int j_i16(const unsigned char *p) { return (int16_t)(p[0] | p[1] << 8); }
static int32_t j_i32(const unsigned char *p) { return (int32_t)((uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24); }
static int64_t j_i64(const unsigned char *p) { uint64_t v = 0; for (int i = 7; i >= 0; i--) v = v << 8 | p[i]; return (int64_t)v; }

static void test_exec(void)
{
    /* WinHandler.exec(filename, parameters) */
    struct req q; r_start(&q, WH_EXEC); r_bytes(&q, "C:\\SAI2\\sai2.exe"); r_bytes(&q, "--x \"a b\"");
    struct wh_reader r = reader(&q);
    int fn, an;
    const char *f = wh_read_bytes(&r, &fn), *a = wh_read_bytes(&r, &an);
    CHECK(f && fn == 16 && memcmp(f, "C:\\SAI2\\sai2.exe", 16) == 0, "EXEC: the file");
    CHECK(a && an == 9 && memcmp(a, "--x \"a b\"", 9) == 0, "EXEC: the arguments");

    /* parameters == null: putInt(0) */
    r_start(&q, WH_EXEC); r_bytes(&q, "notepad.exe"); r_i32(&q, 0);
    r = reader(&q);
    f = wh_read_bytes(&r, &fn); a = wh_read_bytes(&r, &an);
    CHECK(f && fn == 11 && a && an == 0, "EXEC: no arguments");
}

static void test_bad_lengths(void)
{
    struct req q; r_start(&q, WH_EXEC); r_i32(&q, 300);   /* runs past 256 */
    struct wh_reader r = reader(&q);
    int n = 7;
    CHECK(wh_read_bytes(&r, &n) == NULL && n == 0, "a length past the datagram is refused");
    CHECK(wh_read_i32(&r) == 0 && r.pos > r.len, "and everything after it reads as nothing");

    r_start(&q, WH_EXEC); r_i32(&q, -5);
    r = reader(&q);
    CHECK(wh_read_bytes(&r, &n) == NULL, "a negative length is refused");

    unsigned char tiny[3] = { WH_KILL_PROCESS, 1, 0 };
    struct wh_reader t = { tiny, 3, 1 };
    CHECK(wh_read_bytes(&t, &n) == NULL, "a datagram too short for its length is refused");
}

static void test_kill(void)
{
    /* killProcess(name, pid): name may be null -> putInt(0) */
    struct req q; r_start(&q, WH_KILL_PROCESS); r_i32(&q, 0); r_i32(&q, 4242);
    struct wh_reader r = reader(&q);
    int n; const char *s = wh_read_bytes(&r, &n);
    CHECK(s && n == 0 && wh_read_i32(&r) == 4242, "KILL_PROCESS by pid");

    r_start(&q, WH_KILL_PROCESS); r_bytes(&q, "sai2.exe"); r_i32(&q, 0);
    r = reader(&q);
    s = wh_read_bytes(&r, &n);
    char name[32]; wh_copy_string(name, sizeof(name), s, n);
    CHECK(strcmp(name, "sai2.exe") == 0 && wh_read_i32(&r) == 0, "KILL_PROCESS by name");
}

static void test_affinity(void)
{
    /* setProcessAffinity(pid, mask): putInt(9), pid, mask, (byte)0 */
    struct req q; r_start(&q, WH_SET_PROCESS_AFFINITY); r_i32(&q, 9); r_i32(&q, 77); r_i32(&q, 0xC0); r_u8(&q, 0);
    struct wh_reader r = reader(&q);
    CHECK(wh_read_i32(&r) == 9 && wh_read_i32(&r) == 77 && wh_read_i32(&r) == 0xC0 && wh_read_u8(&r) == 0,
          "SET_PROCESS_AFFINITY by pid");

    /* setProcessAffinity(name, mask): putInt(9 + n), 0, mask, (byte)n, name */
    r_start(&q, WH_SET_PROCESS_AFFINITY); r_i32(&q, 9 + 8); r_i32(&q, 0); r_i32(&q, 0xFF); r_u8(&q, 8);
    memcpy(q.d + q.pos, "sai2.exe", 8); q.pos += 8;
    r = reader(&q);
    wh_read_i32(&r);
    int32_t pid = wh_read_i32(&r); uint32_t mask = (uint32_t)wh_read_i32(&r); int n = wh_read_u8(&r);
    char name[32]; wh_copy_string(name, sizeof(name), (const char *)q.d + r.pos, n);
    CHECK(pid == 0 && mask == 0xFF && n == 8 && strcmp(name, "sai2.exe") == 0, "SET_PROCESS_AFFINITY by name");
}

static void test_mouse(void)
{
    /* mouseEvent(flags, dx, dy, wheel): putInt(10), flags, short dx, short dy, short wheel, byte feedback */
    struct req q; r_start(&q, WH_MOUSE_EVENT); r_i32(&q, 10); r_i32(&q, 0x0001); r_i16(&q, -12); r_i16(&q, 34); r_i16(&q, 0); r_u8(&q, 1);
    struct wh_reader r = reader(&q);
    struct wh_mouse m;
    CHECK(wh_read_mouse(&r, &m) && m.flags == 1 && m.dx == -12 && m.dy == 34 && m.feedback == 1,
          "MOUSE_EVENT: a move, with the cursor asked back");

    r_start(&q, WH_MOUSE_EVENT); r_i32(&q, 10); r_i32(&q, 0x0800); r_i16(&q, 0); r_i16(&q, 0); r_i16(&q, -120); r_u8(&q, 0);
    r = reader(&q);
    CHECK(wh_read_mouse(&r, &m) && m.flags == 0x0800 && m.wheel == -120 && m.feedback == 0,
          "MOUSE_EVENT: the wheel, down a notch");

    r_start(&q, WH_MOUSE_EVENT); r_i32(&q, 6);
    r = reader(&q);
    CHECK(!wh_read_mouse(&r, &m), "MOUSE_EVENT with another length is refused");
}

static void test_bring_to_front_and_window(void)
{
    /* bringToFront(name, handle): int n, name, long handle */
    struct req q; r_start(&q, WH_BRING_TO_FRONT); r_bytes(&q, "sai2.exe"); r_i64(&q, 0x10044);
    struct wh_reader r = reader(&q);
    int n; const char *s = wh_read_bytes(&r, &n);
    CHECK(s && n == 8 && wh_read_i64(&r) == 0x10044, "BRING_TO_FRONT: class and window handle");

    /* showWindow(handle, nCmdShow): long, int */
    r_start(&q, WH_SHOW_WINDOW); r_i64(&q, 0x20066); r_i32(&q, 9);
    r = reader(&q);
    CHECK(wh_read_i64(&r) == 0x20066 && wh_read_i32(&r) == 9, "SHOW_WINDOW: handle and SW_RESTORE");
}

static void test_answers(void)
{
    unsigned char c[5];
    wh_pack_cursor(c, 1919, -3);
    CHECK(c[0] == WH_CURSOR_POS_FEEDBACK && j_i16(c + 1) == 1919 && j_i16(c + 3) == -3,
          "CURSOR_POS_FEEDBACK reads back as the app reads it");

    unsigned char h[5];
    wh_pack_header(h, WH_GET_EXECUTABLE_PATH, 38);
    CHECK(h[0] == WH_GET_EXECUTABLE_PATH && j_i32(h + 1) == 38, "GET_EXECUTABLE_PATH header: the length");

    struct wh_process p = { .pid = 312, .memory = 123456789012ULL, .affinity = 0xC0, .wow64 = 1 };
    memset(p.name, 0, sizeof(p.name));
    strcpy(p.name, "sai2.exe");
    unsigned char g[WH_PROCESS_SIZE];
    wh_pack_process(g, 7, 2, &p);
    /* WinHandler: position + 4, getShort count, getShort index, getInt pid,
     * getLong memory, getInt affinity, get() == 1, 32 bytes name */
    const unsigned char *x = g + 1 + 4;
    CHECK(g[0] == WH_GET_PROCESS && j_i16(x) == 7 && j_i16(x + 2) == 2, "GET_PROCESS: count and index");
    CHECK(j_i32(x + 4) == 312 && j_i64(x + 8) == 123456789012LL && j_i32(x + 16) == 0xC0 && x[20] == 1,
          "GET_PROCESS: pid, memory, affinity, WoW64");
    CHECK(memcmp(x + 21, "sai2.exe\0", 9) == 0 && WH_PROCESS_SIZE <= 64, "GET_PROCESS: the name, inside the app's 64-byte buffer");
}

static void test_copy_string(void)
{
    char s[4];
    wh_copy_string(s, sizeof(s), "sai2.exe", 8);
    CHECK(strcmp(s, "sai") == 0, "a long name is cut to fit, still 0-terminated");
    wh_copy_string(s, sizeof(s), "x", -1);
    CHECK(s[0] == 0, "a negative length gives an empty string");
}

int main(void)
{
    test_exec();
    test_bad_lengths();
    test_kill();
    test_affinity();
    test_mouse();
    test_bring_to_front_and_window();
    test_answers();
    test_copy_string();
    printf("%d winhandler protocol case(s), %d failure(s)\n", cases, failures);
    return failures ? 1 : 0;
}
