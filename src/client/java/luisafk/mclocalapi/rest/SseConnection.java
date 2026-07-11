package luisafk.mclocalapi.rest;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public class SseConnection {

    private final HttpExchange exchange;
    private final OutputStream out;
    private volatile boolean closed = false;
    private Runnable onCloseCallback;

    public SseConnection(HttpExchange exchange) throws IOException {
        this.exchange = exchange;
        this.out = exchange.getResponseBody();
    }

    public void onClose(Runnable callback) {
        this.onCloseCallback = callback;
    }

    public synchronized void sendEvent(String data) {
        sendEvent("message", data);
    }

    public synchronized void sendEvent(String event, String data) {
        if (closed) {
            throw new IllegalStateException("SSE connection is closed");
        }

        try {
            String payload = "event: " + event + "\ndata: " + data + "\n\n";
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            close();
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void sendComment(String comment) {
        if (closed) {
            return;
        }

        try {
            out.write(
                (": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8)
            );
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;

        try {
            out.close();
        } catch (IOException e) {
            // ignore
        }

        exchange.close();

        if (onCloseCallback != null) {
            onCloseCallback.run();
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
