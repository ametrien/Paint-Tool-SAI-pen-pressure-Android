/*
 * The wire between winhandler.exe and the app (WinHandler.java): reading the
 * app's requests and packing our answers. Plain C, no Windows calls, so
 * tests/test_winhandler_protocol.c runs it on any machine. winhandler.c
 * describes every message.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms (GPL 3, section 7): see NOTICE
 */
#ifndef WH_PROTOCOL_H
#define WH_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>

#define WH_CLIENT_PORT 7946   /* we listen here (WinHandler.CLIENT_PORT) */
#define WH_SERVER_PORT 7947   /* the app listens here (WinHandler.SERVER_PORT) */
#define WH_REQUEST_MAX 256    /* the app's sendData buffer */

/* RequestCodes.java */
enum {
    WH_EXIT = 0, WH_INIT = 1, WH_EXEC = 2, WH_KILL_PROCESS = 3, WH_LIST_PROCESSES = 4,
    WH_GET_PROCESS = 5, WH_SET_PROCESS_AFFINITY = 6, WH_MOUSE_EVENT = 7,
    WH_KEYBOARD_EVENT = 11, WH_BRING_TO_FRONT = 12, WH_CURSOR_POS_FEEDBACK = 13,
    WH_SET_CLIPBOARD_DATA = 14, WH_OPEN_URL = 15, WH_SHOW_DESKTOP = 18, WH_SHOW_WINDOW = 20,
    WH_GET_EXECUTABLE_PATH = 21
};

/* ---- reading a request -------------------------------------------------- */

struct wh_reader {
    const unsigned char *d;
    int len;
    int pos;     /* starts at 1: byte 0 is the request code */
};

static inline int wh_has(const struct wh_reader *r, int n)
{
    return n >= 0 && r->pos >= 0 && r->pos + n <= r->len;
}

static inline int wh_read_u8(struct wh_reader *r)
{
    if (!wh_has(r, 1)) { r->pos = r->len + 1; return -1; }
    return r->d[r->pos++];
}

static inline int32_t wh_read_i32(struct wh_reader *r)
{
    if (!wh_has(r, 4)) { r->pos = r->len + 1; return 0; }
    const unsigned char *p = r->d + r->pos;
    r->pos += 4;
    return (int32_t)((uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24);
}

static inline int16_t wh_read_i16(struct wh_reader *r)
{
    if (!wh_has(r, 2)) { r->pos = r->len + 1; return 0; }
    const unsigned char *p = r->d + r->pos;
    r->pos += 2;
    return (int16_t)((uint16_t)p[0] | (uint16_t)p[1] << 8);
}

static inline int64_t wh_read_i64(struct wh_reader *r)
{
    if (!wh_has(r, 8)) { r->pos = r->len + 1; return 0; }
    uint64_t v = 0;
    for (int i = 7; i >= 0; i--) v = v << 8 | r->d[r->pos + i];
    r->pos += 8;
    return (int64_t)v;
}

/* an int length, then that many bytes: where they start, and *n; NULL (and
 * *n = 0) when the length is negative or runs past the datagram */
static inline const char *wh_read_bytes(struct wh_reader *r, int *n)
{
    int32_t len = wh_read_i32(r);
    *n = 0;
    if (r->pos > r->len || len < 0 || !wh_has(r, len)) { r->pos = r->len + 1; return NULL; }
    const char *s = (const char *)r->d + r->pos;
    r->pos += len;
    *n = len;
    return s;
}

/* n bytes into a 0-terminated string of at most size - 1 of them */
static inline void wh_copy_string(char *out, size_t size, const char *s, int n)
{
    if (size == 0) return;
    size_t k = n < 0 ? 0 : (size_t)n;
    if (k > size - 1) k = size - 1;
    if (k) memcpy(out, s, k);
    out[k] = 0;
}

struct wh_mouse {
    uint32_t flags;    /* MOUSEEVENTF_*, MouseEventFlags.java */
    int dx, dy, wheel;
    int feedback;      /* 1: answer with the cursor position */
};

/* MOUSE_EVENT: int 10, int flags, short dx, short dy, short wheel, byte feedback */
static inline int wh_read_mouse(struct wh_reader *r, struct wh_mouse *m)
{
    int32_t len = wh_read_i32(r);
    m->flags = (uint32_t)wh_read_i32(r);
    m->dx = wh_read_i16(r);
    m->dy = wh_read_i16(r);
    m->wheel = wh_read_i16(r);
    m->feedback = wh_read_u8(r) == 1;
    return len == 10 && r->pos <= r->len;
}

/* ---- packing an answer ------------------------------------------------- */

static inline void wh_put_i16(unsigned char *p, int16_t v)
{
    p[0] = (unsigned char)(v & 0xff);
    p[1] = (unsigned char)((uint16_t)v >> 8);
}

static inline void wh_put_i32(unsigned char *p, int32_t v)
{
    for (int i = 0; i < 4; i++) p[i] = (unsigned char)((uint32_t)v >> (8 * i));
}

static inline void wh_put_i64(unsigned char *p, int64_t v)
{
    for (int i = 0; i < 8; i++) p[i] = (unsigned char)((uint64_t)v >> (8 * i));
}

/* code + int: OPEN_URL and GET_EXECUTABLE_PATH, before their data datagram */
static inline void wh_pack_header(unsigned char out[5], int code, int32_t n)
{
    out[0] = (unsigned char)code;
    wh_put_i32(out + 1, n);
}

static inline void wh_pack_cursor(unsigned char out[5], int16_t x, int16_t y)
{
    out[0] = WH_CURSOR_POS_FEEDBACK;
    wh_put_i16(out + 1, x);
    wh_put_i16(out + 3, y);
}

struct wh_process {
    uint32_t pid;
    uint64_t memory;     /* working set, bytes */
    uint32_t affinity;
    int wow64;
    char name[32];       /* ANSI, not always 0-terminated in the packet */
};

/* GET_PROCESS: what WinHandler.handleRequest reads, in that order */
#define WH_PROCESS_SIZE (1 + 4 + 2 + 2 + 4 + 8 + 4 + 1 + 32)

static inline void wh_pack_process(unsigned char out[WH_PROCESS_SIZE], uint16_t count, uint16_t index,
                                   const struct wh_process *p)
{
    memset(out, 0, WH_PROCESS_SIZE);
    out[0] = WH_GET_PROCESS;
    wh_put_i32(out + 1, 0);              /* skipped by the app */
    wh_put_i16(out + 5, (int16_t)count);
    wh_put_i16(out + 7, (int16_t)index);
    wh_put_i32(out + 9, (int32_t)p->pid);
    wh_put_i64(out + 13, (int64_t)p->memory);
    wh_put_i32(out + 21, (int32_t)p->affinity);
    out[25] = p->wow64 ? 1 : 0;
    memcpy(out + 26, p->name, 32);
}

#endif
