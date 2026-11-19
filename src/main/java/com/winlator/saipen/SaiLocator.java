package com.winlator.saipen;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Finds PaintTool SAI Ver.2 on the device, so the person only has to put it
 * somewhere (Download, Telegram, Documents...) and open SAI Pen.
 *
 * SAI needs its whole folder, not only sai2.exe: next to it sai2.ini and the
 * init folder (brushes, papers). With only the exe it stops at start with a
 * dialog listing init\papertex, init\blotmap... (seen on the tablet on
 * 2026-11-18, when only sai2.exe had come over Telegram). So a folder with
 * the exe but without them is reported as INCOMPLETE, and the screen says
 * what is missing instead of starting SAI into that dialog.
 *
 * No Android types: the JVM tests run it on the Mac with real folders.
 */
public final class SaiLocator {
    private SaiLocator() {}

    public static final String EXE = "sai2.exe";

    /** A folder that holds sai2.exe. */
    public static final class Found {
        public final File dir;
        /** sai2.ini and init/ are next to the exe: SAI can start */
        public final boolean complete;
        /** sai2.exe's time: the newest SAI wins when there are several */
        public final long modified;

        Found(File dir, boolean complete, long modified) {
            this.dir = dir;
            this.complete = complete;
            this.modified = modified;
        }

        public File exe() {
            File f = child(dir, EXE);
            return f != null ? f : new File(dir, EXE);
        }
    }

    /** Folder names never worth walking into: photo folders with thousands
     *  of entries. Android/data and Android/obb (other apps' private files,
     *  unreadable) are skipped too, but not Android/media: newer Telegram
     *  versions save received files under Android/media/org.telegram.messenger. */
    private static final String[] SKIP = {"dcim", "lost.dir"};

    /**
     * Walks root breadth-first, down to maxDepth levels below it, and returns
     * every folder that holds sai2.exe (any letter case: it came from
     * Windows). Hidden folders and SKIP are not entered.
     */
    public static List<Found> scan(File root, int maxDepth) {
        List<Found> out = new ArrayList<>();
        walk(root, maxDepth, dir -> {
            File exe = child(dir, EXE);
            if (exe != null && exe.isFile()) out.add(new Found(dir, isComplete(dir), exe.lastModified()));
        });
        return out;
    }

    /** sai2*.zip archives under root (SAI as downloaded from its site). */
    public static List<File> zips(File root, int maxDepth) {
        List<File> out = new ArrayList<>();
        walk(root, maxDepth, dir -> {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && n.startsWith("sai2") && n.endsWith(".zip")) out.add(f);
            }
        });
        return out;
    }

    /** sai2.ini and init/ next to the exe. */
    public static boolean isComplete(File dir) {
        File ini = child(dir, "sai2.ini");
        File init = child(dir, "init");
        return ini != null && ini.isFile() && init != null && init.isDirectory();
    }

    /** Complete folders first; among those the newest sai2.exe. Null if none. */
    public static Found best(List<Found> found) {
        Found best = null;
        for (Found f : found) {
            if (best == null
                || (f.complete && !best.complete)
                || (f.complete == best.complete && f.modified > best.modified)) best = f;
        }
        return best;
    }

    /**
     * The folder a "choose folder" picker (ACTION_OPEN_DOCUMENT_TREE) points
     * at, from its tree document id: "primary:Download/SAI" is under the
     * phone's own storage, "1A2B-3C4D:SAI" on a memory card. Null for
     * anything else (a cloud folder has no path Wine could use).
     */
    public static String treeDocIdToPath(String docId, String primaryRoot) {
        if (docId == null) return null;
        int colon = docId.indexOf(':');
        if (colon <= 0) return null;
        String volume = docId.substring(0, colon);
        String rel = docId.substring(colon + 1);
        String base;
        if (volume.equalsIgnoreCase("primary")) base = primaryRoot;
        else if (volume.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) base = "/storage/" + volume;
        else return null;
        return rel.isEmpty() ? base : base + "/" + rel;
    }

    /**
     * The newest SAI licence certificate in these folders, walked maxDepth
     * levels down: a .slc file with "sai" in its name, as SYSTEMAX names
     * them (sai-010062815390.slc). Null if there is none. A .slc named
     * otherwise is still taken when the person picks it by hand.
     *
     * A certificate is made for one System ID, the one SAI shows in its
     * menu Other > System ID, and SAI in Wine on the tablet has its own.
     * SYSTEMAX's FAQ: a new computer needs no new licence, only a new
     * certificate downloaded for the new System ID with the same licence
     * number and password. That download is the newest .slc on the device.
     */
    public static File newestLicence(List<File> roots, int maxDepth) {
        File best = null;
        for (File root : roots) {
            List<File> found = new ArrayList<>();
            walk(root, maxDepth, dir -> {
                File[] files = dir.listFiles();
                if (files == null) return;
                for (File f : files) {
                    if (f.isFile() && isAutoLicenceName(f.getName())) found.add(f);
                }
            });
            for (File f : found) if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    /** A licence certificate is a small file: SAI's are 128 bytes. */
    public static final int MAX_LICENCE_BYTES = 64 * 1024;

    public static boolean isLicenceName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".slc");
    }

    /** A .slc that is SAI's for sure, to take without asking. */
    public static boolean isAutoLicenceName(String name) {
        return isLicenceName(name) && name.toLowerCase(Locale.ROOT).contains("sai");
    }

    /**
     * Copies a licence certificate where SAI Ver.2 looks for it: the folder
     * SYSTEMAX names, Documents\\SYSTEMAX Software Development\\SAIv2 (the one
     * with "error" and "settings"), here inside the Wine prefix. Returns true
     * when it wrote the file, false when the same bytes were already there.
     */
    public static boolean installLicence(File slc, File saiV2Dir) throws IOException {
        return installLicence(java.nio.file.Files.readAllBytes(slc.toPath()), slc.getName(), saiV2Dir);
    }

    /**
     * The same from a file the person picked (the picker gives bytes and a
     * name, no path). Refuses anything that is not a .slc of a sane size.
     *
     * THE TRAP: only ONE certificate stays in the folder. A certificate is
     * made for one System ID; an older one for another device (the Mac's, say)
     * left next to the right one could be the one SAI reads, and SAI would
     * stay in trial mode. Only .slc files in that folder are removed, and only
     * copies this app put there: the person's own files are not touched.
     */
    public static boolean installLicence(byte[] bytes, String name, File saiV2Dir) throws IOException {
        if (!isLicenceName(name)) throw new IOException("not a SAI licence certificate (.slc): " + name);
        if (bytes.length == 0 || bytes.length > MAX_LICENCE_BYTES) {
            throw new IOException("not a SAI licence certificate (" + bytes.length + " bytes)");
        }
        File target = new File(saiV2Dir, new File(name).getName());
        boolean same = target.isFile()
            && java.util.Arrays.equals(bytes, java.nio.file.Files.readAllBytes(target.toPath()));
        for (File old : installedLicences(saiV2Dir)) {
            if (!old.getName().equals(target.getName())) old.delete();
        }
        if (same) return false;
        if (!saiV2Dir.isDirectory() && !saiV2Dir.mkdirs()) throw new IOException("cannot create " + saiV2Dir);
        java.nio.file.Files.write(target.toPath(), bytes);
        return true;
    }

    /**
     * The automatic pickup at each start: installs `found` (newestLicence())
     * unless a certificate is in SAI's folder already that is at least as
     * new. Returns true when it installed.
     *
     * THE TRAP: installLicence() keeps one certificate only, so taking the
     * newest sai*.slc of Downloads every time would throw out one the person
     * picked by hand from elsewhere, as soon as an older file (the Mac's,
     * say) sits in Downloads. Our copy's time is when it was installed, so
     * only a file that came AFTER that replaces it.
     */
    public static boolean autoInstallLicence(File found, File saiV2Dir) throws IOException {
        if (found == null) return false;
        long newestInstalled = 0;
        for (File f : installedLicences(saiV2Dir)) newestInstalled = Math.max(newestInstalled, f.lastModified());
        if (newestInstalled > 0 && found.lastModified() <= newestInstalled) return false;
        return installLicence(found, saiV2Dir);
    }

    /** The certificates SAI will find in its folder. */
    /** Takes every certificate out of where SAI reads it; the file it came
     *  from (in Downloads, say) is not touched. How many were removed. */
    public static int removeLicences(File saiV2Dir) {
        int n = 0;
        for (File f : installedLicences(saiV2Dir)) if (f.delete()) n++;
        return n;
    }

    public static List<File> installedLicences(File saiV2Dir) {
        List<File> out = new ArrayList<>();
        File[] files = saiV2Dir.listFiles();
        if (files == null) return out;
        for (File f : files) if (f.isFile() && isLicenceName(f.getName())) out.add(f);
        return out;
    }

    /**
     * Unpacks a SAI zip next to itself, into a new folder named after the
     * zip ("sai2-Alpha-20261019-64bit-en"), because SAI's own zips have no
     * folder inside: their files would land loose among everything else.
     * Returns the new folder.
     *
     * THE TRAP: an entry named "../x" or "/x" would write outside that
     * folder. Such an entry stops the unpacking.
     */
    public static File unzipNextToIt(File zip) throws IOException {
        String base = zip.getName();
        if (base.toLowerCase(Locale.ROOT).endsWith(".zip")) base = base.substring(0, base.length() - 4);
        File parent = zip.getAbsoluteFile().getParentFile();
        File dest = new File(parent, base);
        for (int i = 2; dest.exists(); i++) dest = new File(parent, base + " (" + i + ")");
        if (!dest.mkdirs()) throw new IOException("cannot create " + dest);
        String destPath = dest.getCanonicalPath() + File.separator;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(destPath)) {
                    throw new IOException("entry outside the folder: " + e.getName());
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
        return dest;
    }

    /**
     * The size of Wine's screen for a display of w x h pixels: the display's
     * own proportions (so no black bars), turned to landscape, `height`
     * pixels high. Width rounded to an even number.
     */
    public static String wineScreenSize(int w, int h, int height) {
        int longSide = Math.max(w, h), shortSide = Math.min(w, h);
        if (shortSide <= 0) return (height * 16 / 9) + "x" + height;
        int width = Math.round((float) longSide * height / shortSide);
        width += width & 1;
        return width + "x" + height;
    }

    /**
     * The heights offered for Wine's screen on a display whose short side
     * is `shortSide` pixels: 720 (fastest, SAI's buttons largest), 900, and
     * the display's own (sharpest: one Wine pixel per screen pixel), never
     * more than the display has. Sorted, no repeats.
     *
     * Higher is sharper and slower: every frame SAI draws is that many more
     * pixels through Wine and the X server, and SAI's buttons, drawn at a
     * fixed pixel size, come out smaller.
     */
    public static int[] screenHeights(int shortSide) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int h : new int[] {720, 900, shortSide}) {
            if (h > 0 && h <= shortSide) set.add(h);
        }
        if (set.isEmpty()) set.add(Math.max(1, shortSide));
        int[] out = new int[set.size()];
        int i = 0;
        for (int h : set) out[i++] = h;
        return out;
    }

    /** Before the person chooses: 900 where the display has it (720 was
     *  soft on a 1200-line tablet), else the most it has up to 720. */
    public static int defaultScreenHeight(int shortSide) {
        if (shortSide >= 900) return 900;
        return Math.min(720, Math.max(1, shortSide));
    }

    /**
     * Winlator's drives setting with SAI's folder as S: (first, so a SAI
     * inside Download still maps to S:), D: = Download, E: = the app's
     * own storage. The format is Winlator's: letter, colon, path, no
     * separators (Container.drivesIterator).
     */
    public static String drives(String saiDir, String downloads, String internal) {
        return "S:" + saiDir + "D:" + downloads + "E:" + internal;
    }

    /**
     * Start SAI right away, without showing the launcher screen? Only when
     * the person opened the app (the launcher icon). After SAI exits,
     * Winlator restarts the app with container_id/start_path, and after its
     * settings with selected_menu_item_id.
     *
     * THE TRAP: starting SAI again on that restart would loop: closing SAI
     * would open SAI.
     */
    public static boolean shouldAutoLaunch(boolean fromLauncherIcon, boolean afterSession,
                                           boolean afterSettings, boolean saiReady) {
        return fromLauncherIcon && !afterSession && !afterSettings && saiReady;
    }

    /* ---- helpers ---- */

    private interface DirVisitor { void visit(File dir); }

    private static void walk(File root, int maxDepth, DirVisitor v) {
        if (root == null || !root.isDirectory()) return;
        ArrayDeque<File> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        queue.add(root);
        depths.add(0);
        while (!queue.isEmpty()) {
            File dir = queue.poll();
            int depth = depths.poll();
            v.visit(dir);
            if (depth >= maxDepth) continue;
            File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (!k.isDirectory()) continue;
                String n = k.getName().toLowerCase(Locale.ROOT);
                if (n.startsWith(".") || isSkipped(n)) continue;
                if ((n.equals("data") || n.equals("obb")) && dir.getName().equalsIgnoreCase("android")) continue;
                queue.add(k);
                depths.add(depth + 1);
            }
        }
    }

    private static boolean isSkipped(String lowerName) {
        for (String s : SKIP) if (s.equals(lowerName)) return true;
        return false;
    }

    /**
     * dir's child of that name in any letter case, or null. Taken from the
     * folder's listing, so it carries the name as it really is: Android's
     * storage tells SAI2.EXE from sai2.exe, the Mac's disk (where the tests
     * run) does not, and new File(dir, "sai2.exe").exists() would hide that.
     */
    private static File child(File dir, String name) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) if (k.getName().equals(name)) return k;
        for (File k : kids) if (k.getName().equalsIgnoreCase(name)) return k;
        return null;
    }
}
