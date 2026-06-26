import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Base64;

/**
 * CheerpJ browser WebSocket transport.
 *
 * This replaces the broken java.net.http.WebSocket version.
 *
 * It does NOT use:
 *   java.net.http.HttpClient
 *   java.net.http.WebSocket
 *   java.nio.channels.Selector
 *   netscape.javascript.JSObject (applet LiveConnect, removed in JDK 11 and
 *                                 not implemented by CheerpJ 3)
 *
 * Instead, it calls browser JavaScript through CheerpJ native methods. Each
 * native below is implemented in JavaScript as Java_WebSocketSocket_<name> and
 * registered in cheerpjInit({ natives: { ... } }). Those JS shims forward to the
 * existing window.rspyWs* bridge functions supplied by rspy-ws-bridge.js.
 *
 * Required page wiring:
 *   <script src="/rspy-ws-bridge.js"></script>   // defines window.rspyWs*
 *   cheerpjInit({ natives: { Java_WebSocketSocket_rspyWsOpenN, ... } })
 */
public final class WebSocketSocket extends Socket {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int POLL_SLEEP_MS = 5;

    /*
     * CheerpJ native bridge. Implemented in JS as Java_WebSocketSocket_<name>
     * and registered via cheerpjInit({ natives: { ... } }).
     */
    private static native int rspyWsOpenN(String url);
    private static native int rspyWsStateN(int id);
    private static native int rspyWsAvailableN(int id);
    private static native String rspyWsReadN(int id, int len);
    private static native boolean rspyWsSendN(int id, String b64);
    private static native String rspyWsErrorN(int id);
    private static native void rspyWsCloseN(int id);

    private final int socketId;
    private final String url;

    private final InputStream inputStream;
    private final OutputStream outputStream;

    private volatile boolean closed = false;
    private volatile int soTimeoutMillis = 30000;

    public WebSocketSocket(String url) throws IOException {
        this.url = url;

        int opened;

        try {
            opened = rspyWsOpenN(url);
        } catch (UnsatisfiedLinkError e) {
            throw new IOException(
                    "CheerpJ native bridge not registered. Add the Java_WebSocketSocket_* " +
                    "functions to cheerpjInit({ natives: { ... } }) and ensure rspy-ws-bridge.js " +
                    "is loaded before cheerpjRunJar().",
                    e
            );
        }

        this.socketId = opened;

        waitForOpen();

        this.inputStream = new Input();
        this.outputStream = new Output();

        System.out.println("[RSPY WEBCLIENT] Connected browser JS WebSocket transport: " + url + " id=" + socketId);
    }

    private void waitForOpen() throws IOException {
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            int state = state();

            if (state == 1) {
                return;
            }

            if (state == 3 || state == 4) {
                String err = String.valueOf(rspyWsErrorN(socketId));
                throw new IOException("WebSocket failed to open. state=" + state + " error=" + err);
            }

            sleepQuietly(POLL_SLEEP_MS);
        }

        throw new SocketTimeoutException("Timed out opening browser WebSocket: " + url);
    }

    private int state() throws IOException {
        return rspyWsStateN(socketId);
    }

    private int availableBytes() throws IOException {
        return rspyWsAvailableN(socketId);
    }

    private int readBytes(byte[] dst, int off, int len) throws IOException {
        if (dst == null) {
            throw new NullPointerException("dst");
        }

        if (off < 0 || len < 0 || off + len > dst.length) {
            throw new IndexOutOfBoundsException();
        }

        if (len == 0) {
            return 0;
        }

        long deadline = soTimeoutMillis > 0 ? System.currentTimeMillis() + soTimeoutMillis : 0L;

        while (!closed) {
            int available = availableBytes();

            if (available > 0) {
                int wanted = Math.min(len, available);

                String encoded = rspyWsReadN(socketId, wanted);

                if (encoded == null || encoded.length() == 0 || "null".equals(encoded)) {
                    continue;
                }

                byte[] decoded = Base64.getDecoder().decode(encoded);
                int n = Math.min(len, decoded.length);
                System.arraycopy(decoded, 0, dst, off, n);
                return n;
            }

            int state = state();

            if (state == 3 || state == 4) {
                closed = true;
                return -1;
            }

            if (soTimeoutMillis > 0 && System.currentTimeMillis() >= deadline) {
                throw new SocketTimeoutException("WebSocket read timed out.");
            }

            sleepQuietly(POLL_SLEEP_MS);
        }

        return -1;
    }

    private int readOne() throws IOException {
        byte[] one = new byte[1];
        int n = readBytes(one, 0, 1);
        return n <= 0 ? -1 : (one[0] & 0xff);
    }

    private void sendBytes(byte[] src, int off, int len) throws IOException {
        if (src == null) {
            throw new NullPointerException("src");
        }

        if (off < 0 || len < 0 || off + len > src.length) {
            throw new IndexOutOfBoundsException();
        }

        if (len == 0) {
            return;
        }

        if (closed) {
            throw new SocketException("WebSocket socket is closed.");
        }

        byte[] copy = new byte[len];
        System.arraycopy(src, off, copy, 0, len);

        String encoded = Base64.getEncoder().encodeToString(copy);

        boolean ok = rspyWsSendN(socketId, encoded);

        if (!ok) {
            int state = state();
            String err = String.valueOf(rspyWsErrorN(socketId));
            throw new IOException("WebSocket send failed. state=" + state + " error=" + err);
        }
    }

    private static void sleepQuietly(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public OutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public synchronized void setSoTimeout(int timeout) {
        soTimeoutMillis = timeout;
    }

    @Override
    public synchronized int getSoTimeout() {
        return soTimeoutMillis;
    }

    @Override
    public void setTcpNoDelay(boolean on) {
        // No-op for browser WebSocket transport.
    }

    @Override
    public boolean getTcpNoDelay() {
        return true;
    }

    @Override
    public boolean isConnected() {
        try {
            return !closed && state() == 1;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;

        try {
            rspyWsCloseN(socketId);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void shutdownInput() {
        close();
    }

    @Override
    public void shutdownOutput() {
        close();
    }

    private final class Input extends InputStream {
        @Override
        public int read() throws IOException {
            return readOne();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return readBytes(b, off, len);
        }

        @Override
        public int available() throws IOException {
            return availableBytes();
        }

        @Override
        public void close() {
            WebSocketSocket.this.close();
        }
    }

    private final class Output extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            byte[] one = new byte[] { (byte) b };
            sendBytes(one, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            sendBytes(b, off, len);
        }

        @Override
        public void flush() {
            // Browser WebSocket sends immediately.
        }

        @Override
        public void close() {
            WebSocketSocket.this.close();
        }
    }
}
