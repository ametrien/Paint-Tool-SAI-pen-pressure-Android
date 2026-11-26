/*
 * winhandler.exe for SAI Pen for Android: our own, written from the
 * protocol on the app's side (Winlator's WinHandler.java, LGPL-2.1). Winlator
 * ships a winhandler.exe of its own whose source is not published; this one
 * replaces it, so every program in the APK has its source.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms (GPL 3, section 7): see NOTICE
 *
 * How Winlator uses it. Every session starts as
 *     wine explorer /desktop=... C:\windows\winhandler.exe /dir <folder> "<program>" [arguments]
 * and Wine's browser setting runs
 *     C:\windows\winhandler.exe /url <address>
 * for a link a Windows program opens.
 *
 * With /dir it starts the program in that folder, tells the app it is
 * there (INIT) and then serves the app's requests until the session ends:
 * SAI Pen ends the session itself once sai2.exe has closed. With /url it
 * hands the address to the app (OPEN_URL), which opens it on Android, and
 * exits.
 *
 * The wire: UDP on 127.0.0.1. We listen on CLIENT_PORT 7946, the app on
 * SERVER_PORT 7947. All numbers little-endian. Byte 0 is the request code
 * (RequestCodes.java). The app sends every request as one 256-byte datagram
 * (WinHandler.sendData), whatever its length.
 *
 *   app -> us
 *     EXEC 2                 int n, n bytes file (UTF-8), int m, m bytes arguments
 *     KILL_PROCESS 3         int n, n bytes process name, int pid (0: by name)
 *     LIST_PROCESSES 4       int 0                 -> one GET_PROCESS per process
 *     SET_PROCESS_AFFINITY 6 int len, int pid, int mask, byte n, n bytes name (pid 0: by name)
 *     MOUSE_EVENT 7          int 10, int flags (MOUSEEVENTF_*), short dx, short dy,
 *                            short wheel, byte 1: answer with CURSOR_POS_FEEDBACK
 *     KEYBOARD_EVENT 11      byte virtual key, int flags (KEYEVENTF_*)
 *     BRING_TO_FRONT 12      int n, n bytes window class (= the program's file name), long HWND
 *     SET_CLIPBOARD_DATA 14  int n, n bytes text (UTF-8)
 *     SHOW_DESKTOP 18        int 0
 *     SHOW_WINDOW 20         long HWND, int nCmdShow
 *     GET_EXECUTABLE_PATH 21 int pid               -> GET_EXECUTABLE_PATH back
 *     CLOSE_SAI 100          nothing more: SAI Pen's own, closes SAI the way
 *                            its red cross does (close_sai below)
 *
 *   us -> app
 *     INIT 1                 nothing more; until it comes the app sends nothing
 *     GET_PROCESS 5          int 0, short count, short index, int pid, long memory,
 *                            int affinity mask, byte WoW64, 32 bytes name (ANSI)
 *     CURSOR_POS_FEEDBACK 13 short x, short y
 *     OPEN_URL 15            int n, then a second datagram of n bytes (UTF-8)
 *     GET_EXECUTABLE_PATH 21 int n, then a second datagram of n bytes (UTF-16LE), none if n = 0
 *
 * Left out, as SAI Pen does not use them: the gamepad and MIDI requests
 * (Wine's own drivers talk to the app for those) and PRE_EXEC (sent by
 * Winlator's winpreexec, which SAI Pen does not ship).
 */
/* process names go to the app as ANSI (GET_PROCESS): the plain Toolhelp
 * structs, whatever -municode (for wWinMain) asks for */
#undef UNICODE
#undef _UNICODE
#define WIN32_LEAN_AND_MEAN
#define PSAPI_VERSION 2
#include <winsock2.h>
#include <windows.h>
#include <shellapi.h>
#include <tlhelp32.h>
#include <psapi.h>
#include <stdint.h>
#include <string.h>
#include <wchar.h>

#include "protocol.h"

static SOCKET sock = INVALID_SOCKET;
static struct sockaddr_in app_addr;

static void send_to_app(const void *data, int len)
{
    sendto(sock, (const char *)data, len, 0, (struct sockaddr *)&app_addr, sizeof(app_addr));
}

/* ---- text ------------------------------------------------------------- */

/* UTF-8 bytes (not 0-terminated) to a new wide string; free() it */
static wchar_t *utf8_to_wide(const char *s, int n)
{
    int w = MultiByteToWideChar(CP_UTF8, 0, s, n, NULL, 0);
    wchar_t *out = calloc((size_t)w + 1, sizeof(wchar_t));
    if (out && w > 0) MultiByteToWideChar(CP_UTF8, 0, s, n, out, w);
    return out;
}

/* ---- processes -------------------------------------------------------- */

/* the file name of a process by its pid, "" if it cannot be read */
static void process_file_name(DWORD pid, char *out, size_t size)
{
    out[0] = 0;
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return;
    PROCESSENTRY32 pe = { .dwSize = sizeof(pe) };
    for (BOOL ok = Process32First(snap, &pe); ok; ok = Process32Next(snap, &pe)) {
        if (pe.th32ProcessID == pid) {
            lstrcpynA(out, pe.szExeFile, (int)size);
            break;
        }
    }
    CloseHandle(snap);
}

/* call fn for every process whose file name is name (any letter case) */
static void for_each_named(const char *name, void (*fn)(DWORD pid, void *ctx), void *ctx)
{
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return;
    PROCESSENTRY32 pe = { .dwSize = sizeof(pe) };
    for (BOOL ok = Process32First(snap, &pe); ok; ok = Process32Next(snap, &pe)) {
        if (lstrcmpiA(pe.szExeFile, name) == 0) fn(pe.th32ProcessID, ctx);
    }
    CloseHandle(snap);
}

static void kill_pid(DWORD pid, void *ctx)
{
    (void)ctx;
    if (pid == GetCurrentProcessId()) return;
    HANDLE h = OpenProcess(PROCESS_TERMINATE, FALSE, pid);
    if (h) {
        TerminateProcess(h, 1);
        CloseHandle(h);
    }
}

static void set_affinity_pid(DWORD pid, void *ctx)
{
    DWORD_PTR mask = (DWORD_PTR)*(uint32_t *)ctx;
    HANDLE h = OpenProcess(PROCESS_SET_INFORMATION | PROCESS_QUERY_INFORMATION, FALSE, pid);
    if (h) {
        SetProcessAffinityMask(h, mask);
        CloseHandle(h);
    }
}

static void list_processes(void)
{
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return;
    PROCESSENTRY32 pe = { .dwSize = sizeof(pe) };
    int count = 0;
    for (BOOL ok = Process32First(snap, &pe); ok; ok = Process32Next(snap, &pe)) count++;

    int index = 0;
    for (BOOL ok = Process32First(snap, &pe); ok && index < count; ok = Process32Next(snap, &pe)) {
        struct wh_process p;
        memset(&p, 0, sizeof(p));
        p.pid = pe.th32ProcessID;
        HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ, FALSE, pe.th32ProcessID);
        if (h) {
            PROCESS_MEMORY_COUNTERS mc = { .cb = sizeof(mc) };
            if (GetProcessMemoryInfo(h, &mc, sizeof(mc))) p.memory = mc.WorkingSetSize;
            DWORD_PTR mask = 0, system = 0;
            if (GetProcessAffinityMask(h, &mask, &system)) p.affinity = (uint32_t)mask;
            BOOL wow64 = FALSE;
            if (IsWow64Process(h, &wow64)) p.wow64 = wow64 ? 1 : 0;
            CloseHandle(h);
        }
        lstrcpynA(p.name, pe.szExeFile, sizeof(p.name));
        unsigned char out[WH_PROCESS_SIZE];
        wh_pack_process(out, (uint16_t)count, (uint16_t)index, &p);
        send_to_app(out, sizeof(out));
        index++;
        /* the app takes them one by one on a 64-byte buffer: do not flood it */
        Sleep(1);
    }
    CloseHandle(snap);
}

static void send_executable_path(DWORD pid)
{
    wchar_t path[MAX_PATH * 2];
    DWORD len = 0;
    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (h) {
        len = sizeof(path) / sizeof(path[0]);
        if (!QueryFullProcessImageNameW(h, 0, path, &len)) len = 0;
        CloseHandle(h);
    }
    unsigned char head[5];
    wh_pack_header(head, WH_GET_EXECUTABLE_PATH, (int32_t)(len * sizeof(wchar_t)));
    send_to_app(head, sizeof(head));
    if (len > 0) send_to_app(path, (int)(len * sizeof(wchar_t)));
}

/* ---- windows ---------------------------------------------------------- */

struct find_window {
    const char *class_name;
    HWND found;
};

static BOOL CALLBACK find_by_class(HWND w, LPARAM lp)
{
    struct find_window *f = (struct find_window *)lp;
    if (!IsWindowVisible(w) || GetWindow(w, GW_OWNER) != NULL) return TRUE;
    char cls[256];
    if (GetClassNameA(w, cls, sizeof(cls)) && lstrcmpiA(cls, f->class_name) == 0) {
        f->found = w;
        return FALSE;
    }
    /* the class Winlator names is the X window's, which Wine makes the
     * program's file name: match the window's process too */
    DWORD pid = 0;
    char exe[MAX_PATH];
    GetWindowThreadProcessId(w, &pid);
    process_file_name(pid, exe, sizeof(exe));
    if (exe[0] && lstrcmpiA(exe, f->class_name) == 0) {
        f->found = w;
        return FALSE;
    }
    return TRUE;
}

static void bring_to_front(const char *class_name, HWND w)
{
    if (!w || !IsWindow(w)) {
        struct find_window f = { class_name, NULL };
        EnumWindows(find_by_class, (LPARAM)&f);
        w = f.found;
    }
    if (!w) return;
    if (IsIconic(w)) ShowWindow(w, SW_RESTORE);
    SwitchToThisWindow(w, TRUE);
    SetWindowPos(w, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
}

static BOOL CALLBACK minimize_one(HWND w, LPARAM lp)
{
    (void)lp;
    if (IsWindowVisible(w) && GetWindow(w, GW_OWNER) == NULL && !(GetWindowLongW(w, GWL_EXSTYLE) & WS_EX_TOOLWINDOW))
        ShowWindow(w, SW_MINIMIZE);
    return TRUE;
}

/* ---- clipboard -------------------------------------------------------- */

static void set_clipboard(const char *text, int n)
{
    wchar_t *w = utf8_to_wide(text, n);
    if (!w) return;
    size_t bytes = (wcslen(w) + 1) * sizeof(wchar_t);
    HGLOBAL mem = GlobalAlloc(GMEM_MOVEABLE, bytes);
    if (mem) {
        void *p = GlobalLock(mem);
        memcpy(p, w, bytes);
        GlobalUnlock(mem);
        if (OpenClipboard(NULL)) {
            EmptyClipboard();
            if (!SetClipboardData(CF_UNICODETEXT, mem)) GlobalFree(mem);
            CloseClipboard();
        }
        else GlobalFree(mem);
    }
    free(w);
}

/* ---- starting programs ------------------------------------------------ */

static void run(const wchar_t *file, const wchar_t *args, const wchar_t *dir)
{
    SHELLEXECUTEINFOW info = { .cbSize = sizeof(info) };
    info.fMask = SEE_MASK_NOASYNC | SEE_MASK_FLAG_NO_UI;
    info.lpVerb = L"open";
    info.lpFile = file;
    info.lpParameters = (args && args[0]) ? args : NULL;
    info.lpDirectory = (dir && dir[0]) ? dir : NULL;
    info.nShow = SW_SHOWNORMAL;
    ShellExecuteExW(&info);
}

/* ---- requests --------------------------------------------------------- */

static void close_sai(void);

static void handle(const unsigned char *d, int n)
{
    struct wh_reader r = { d, n, 1 };
    switch (d[0]) {
    case WH_EXEC: {
        int fn = 0, an = 0;
        const char *file = wh_read_bytes(&r, &fn);
        const char *args = wh_read_bytes(&r, &an);
        if (!file || fn == 0) break;
        wchar_t *wf = utf8_to_wide(file, fn);
        wchar_t *wa = args ? utf8_to_wide(args, an) : NULL;
        if (wf) run(wf, wa, NULL);
        free(wf);
        free(wa);
        break;
    }
    case WH_KILL_PROCESS: {
        int nn = 0;
        const char *name = wh_read_bytes(&r, &nn);
        int32_t pid = wh_read_i32(&r);
        if (pid > 0) kill_pid((DWORD)pid, NULL);
        else if (name && nn > 0) {
            char s[256];
            wh_copy_string(s, sizeof(s), name, nn);
            for_each_named(s, kill_pid, NULL);
        }
        break;
    }
    case WH_LIST_PROCESSES:
        list_processes();
        break;
    case WH_SET_PROCESS_AFFINITY: {
        wh_read_i32(&r);                       /* length of what follows */
        int32_t pid = wh_read_i32(&r);
        uint32_t mask = (uint32_t)wh_read_i32(&r);
        int nn = wh_read_u8(&r);
        if (mask == 0) break;
        if (pid > 0) set_affinity_pid((DWORD)pid, &mask);
        else if (nn > 0 && r.pos + nn <= r.len) {
            char s[256];
            wh_copy_string(s, sizeof(s), (const char *)d + r.pos, nn);
            for_each_named(s, set_affinity_pid, &mask);
        }
        break;
    }
    case WH_MOUSE_EVENT: {
        struct wh_mouse m;
        if (!wh_read_mouse(&r, &m)) break;
        DWORD data = 0;
        if (m.flags & MOUSEEVENTF_WHEEL) data = (DWORD)(int32_t)m.wheel;
        mouse_event(m.flags, m.dx, m.dy, data, 0);
        if (m.feedback) {
            POINT p;
            if (GetCursorPos(&p)) {
                unsigned char out[5];
                wh_pack_cursor(out, (int16_t)p.x, (int16_t)p.y);
                send_to_app(out, sizeof(out));
            }
        }
        break;
    }
    case WH_KEYBOARD_EVENT: {
        int vkey = wh_read_u8(&r);
        int32_t flags = wh_read_i32(&r);
        if (vkey <= 0) break;
        keybd_event((BYTE)vkey, (BYTE)MapVirtualKeyA((UINT)vkey, MAPVK_VK_TO_VSC), (DWORD)flags, 0);
        break;
    }
    case WH_BRING_TO_FRONT: {
        int nn = 0;
        const char *name = wh_read_bytes(&r, &nn);
        int64_t handle = wh_read_i64(&r);
        char s[256];
        wh_copy_string(s, sizeof(s), name ? name : "", name ? nn : 0);
        bring_to_front(s, (HWND)(intptr_t)handle);
        break;
    }
    case WH_SET_CLIPBOARD_DATA: {
        int nn = 0;
        const char *text = wh_read_bytes(&r, &nn);
        if (text) set_clipboard(text, nn);
        break;
    }
    case WH_SHOW_DESKTOP:
        EnumWindows(minimize_one, 0);
        break;
    case WH_SHOW_WINDOW: {
        int64_t handle = wh_read_i64(&r);
        int32_t cmd = wh_read_i32(&r);
        HWND w = (HWND)(intptr_t)handle;
        if (IsWindow(w)) ShowWindow(w, cmd);
        break;
    }
    case WH_GET_EXECUTABLE_PATH: {
        int32_t pid = wh_read_i32(&r);
        send_executable_path((DWORD)pid);
        break;
    }
    case WH_CLOSE_SAI:
        close_sai();
        break;
    default:
        break;
    }
}

/* ---- the command line ------------------------------------------------- */

/* one argument as Windows would quote it, appended to out */
static void append_quoted(wchar_t *out, size_t size, const wchar_t *arg)
{
    size_t len = wcslen(out);
    if (len && len + 1 < size) out[len++] = L' ';
    int quote = arg[0] == 0 || wcspbrk(arg, L" \t\"") != NULL;
    if (quote && len + 1 < size) out[len++] = L'"';
    for (const wchar_t *p = arg; *p && len + 2 < size; p++) {
        if (*p == L'"') out[len++] = L'\\';
        out[len++] = *p;
    }
    if (quote && len + 1 < size) out[len++] = L'"';
    out[len] = 0;
}

static int open_socket(int bind_ours)
{
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return 0;
    sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) return 0;
    memset(&app_addr, 0, sizeof(app_addr));
    app_addr.sin_family = AF_INET;
    app_addr.sin_port = htons(WH_SERVER_PORT);
    app_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (!bind_ours) return 1;
    BOOL reuse = TRUE;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, (const char *)&reuse, sizeof(reuse));
    struct sockaddr_in me;
    memset(&me, 0, sizeof(me));
    me.sin_family = AF_INET;
    me.sin_port = htons(WH_CLIENT_PORT);
    me.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    return bind(sock, (struct sockaddr *)&me, sizeof(me)) == 0;
}

static void open_url(const wchar_t *url)
{
    if (!open_socket(0)) return;
    int n = WideCharToMultiByte(CP_UTF8, 0, url, -1, NULL, 0, NULL, NULL) - 1;
    if (n <= 0) return;
    char *s = malloc((size_t)n + 1);
    if (!s) return;
    WideCharToMultiByte(CP_UTF8, 0, url, -1, s, n + 1, NULL, NULL);
    unsigned char head[5];
    wh_pack_header(head, WH_OPEN_URL, n);
    send_to_app(head, sizeof(head));
    send_to_app(s, n);
    free(s);
}

/*
 * SAI's main window, maximized at every start (wh_maximize_step, protocol.h).
 * Only ShowWindow on SAI's main window: no keys, no clicks, nothing of SAI's
 * memory or code.
 */
struct sai_main { HWND w; DWORD pid; };

static BOOL CALLBACK find_sai_main(HWND w, LPARAM p)
{
    wchar_t t[64];
    if (IsWindowVisible(w) && GetWindow(w, GW_OWNER) == NULL &&
        GetWindowTextW(w, t, 64) > 0 && wcsncmp(t, L"PaintTool SAI", 13) == 0) {
        struct sai_main *m = (struct sai_main *)p;
        m->w = w;
        GetWindowThreadProcessId(w, &m->pid);
        return FALSE;
    }
    return TRUE;
}

/*
 * Exit SAI on the on-screen keys (wh_close_step, protocol.h): the message
 * SAI's red cross sends, posted to SAI's main window. SAI then does what it
 * always does on its red cross: asks about unsaved drawings, and stays open
 * on Cancel. Never TerminateProcess. Posted, not sent: we do not wait on SAI's
 * dialog.
 */
static void close_sai(void)
{
    struct sai_main m = { NULL, 0 };
    EnumWindows(find_sai_main, (LPARAM)&m);
    HWND dialog = m.w ? GetLastActivePopup(m.w) : NULL;
    if (dialog == m.w) dialog = NULL;
    switch (wh_close_step(m.w != NULL, m.w && IsWindowEnabled(m.w), dialog != NULL)) {
    case WH_CLOSE_POST:
        PostMessageW(m.w, WM_SYSCOMMAND, SC_CLOSE, 0);
        break;
    case WH_CLOSE_SHOW_DIALOG:
        SetForegroundWindow(dialog);
        break;
    default:
        break;
    }
}

static DWORD WINAPI maximize_sai(LPVOID unused)
{
    (void)unused;
    struct wh_maximize state = { 0, 0 };
    for (;;) {
        struct sai_main m = { NULL, 0 };
        EnumWindows(find_sai_main, (LPARAM)&m);
        if (m.w && wh_maximize_step(&state, m.pid, IsZoomed(m.w), IsIconic(m.w), (int64_t)GetTickCount64()))
            ShowWindow(m.w, SW_MAXIMIZE);
        Sleep((DWORD)wh_maximize_next_ms(&state, (int64_t)GetTickCount64()));
    }
    return 0;
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmdline, int show)
{
    (void)inst; (void)prev; (void)cmdline; (void)show;
    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (!argv) return 1;

    if (argc >= 3 && lstrcmpiW(argv[1], L"/url") == 0) {
        /* Wine passes the address as one argument; join any split one back */
        wchar_t url[4096] = L"";
        for (int i = 2; i < argc; i++) {
            if (i > 2) wcsncat(url, L" ", 4095 - wcslen(url));
            wcsncat(url, argv[i], 4095 - wcslen(url));
        }
        open_url(url);
        return 0;
    }

    if (!open_socket(1)) {
        /* another winhandler already serves the app: only start the program */
        sock = INVALID_SOCKET;
    }

    if (argc >= 4 && lstrcmpiW(argv[1], L"/dir") == 0) {
        static wchar_t args[8192];
        args[0] = 0;
        for (int i = 4; i < argc; i++) append_quoted(args, sizeof(args) / sizeof(args[0]), argv[i]);
        run(argv[3], args, argv[2]);
    }
    LocalFree(argv);
    if (sock == INVALID_SOCKET) return 0;

    unsigned char init = WH_INIT;
    send_to_app(&init, 1);
    CreateThread(NULL, 0, maximize_sai, NULL, 0, NULL);

    unsigned char buf[WH_REQUEST_MAX];
    for (;;) {
        struct sockaddr_in from;
        int fromlen = sizeof(from);
        int n = recvfrom(sock, (char *)buf, sizeof(buf), 0, (struct sockaddr *)&from, &fromlen);
        if (n <= 0) {
            if (WSAGetLastError() == WSAEMSGSIZE) n = sizeof(buf);   /* longer than we read: the start is enough */
            else {
                Sleep(10);
                continue;
            }
        }
        if (from.sin_addr.s_addr != htonl(INADDR_LOOPBACK)) continue;
        if (buf[0] == WH_EXIT) break;
        handle(buf, n);
    }
    closesocket(sock);
    WSACleanup();
    return 0;
}
