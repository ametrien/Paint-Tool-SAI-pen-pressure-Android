package com.winlator.saipen;

import android.content.Context;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The "computer's" serial number Wine shows Windows programs, one per device.
 *
 * SAI builds its System ID from the SMBIOS strings (maker, model and serial
 * number of the system and of the board) and from drive C:'s description.
 * Wine reads the SMBIOS strings from /sys/class/dmi/id/, which Android does
 * not have, so they were empty on every device and every device got the same
 * System ID. tools/rewrite_tar.py points Wine's product_serial at FILE in the
 * app's files folder instead; this class writes it.
 *
 * Only for a NEW container: one made before this file existed keeps the
 * empty serial and so its System ID, and its licence keeps working.
 *
 * The serial comes from ANDROID_ID (one per device and signing key, kept
 * over reinstalls since Android 8), hashed, so the same device gets the same
 * System ID again after a reinstall and ANDROID_ID itself is not shown.
 */
public final class DeviceSerial {
    /** Same length as "product_serial" in the rewritten path: do not rename. */
    public static final String FILE = "id";

    private DeviceSerial() {}

    /** The serial for an ANDROID_ID: "SP" and 16 hex digits. */
    static String serialFor(String androidId) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] h = sha.digest(("sai-pen-system-serial:" + androidId).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder("SP");
            for (int i = 0; i < 8; i++) s.append(String.format("%02X", h[i] & 0xff));
            return s.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes the serial unless the file is there already. */
    public static void writeIfMissing(Context context) {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null || id.isEmpty()) id = UUID.randomUUID().toString();
        writeIfMissing(context.getFilesDir(), id);
    }

    static boolean writeIfMissing(File filesDir, String androidId) {
        File out = new File(filesDir, FILE);
        if (out.exists()) return false;
        File tmp = new File(filesDir, FILE + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write((serialFor(androidId) + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        catch (IOException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(out);
    }
}
