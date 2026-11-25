package com.winlator.saipen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Through a real loopback socket, bound the way the DLL binds its own:
 * IPv4 127.0.0.1 only (wintab32.c open_sample_socket, AF_INET
 * INADDR_LOOPBACK).
 *
 * These tests run with -Djava.net.preferIPv6Addresses=true (app/build.gradle,
 * from patches/winlator.patch). With it the Mac's JVM answers
 * InetAddress.getLoopbackAddress() with ::1, as Android always does, so a
 * sender that asks for "the loopback address" instead of 127.0.0.1 fails
 * here the way 0.1.0 failed on the tablet (recv=0).
 */
public class PenSampleSenderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DatagramSocket dll;
    private PenSampleSender sender;

    @Before
    public void setUp() throws Exception {
        InetAddress v4 = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        dll = new DatagramSocket(new InetSocketAddress(v4, 0));
        dll.setSoTimeout(1000);
        sender = new PenSampleSender(dll.getLocalPort(), 1920, 1080);
    }

    @After
    public void tearDown() {
        sender.close();
        dll.close();
        PenSampleSender.statusFile = null;
    }

    private String[] receive() throws Exception {
        byte[] buf = new byte[256];
        DatagramPacket pk = new DatagramPacket(buf, buf.length);
        dll.receive(pk);
        return new String(pk.getData(), 0, pk.getLength(), StandardCharsets.US_ASCII).split(" ");
    }

    private String[] receiveOrNull(int timeoutMs) throws Exception {
        dll.setSoTimeout(timeoutMs);
        try {
            return receive();
        }
        catch (SocketTimeoutException e) {
            return null;
        }
        finally {
            dll.setSoTimeout(1000);
        }
    }

    @Test
    public void theTestJvmAnswersLikeAndroid() {
        /* guards the setting the next test depends on: without it the Mac
         * JVM says 127.0.0.1 and the trap below cannot show */
        assertEquals("::1 expected; is -Djava.net.preferIPv6Addresses=true set for unit tests?",
                     "0:0:0:0:0:0:0:1", InetAddress.getLoopbackAddress().getHostAddress());
    }

    @Test
    public void sendsToIpv4LoopbackWhereTheDllListens() throws Exception {
        /* THE TRAP: getLoopbackAddress() is ::1 on Android, and a datagram
         * to ::1 never reaches a socket bound to 127.0.0.1 */
        assertTrue(PenSampleSender.DLL_ADDRESS instanceof Inet4Address);
        assertEquals("127.0.0.1", PenSampleSender.DLL_ADDRESS.getHostAddress());
        sender.send(700, 10, 10, 1);
        String[] f = receiveOrNull(1000);
        assertNotNull("a listener on IPv4 127.0.0.1, like the DLL, got nothing", f);
        assertEquals("700", f[1]);
    }

    @Test
    public void samplesArriveInOrderWithRisingSequenceNumbers() throws Exception {
        for (int i = 0; i < 50; i++) sender.send(100 + i, i, 10, 1000 + i);
        for (int i = 0; i < 50; i++) {
            String[] f = receive();
            assertEquals(10, f.length);
            assertEquals(String.valueOf(i), f[0]);
            assertEquals(String.valueOf(100 + i), f[1]);
            assertEquals(String.valueOf(i * 8), f[2]);
            assertEquals(String.valueOf((1080 - 10) * 8), f[3]);
            assertEquals("15360", f[4]);
            assertEquals("8640", f[5]);
            assertEquals(String.valueOf(1000 + i), f[6]);
            assertEquals("1", f[9]);
        }
    }

    @Test
    public void hoveringStillKeepsSendingSoSaiKeepsItsBrushCursor() throws Exception {
        sender.send(0, 300, 200, 5);
        sender.setInRange(true);
        String[] first = receive();
        assertEquals("0", first[1]);
        /* the pen hovers still: resends every ~50 ms, same place, no event time */
        for (int i = 0; i < 3; i++) {
            String[] f = receive();
            assertEquals("0", f[1]);
            assertEquals(first[2], f[2]);
            assertEquals(first[3], f[3]);
            assertEquals("0", f[9]);
            assertTrue(Long.parseLong(f[0]) > Long.parseLong(first[0]));
        }
    }

    @Test
    public void noResendsOncePenLeaves() throws Exception {
        sender.send(0, 300, 200, 5);
        sender.setInRange(true);
        receive();
        sender.setInRange(false);
        /* drain what was already in flight, then silence */
        /* (capped: if resends never stop, fail instead of hanging) */
        for (int i = 0; i < 3 && receiveOrNull(120) != null; i++) { }
        assertNull(receiveOrNull(300));
    }

    @Test
    public void noResendsWhileTouching() throws Exception {
        sender.setInRange(true);
        sender.send(900, 300, 200, 5);
        receive();
        assertNull(receiveOrNull(300));
    }

    @Test
    public void statusFileCountsWhatWasSent() throws Exception {
        /* C:\saipen_status.txt: the app's half of "where did the samples go",
         * next to the DLL's recv= in C:\wt_status.txt */
        File status = new File(tmp.getRoot(), "saipen_status.txt");
        PenSampleSender.statusFile = status;
        for (int i = 0; i < 3; i++) sender.send(500, 10, 10, 1 + i);
        for (int i = 0; i < 3; i++) receive();

        String text = "";
        for (int waited = 0; waited < 3000 && !text.contains("sent=3\n"); waited += 100) {
            Thread.sleep(100);
            if (status.isFile()) text = new String(Files.readAllBytes(status.toPath()), StandardCharsets.US_ASCII);
        }
        assertTrue(text, text.contains("sent=3\n"));
        assertTrue(text, text.contains("failed=0\n"));
        assertTrue(text, text.contains("target=127.0.0.1:" + dll.getLocalPort() + "\n"));
        assertTrue(text, text.contains("screen=1920x1080\n"));
    }
}
