import java.io.IOException;
import java.net.Socket;

/**
 * Centralized client socket opener.
 *
 * Desktop/offline:
 *   normal TCP socket
 *
 * Browser/CheerpJ:
 *   WebSocket-backed socket
 */
public final class RspyClientSocketFactory {

    private RspyClientSocketFactory() {
    }

    public static Socket open(int port) throws IOException {
        if (isWebClientMode()) {
            String url = System.getProperty("rspy.websocket.url", "wss://ws.rspy.org/");
            System.out.println("[RSPY WEBCLIENT] openSocket(" + port + ") -> " + url);
            return new WebSocketSocket(url);
        }

        String host = System.getProperty("rspy.tcp.host", "127.0.0.1");
        System.out.println("[RSPY DESKTOP] openSocket(" + port + ") -> " + host + ":" + port);
        return new Socket(host, port);
    }

    public static boolean isWebClientMode() {
        String explicit = System.getProperty("rspy.webclient", "");

        if ("true".equalsIgnoreCase(explicit)
                || "1".equals(explicit)
                || "yes".equalsIgnoreCase(explicit)) {
            return true;
        }

        String runtime = (
                System.getProperty("java.vm.name", "") + " " +
                System.getProperty("java.vendor", "") + " " +
                System.getProperty("java.runtime.name", "")
        ).toLowerCase();

        return runtime.contains("cheerp");
    }
}
