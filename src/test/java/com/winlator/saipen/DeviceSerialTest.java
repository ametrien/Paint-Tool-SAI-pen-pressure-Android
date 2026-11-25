package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DeviceSerialTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void sameDeviceSameSerial() {
        assertEquals(DeviceSerial.serialFor("9774d56d682e549c"), DeviceSerial.serialFor("9774d56d682e549c"));
    }

    @Test public void otherDeviceOtherSerial() {
        assertNotEquals(DeviceSerial.serialFor("9774d56d682e549c"), DeviceSerial.serialFor("9774d56d682e549d"));
    }

    @Test public void serialShape() {
        String s = DeviceSerial.serialFor("abc");
        assertTrue(s, s.matches("SP[0-9A-F]{16}"));
        assertFalse("ANDROID_ID must not show", s.contains("abc"));
    }

    @Test public void writesOnceAndKeepsTheFirst() throws Exception {
        File dir = tmp.newFolder();
        assertTrue(DeviceSerial.writeIfMissing(dir, "one"));
        File f = new File(dir, DeviceSerial.FILE);
        String first = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII);
        assertEquals(DeviceSerial.serialFor("one") + "\n", first);
        assertFalse(DeviceSerial.writeIfMissing(dir, "two"));
        assertEquals(first, new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII));
        assertFalse(new File(dir, DeviceSerial.FILE + ".tmp").exists());
    }

    /** tools/rewrite_tar.py puts files/<FILE> where Wine's product_serial
     *  path was, in place: the length must match. */
    @Test public void pathFitsWinesSlot() {
        assertEquals("/sys/class/dmi/id/product_serial".length(),
                ("/data/data/sai.penpress/files/" + DeviceSerial.FILE).length());
    }
}
