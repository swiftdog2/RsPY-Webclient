import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Socket-shaped wrapper around a WebSocket.
 *
 * The old 317 client expects a java.net.Socket with:
 *   getInputStream()
 *   getOutputStream()
 *   setSoTimeout()
 *   setTcpNoDelay()
 *   close()
 *
 * This class keeps that surface area, but the real transport is WebSocket.
 */
public final class WebSocketSocket extends Socket {

    private final Object lock = new Object();

    private final URI uri;
    private final WebSocket webSocket;
    private final InputStream inputStream;
    private final OutputStream outputStream;

    private final byte[] readBuffer = new byte[1 << 20];
    private int readHead = 0;
    private int readTail = 0;
    private int readSize = 0;

    private volatile boolean closed = false;
    private volatile boolean connected = false;
    private volatile int soTimeoutMillis = 30000;

    public WebSocketSocket(String url) throws IOException {
        try {
            this.uri = URI.create(url);

            this.inputStream = new Input();
            this.outputStream = new Output();

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            this.webSocket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(this.uri, new Listener())
                    .get(15, TimeUnit.SECONDS);

            this.connected = true;

            System.out.println("[RSPY WEBCLIENT] Connected WebSocket transport: " + url);
        } catch (Exception e) {
            throw new IOException("Failed to open WebSocket transport: " + url, e);
        }
    }

    private int bufferedAvailable() {
        synchronized (lock) {
            return readSize;
        }
    }

    private void feed(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        synchronized (lock) {
            if (closed) {
                return;
            }

            for (byte b : data) {
                if (readSize >= readBuffer.length) {
                    readHead = (readHead + 1) % readBuffer.length;
                    readSize--;
                }

                readBuffer[readTail] = b;
                readTail = (readTail + 1) % readBuffer.length;
                readSize++;
            }

            lock.notifyAll();
        }
    }

    private int readOne() throws IOException {
        byte[] one = new byte[1];
        int n = readBytes(one, 0, 1);
        return n <= 0 ? -1 : (one[0] & 0xff);
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

        long deadline = 0L;
        boolean timed = soTimeoutMillis > 0;

        if (timed) {
            deadline = System.currentTimeMillis() + soTimeoutMillis;
        }

        synchronized (lock) {
            while (readSize == 0 && !closed) {
                try {
                    if (!timed) {
                        lock.wait();
                    } else {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0L) {
                            throw new SocketTimeoutException("WebSocket read timed out");
                        }

                        lock.wait(remaining);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading WebSocket stream", e);
                }
            }

            if (readSize == 0 && closed) {
                return -1;
            }

            int n = Math.min(len, readSize);

            for (int i = 0; i < n; i++) {
                dst[off + i] = readBuffer[readHead];
                readHead = (readHead + 1) % readBuffer.length;
                readSize--;
            }

            return n;
        }
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
            throw new SocketException("WebSocket socket is closed");
        }

        byte[] copy = new byte[len];
        System.arraycopy(src, off, copy, 0, len);

        try {
            webSocket.sendBinary(ByteBuffer.wrap(copy), true).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            close();
            throw new IOException("Failed to write WebSocket payload", e);
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
        // No-op. WebSocket transport has its own framing.
    }

    @Override
    public boolean getTcpNoDelay() {
        return true;
    }

    @Override
    public boolean isConnected() {
        return connected && !closed;
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
        connected = false;

        synchronized (lock) {
            lock.notifyAll();
        }

        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
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
        public int available() {
            return bufferedAvailable();
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
            // sendBinary already queues the frame.
        }

        @Override
        public void close() {
            WebSocketSocket.this.close();
        }
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            connected = true;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            feed(copy);
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            connected = false;
            synchronized (lock) {
                lock.notifyAll();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed = true;
            connected = false;
            synchronized (lock) {
                lock.notifyAll();
            }
            System.out.println("[RSPY WEBCLIENT] WebSocket error: " + error);
        }
    }
}
