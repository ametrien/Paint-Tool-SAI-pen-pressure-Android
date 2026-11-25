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

    public static final String TIMELAPSES = "Timelapses";

    /** Documents/SAI Drawings/Timelapses on the tablet. */
    public static File timelapseFolder() {
        return new File(drawingsFolder(), TIMELAPSES);
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
        /* SAI's file browser still opens in users/xuser: what was saved
         * there (or SAI was closed by Android before SAI Pen saw it close)
         * goes to the device now (DeviceDrawings says how).
         * keepOriginals = true: a copy stays inside the app so SAI's Recent
         * list can still open it. The copy is cleaned up when SAI closes
         * (moveDrawingsOut with keepOriginals = false, in SaiSession). */
        containerRoot = container.getRootDir();
        moveDrawingsOut(containerRoot, true);

        /* SAI's own settings (brushes, textures, presets, swatches) on the
         * tablet, in the same place as on Windows, so people can copy their
         * PC's folder there and it outlives an uninstall. Until 0.4.21 they
         * were inside the app; the first start moves them out
         * (PrefixLinks.moveIntoAndLink says how). */
        linkSettings(container.getRootDir());

        File userReg = new File(prefix, "user.reg");
        try (WineRegistryEditor reg = new WineRegistryEditor(userReg)) {
            reg.setStringValue("Software\\Wine\\DllOverrides", "wintab32", "native,builtin");
        }

        applyLanguage(context, prefix);
    }

    /**
     * SAI's menu language (sai2.ini, SaiLanguage) and the font for Chinese,
     * Japanese and Korean text (system.reg, FontLink), as picked in the
     * launcher. Both are read once when SAI and Wine start.
     */
    static void applyLanguage(Context context, File prefix) {
        android.content.SharedPreferences prefs =
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        String saiDir = prefs.getString("saipen_sai_dir", null);
        try {
            String lang = SaiLanguage.apply(saiDir != null ? new File(saiDir) : null,
                prefs.getString(SaiLanguage.PREF_CHOICE, null), deviceLanguage());
            if (lang != null) android.util.Log.i("SaiPen", "sai2.ini lang = " + lang);
        }
        catch (java.io.IOException | RuntimeException e) {
            android.util.Log.w("SaiPen", "sai2.ini language", e);
        }

        File systemReg = new File(prefix, "system.reg");
        try {
            String before = new String(java.nio.file.Files.readAllBytes(systemReg.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
            java.util.List<String> fonts = FontLink.fontsFor(prefs.getString(FontLink.PREF_FONT, null),
                file -> new File(FontLink.SYSTEM_FONTS, file).isFile());
            String after = FontLink.apply(before, fonts, java.util.Collections.emptyList());
            if (!after.equals(before)) {
                File tmp = new File(prefix, "system.reg.saipen");
                java.nio.file.Files.write(tmp.toPath(), after.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.nio.file.Files.move(tmp.toPath(), systemReg.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                android.util.Log.i("SaiPen", "font links: " + fonts);
            }
        }
        catch (java.io.IOException | RuntimeException e) {
            android.util.Log.w("SaiPen", "font links", e);
        }
    }

    /** The device's language ("ja", "en"...). */
    static String deviceLanguage() {
        android.os.LocaleList list = android.content.res.Resources.getSystem().getConfiguration().getLocales();
        return list.isEmpty() ? java.util.Locale.getDefault().getLanguage() : list.get(0).getLanguage();
    }

    /** The container of the session being set up or run: SaiSession moves
     *  drawings out of it when SAI closes. */
    public static volatile File containerRoot;

    /**
     * Drawings SAI saved inside the app (users/xuser and the folders in
     * DeviceDrawings.FOLDERS) go to Documents/SAI Drawings, which SAI sees as
     * users/xuser/SAI Drawings. Called before each session and after SAI closes,
     * never while SAI runs. Returns the device files drawings went to.
     */
    public static java.util.List<File> moveDrawingsOut(File containerRoot) {
        return moveDrawingsOut(containerRoot, false);
    }

    /**
     * When `keepOriginals` is true, the in-app file is left in place after
     * copying it to the device: SAI's Recent list (which remembers the
     * in-app path) can still open it next session. Called with true before
     * SAI starts; with false (move, delete the original) after SAI closes.
     */
    public static java.util.List<File> moveDrawingsOut(File containerRoot, boolean keepOriginals) {
        File xuser = new File(containerRoot, ".wine/drive_c/users/xuser");
        java.util.List<File> folders = new java.util.ArrayList<>();
        for (String f : DeviceDrawings.FOLDERS) folders.add(f.isEmpty() ? xuser : new File(xuser, f));
        java.util.List<File> moved;
        try {
            moved = DeviceDrawings.moveOut(folders, drawingsFolder(), keepOriginals);
        }
        catch (RuntimeException e) {
            android.util.Log.w("SaiPen", "drawings not moved out", e);
            return java.util.Collections.emptyList();
        }
        for (File f : moved) android.util.Log.i("SaiPen", "drawing moved to the device: " + f);
        return moved;
    }

    /**
     * Notifies Android's MediaStore about all drawings and videos in Documents/SAI Drawings,
     * so that Android file managers (Samsung My Files) and Recent views show them immediately.
     */
    public static void scanDrawings(Context context) {
        if (context == null) return;
        DeviceDrawings.migrateTimelapses(drawingsFolder(), timelapseFolder());
        java.util.List<File> files = DeviceDrawings.listScannable(drawingsFolder());
        if (files.isEmpty()) return;
        String[] paths = new String[files.size()];
        for (int i = 0; i < files.size(); i++) paths[i] = files.get(i).getAbsolutePath();
        android.media.MediaScannerConnection.scanFile(context.getApplicationContext(), paths, null, null);
    }

    /** Scans a single drawing or video file into Android's MediaStore. */
    public static void scanFile(Context context, File file) {
        if (context == null || file == null) return;
        android.media.MediaScannerConnection.scanFile(
            context.getApplicationContext(), new String[]{file.getAbsolutePath()}, null, null);
    }

    /**
     * SAI's own settings (brushes, textures, presets, swatches) on the
     * tablet, in the same place as on Windows, so people can copy their PC's
     * folder there and it outlives an uninstall. Until 0.4.21 they were
     * inside the app; the first call moves them out
     * (PrefixLinks.moveIntoAndLink says how). Called before each session
     * and by SAI Pen's screen before it counts or adds brushes: a pack
     * added into an empty folder on the tablet would otherwise win over
     * SAI's settings still inside the app. Returns true when the link is in
     * place; on false SAI uses its folder inside the app, as before.
     */
    public static boolean linkSettings(File containerRoot) {
        File settings = new File(containerRoot, ".wine/drive_c/users/xuser/Documents/" + SETTINGS_PATH);
        try {
            return PrefixLinks.moveIntoAndLink(settings, settingsFolder(),
                new File(settings.getParentFile(), "settings-inside-the-app"));
        }
        catch (Exception e) {
            return false;
        }
    }
}
