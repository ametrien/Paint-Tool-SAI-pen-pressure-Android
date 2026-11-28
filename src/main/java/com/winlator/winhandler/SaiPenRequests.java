package com.winlator.winhandler;

/**
 * SAI Pen's own requests to its winhandler.exe (winhandler/winhandler.c),
 * beside Winlator's in WinHandler. In WinHandler's package for its send
 * queue and buffer, so no line of Winlator's file changes. The codes are
 * winhandler/protocol.h's, far from Winlator's own (0 to 22).
 */
public final class SaiPenRequests {
    /** protocol.h WH_CLOSE_SAI */
    static final byte CLOSE_SAI = 100;
    /** protocol.h WH_RESTORE_SAI */
    static final byte RESTORE_SAI = 101;
    /** WinHandler.CLIENT_PORT: winhandler.exe listens here */
    private static final int CLIENT_PORT = 7946;

    private SaiPenRequests() {}

    /**
     * Closes SAI the way its red cross does (never a kill): SAI asks about
     * unsaved drawings and stays open on Cancel. Nothing before
     * winhandler.exe has said it is there.
     */
    public static void closeSai(WinHandler h) {
        send(h, CLOSE_SAI);
    }

    /**
     * Brings a minimized SAI back: the Wine desktop has no taskbar, so SAI
     * minimized with its title bar's button had nowhere to come back from.
     * winhandler.exe restores SAI's main window only if it is minimized, so
     * a SAI that is closing is left to close.
     */
    public static void restoreSai(WinHandler h) {
        send(h, RESTORE_SAI);
    }

    private static void send(WinHandler h, byte code) {
        if (h == null || !h.initReceived) return;
        h.addAction(() -> {
            h.sendData.rewind();
            h.sendData.put(code);
            h.sendPacket(CLIENT_PORT);
        });
    }
}
