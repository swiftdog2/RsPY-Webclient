import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
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
 *
 * Instead, it calls browser JavaScript functions supplied by rspy-ws-bridge.js.
 *
 * Required page script:
 *   <script src="/rspy-ws-bridge.js"></script>
 */
public final class WebSocketSocket extends Socket {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int POLL_SLEEP_MS = 5;

    private static Object jsWindow;
    private static Method jsCallMethod;
    private static Method jsEvalMethod;
    private static boolean jsInitialized = false;

    private final int socketId;
    private final String url;

    private final InputStream inputStream;
    private final OutputStream outputStream;

    private volatile boolean closed = false;
    private volatile int soTimeoutMillis = 30000;

    public WebSocketSocket(String url) throws IOException {
        this.url = url;

        initJavaScriptBridge();

        Object opened = jsCall("rspyWsOpen", new Object[] { url });

        if (!(opened instanceof Number)) {
            throw new IOException("rspyWsOpen did not return a numeric socket id. Returned: " + opened);
        }

        this.socketId = ((Number) opened).intValue();

        waitForOpen();

        this.inputStream = new Input();
        this.outputStream = new Output();

        System.out.println("[RSPY WEBCLIENT] Connected browser JS WebSocket transport: " + url + " id=" + socketId);
    }

    private static synchronized void initJavaScriptBridge() throws IOException {
        if (jsInitialized) {
            return;
        }

        try {
            Class<?> jsObjectClass = Class.forName("netscape.javascript.JSObject");

            /*
             * Old LiveConnect shape:
             *   JSObject.getWindow(Applet)
             *
             * CheerpJ commonly supports applet-era Java apps, so this is the
             * least invasive bridge to try first. We do this by reflection so
             * the source compiles even when the IDE/module setup is awkward.
             */
            Class<?> appletClass = Class.forName("java.applet.Applet");
            Method getWindow = jsObjectClass.getMethod("getWindow", appletClass);

            jsWindow = getWindow.invoke(null, new Object[] { null });
            jsCallMethod = jsObjectClass.getMethod("call", String.class, Object[].class);
            jsEvalMethod = jsObjectClass.getMethod("eval", String.class);

            if (jsWindow == null) {
                throw new IOException("JSObject.getWindow(null) returned null.");
            }

            Object bridgeType = jsEval("typeof window.rspyWsOpen");

            if (!"function".equals(String.valueOf(bridgeType))) {
                throw new IOException(
                        "Browser bridge is not loaded. Missing window.rspyWsOpen. " +
                        "Add <script src=\"/rspy-ws-bridge.js\"></script> before cheerpjRunJar()."
                );
            }

            jsInitialized = true;
            System.out.println("[RSPY WEBCLIENT] JS bridge initialized.");
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(
                    "Could not initialize CheerpJ JavaScript bridge. " +
                    "This build requires CheerpJ/LiveConnect JSObject support.",
                    t
            );
        }
    }

    private static Object jsCall(String name, Object[] args) throws IOException {
        try {
            return jsCallMethod.invoke(jsWindow, name, new Object[] { args });
        } catch (Throwable t) {
            throw new IOException("JavaScript call failed: " + name, t);
        }
    }

    private static Object jsEval(String script) throws IOException {
        try {
            return jsEvalMethod.invoke(jsWindow, script);
        } catch (Throwable t) {
            throw new IOException("JavaScript eval failed.", t);
        }
    }

    private void waitForOpen() throws IOException {
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            int state = state();

            if (state == 1) {
                return;
            }

            if (state == 3 || state == 4) {
                String err = String.valueOf(jsCall("rspyWsError", new Object[] { Integer.valueOf(socketId) }));
                throw new IOException("WebSocket failed to open. state=" + state + " error=" + err);
            }

            sleepQuietly(POLL_SLEEP_MS);
        }

        throw new SocketTimeoutException("Timed out opening browser WebSocket: " + url);
    }

    private int state() throws IOException {
        Object out = jsCall("rspyWsState", new Object[] { Integer.valueOf(socketId) });

        if (out instanceof Number) {
            return ((Number) out).intValue();
        }

        try {
            return Integer.parseInt(String.valueOf(out));
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    private int availableBytes() throws IOException {
        Object out = jsCall("rspyWsAvailable", new Object[] { Integer.valueOf(socketId) });

        if (out instanceof Number) {
            return ((Number) out).intValue();
        }

        try {
            return Integer.parseInt(String.valueOf(out));
        } catch (NumberFormatException e) {
            return 0;
        }
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

                Object encodedObj = jsCall(
                        "rspyWsRead",
                        new Object[] { Integer.valueOf(socketId), Integer.valueOf(wanted) }
                );

                String encoded = String.valueOf(encodedObj);

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

        Object ok = jsCall(
                "rspyWsSend",
                new Object[] { Integer.valueOf(socketId), encoded }
        );

        if (!Boolean.TRUE.equals(ok) && !"true".equals(String.valueOf(ok))) {
            int state = state();
            String err = String.valueOf(jsCall("rspyWsError", new Object[] { Integer.valueOf(socketId) }));
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
            jsCall("rspyWsClose", new Object[] { Integer.valueOf(socketId) });
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
