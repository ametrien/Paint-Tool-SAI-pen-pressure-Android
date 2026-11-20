package com.winlator.saipen;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.core.WineRegistryEditor;

import java.io.File;

/**
 * Puts our wintab32.dll into the container before every session, the way
 * the Mac app copies it into its prefix at every launch: an updated APK then
 * always runs its own DLL.
 *
 * - C:\windows\system32\wintab32.dll: SAI Ver.2 is 64-bit, and in a 64-bit
 *   prefix system32 holds the 64-bit DLLs. The file is an x86-64 PE DLL;
 *   Box64 runs it inside SAI like the rest of SAI's code.
 * - HKCU\Software\Wine\DllOverrides wintab32 = native,builtin: without it
 *   Wine keeps loading its own wintab32, which never sees pen pressure.
 * - C:\wt_pmax.txt: the full-scale pressure the DLL announces to SAI. It
 *   must match what PenWire scales to, and SAI reads it once at start.
 * - C:\windows\winhandler.exe: our own (winhandler/ in the repository).
 *   Winlator starts every program through it and talks to it over UDP;
 *   Winlator's own one has no published source and is not in the APK.
 *
 * Proof that it loaded: the DLL rewrites C:\wt_status.txt about once a
 * second, with its build stamp and a count of samples received (recv=).
 * The app's own count of samples sent is in C:\saipen_status.txt (sent=).
 */
public final class SaiPenSetup {
    private SaiPenSetup() {}

    public static final String DLL_ASSET = "saipen/wintab32.dll";
    public static final String WINHANDLER_ASSET = "saipen/winhandler.exe";

    /** The drawings folder's name, in Windows and on the tablet. */
    public static final String DRAWINGS = "SAI Drawings";

    /** SAI Ver.2's settings folder under Documents, in Windows and on the tablet. */
    public static final String SETTINGS_PATH = "SYSTEMAX Software Development/SAIv2/settings";

    /** Documents/SYSTEMAX Software Development/SAIv2/settings on the tablet. */
    public static File settingsFolder() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS), SETTINGS_PATH);
    }

    /** Documents/SAI Drawings on the tablet. */
    public static File drawingsFolder() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS), DRAWINGS);
    }

    public static void installInto(Context context, Container container) {
        File prefix = new File(container.getRootDir(), ".wine");
        File driveC = new File(prefix, "drive_c");
        File system32 = new File(driveC, "windows/system32");
        system32.mkdirs();
        FileUtils.copy(context, DLL_ASSET, new File(system32, "wintab32.dll"));
        FileUtils.copy(context, WINHANDLER_ASSET, new File(driveC, "windows/winhandler.exe"));
        FileUtils.writeString(new File(driveC, "wt_pmax.txt"), String.valueOf(PenWire.PMAX));
        /* the app's side of the count, next to the DLL's C:\wt_status.txt */
        PenSampleSender.statusFile = new File(driveC, "saipen_status.txt");

        /* SAI's save dialog opens in the Windows user folder (users/xuser);
         * its Downloads is the tablet's, so a saved drawing can be found
         * (PrefixLinks says why). No backslash before "users" here: javac
         * reads a backslash-u as a character code, even in a comment. */
        try {
            PrefixLinks.linkFolder(new File(driveC, "users/xuser/Downloads"),
                                   new File(com.winlator.core.AppUtils.DIRECTORY_DOWNLOADS));
        }
        catch (java.io.IOException ignored) {
            /* SAI works as before; saving still goes inside the app */
        }
        /* and a folder just for drawings, right where the dialog opens:
         * in Windows "SAI Drawings", on the tablet Documents/SAI Drawings */
        File drawings = drawingsFolder();
        drawings.mkdirs();
        try {
            PrefixLinks.linkFolder(new File(driveC, "users/xuser/" + DRAWINGS), drawings);
        }
        catch (java.io.IOException ignored) {
            /* the Downloads link above still works */
        }

        /* SAI's own settings (brushes, textures, presets, swatches) on the
         * tablet, in the same place as on Windows, so people can copy their
         * PC's folder there and it outlives an uninstall. Until 0.4.21 they
         * were inside the app; the first start moves them out
         * (PrefixLinks.moveIntoAndLink says how). */
        File settings = new File(driveC, "users/xuser/Documents/" + SETTINGS_PATH);
        try {
            PrefixLinks.moveIntoAndLink(settings, settingsFolder(),
                new File(settings.getParentFile(), "settings-inside-the-app"));
        }
        catch (Exception ignored) {
            /* SAI starts with its settings inside the app, as before */
        }

        File userReg = new File(prefix, "user.reg");
        try (WineRegistryEditor reg = new WineRegistryEditor(userReg)) {
            reg.setStringValue("Software\\Wine\\DllOverrides", "wintab32", "native,builtin");
        }
    }
}
