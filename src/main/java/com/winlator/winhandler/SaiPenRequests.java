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
    /** WinHandler.CLIENT_PORT: winhandler.exe listens here */
    private static final int CLIENT_PORT = 7946;

    private SaiPenRequests() {}

    /**
     * Closes SAI the way its red cross does (never a kill): SAI asks about
     * unsaved drawings and stays open on Cancel. Nothing before
     * winhandler.exe has said it is there.
     */
    public static void closeSai(WinHandler h) {
        if (h == null || !h.initReceived) return;
        h.addAction(() -> {
            h.sendData.rewind();
            h.sendData.put(CLOSE_SAI);
            h.sendPacket(CLIENT_PORT);
        });
    }
}
