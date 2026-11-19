package com.winlator.saipen;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends pen samples to our wintab32.dll on UDP 127.0.0.1:47800, in the same
 * line format as the Mac helper (see {@link PenWire}).
 *
 * Android forbids network calls on the UI thread, where touch events
 * arrive, so the UI thread only hands each sample to one sender thread. One
 * thread also keeps the datagrams in the order the samples happened.
 *
 * Wine runs as a child process of this app, in the same network namespace,
 * so the loopback datagram reaches the DLL inside SAI.
 *
 * Once a second the sender writes C:\saipen_status.txt (when the session set
 * {@link #statusFile}): how many samples it sent and whether sending failed.
 * Next to the DLL's own C:\wt_status.txt that tells which side lost them.
 *
 * No Android types: the JVM tests send through a real loopback socket.
 */
public final class PenSampleSender {
    public static final int SAMPLE_PORT = 47800;   /* wintab32.c SAMPLE_PORT */

    /**
     * Where the DLL listens: IPv4 127.0.0.1 (wintab32.c open_sample_socket
     * binds AF_INET INADDR_LOOPBACK, nothing else).
     *
     * THE TRAP: not InetAddress.getLoopbackAddress(). On Android that is
     * always the IPv6 loopback ::1 (libcore InetAddress: "Android-changed:
     * Always returns IPv6 loopback address in Android"), and a datagram to
     * ::1 never reaches a socket bound to 127.0.0.1. Version 0.1.0 did that:
     * SAI ran and drew, but the DLL received 0 samples (recv=0), on a Galaxy
     * Tab S6 Lite, 2026-11-18. The Mac's JVM returns 127.0.0.1, so the tests
     * run with -Djava.net.preferIPv6Addresses=true to behave like Android.
     */
    public static final InetAddress DLL_ADDRESS = ipv4Loopback();

    /** Set by SaiPenSetup for the running session: C:\saipen_status.txt. */
    public static volatile File statusFile;

    private static final long KEEPALIVE_TICK_MS = 25;
    private static final long STATUS_EVERY_MS = 1000;

    private final ScheduledExecutorService thread;
    private final SocketAddress target;
    private DatagramSocket socket;
    private volatile int screenW, screenH;

    /* touched only on the sender thread */
    private long seq = 0;
    private int lastP = -1;
    private float lastX, lastY;
    private long lastSendMs = 0;
    private boolean inRange = false;
    private long sent = 0, failed = 0;
    private String lastError = "";
    private String lastStatus = "";

    public PenSampleSender(int screenW, int screenH) {
        this(SAMPLE_PORT, screenW, screenH);
    }

    /** Tests use their own port; the address is always the DLL's. */
    public PenSampleSender(int port, int screenW, int screenH) {
        this.target = new InetSocketAddress(DLL_ADDRESS, port);
        this.screenW = screenW;
        this.screenH = screenH;
        this.thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pen-sample-sender");
            t.setDaemon(true);
            return t;
        });
        thread.scheduleWithFixedDelay(this::keepAlive, KEEPALIVE_TICK_MS, KEEPALIVE_TICK_MS,
                                      TimeUnit.MILLISECONDS);
        thread.scheduleWithFixedDelay(this::writeStatus, STATUS_EVERY_MS, STATUS_EVERY_MS,
                                      TimeUnit.MILLISECONDS);
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        }
        catch (UnknownHostException e) {
            throw new AssertionError(e);    /* 4 bytes is always a valid address */
        }
    }

    public void setScreenSize(int w, int h) {
        screenW = w;
        screenH = h;
    }

    /** p: 0 hovering, 1..pmax touching. sx, sy: X screen point (y down). */
    public void send(int p, float sx, float sy, long evUs) {
        post(() -> {
            lastP = p;
            lastX = sx;
            lastY = sy;
            transmit(p, sx, sy, evUs, evUs > 0);
        });
    }

    public void setInRange(boolean inRange) {
        post(() -> this.inRange = inRange);
    }

    /** A control line for the DLL, e.g. PenWire.zoomLine(): same socket and
     *  thread as the samples, so it keeps its place among them. */
    public void sendControl(String line) {
        if (line == null) return;
        post(() -> transmitRaw(line.getBytes(StandardCharsets.US_ASCII)));
    }

    public void close() {
        post(this::writeStatus);
        thread.shutdown();
        try {
            thread.awaitTermination(200, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (socket != null) socket.close();
    }

    /** What C:\saipen_status.txt says. Only on the sender thread. */
    String statusText() {
        return "target=" + DLL_ADDRESS.getHostAddress() + ":" + ((InetSocketAddress) target).getPort() + "\n"
            + "screen=" + screenW + "x" + screenH + "\n"
            + "sent=" + sent + "\n"
            + "failed=" + failed + "\n"
            + "last_error=" + lastError + "\n";
    }

    private void post(Runnable r) {
        try {
            thread.execute(r);
        }
        catch (RejectedExecutionException ignored) {
            /* closed: the session is ending */
        }
    }

    private void keepAlive() {
        if (lastP < 0) return;
        long since = nowMs() - lastSendMs;
        if (PenWire.keepAliveShouldResend(inRange, lastP, since)) {
            /* a resend is not a new pen event: no event time (evok 0) */
            transmit(0, lastX, lastY, 0, false);
        }
    }

    private void transmit(int p, float sx, float sy, long evUs, boolean evOk) {
        int[] w = PenWire.toWire(sx, sy, screenW, screenH);
        String line = PenWire.line(seq++, p, w[0], w[1], w[2], w[3],
                                   evUs, evOk, System.nanoTime() / 1000, 0);
        lastSendMs = nowMs();
        transmitRaw(line.getBytes(StandardCharsets.US_ASCII));
    }

    private void transmitRaw(byte[] bytes) {
        try {
            if (socket == null) socket = new DatagramSocket();
            socket.send(new DatagramPacket(bytes, bytes.length, target));
            sent++;
        }
        catch (IOException e) {
            /* nobody listening yet (SAI still starting) is normal for UDP and
             * does not even fail here; anything that does is counted */
            failed++;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private void writeStatus() {
        File f = statusFile;
        if (f == null) return;
        String text = statusText();
        if (text.equals(lastStatus)) return;
        try {
            /* whole file or nothing: Notepad in Wine may read it any moment */
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), text.getBytes(StandardCharsets.US_ASCII));
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);
            lastStatus = text;
        }
        catch (IOException ignored) {
            /* the next second tries again */
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1000000;
    }
}
