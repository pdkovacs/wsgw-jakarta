package io.github.pdkovacs.wsgw.e2e.driver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Just enough RFC 6455 to serve the client, on a raw {@link ServerSocket}, so that tests can count
 * TCP accepts and see the raw handshake request: answers plain requests with a keep-alive 200, and
 * an upgrade with a 101, then {@code messages} text frames, then echoes the client's close.
 */
public final class FakeWsServer implements AutoCloseable {

    public record Request(String requestLine, Map<String, String> headers, int clientPort) {
    }

    private static final Pattern MESSAGES = Pattern.compile("messages=(\\d+)");

    public final AtomicInteger accepts = new AtomicInteger();
    public final AtomicInteger closes = new AtomicInteger();
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final ServerSocket serverSocket;
    private final Set<String> rejectedAuthorizations = new CopyOnWriteArraySet<>();
    private volatile CountDownLatch frameGate = new CountDownLatch(0);

    public FakeWsServer() throws IOException {
        serverSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("fake-ws-acceptor").start(this::acceptLoop);
    }

    public URI httpUri() {
        return URI.create("http://127.0.0.1:%d/plain".formatted(serverSocket.getLocalPort()));
    }

    /** The host and port, the way the driver is configured with a gateway. */
    public String hostAndPort() {
        return "127.0.0.1:%d".formatted(serverSocket.getLocalPort());
    }

    public URI wsUri(int messages) {
        return URI.create("ws://127.0.0.1:%d/ws?messages=%d".formatted(serverSocket.getLocalPort(), messages));
    }

    /** From now on, a handshake's text frames wait for {@link #releaseFrames()}. */
    public void holdFrames() {
        frameGate = new CountDownLatch(1);
    }

    public void releaseFrames() {
        frameGate.countDown();
    }

    /** From now on, handshakes with this {@code Authorization} header value get a 401. */
    public void rejectAuthorization(String authorization) {
        rejectedAuthorizations.add(authorization);
    }

    @Override
    public void close() throws IOException {
        releaseFrames();
        serverSocket.close();
        for (var socket : sockets) {
            socket.close();
        }
    }

    private void acceptLoop() {
        while (true) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                return; // closed
            }
            accepts.incrementAndGet();
            sockets.add(socket);
            Thread.ofVirtual().start(() -> serve(socket));
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            var in = socket.getInputStream();
            var out = socket.getOutputStream();
            while (true) {
                var requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) {
                    return;
                }
                var headers = new LinkedHashMap<String, String>();
                for (var line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
                    int colon = line.indexOf(':');
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
                requests.add(new Request(requestLine, headers, socket.getPort()));

                if (rejectedAuthorizations.contains(headers.get("authorization"))) {
                    out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    continue;
                }

                if (!"websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    continue;
                }
                serveWebSocket(requestLine, headers, in, out);
                return;
            }
        } catch (Exception e) {
            // the client went away; nothing to do
        }
    }

    private void serveWebSocket(String requestLine, Map<String, String> headers, InputStream in, OutputStream out)
            throws Exception {
        var accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                (headers.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                        .getBytes(StandardCharsets.US_ASCII)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        frameGate.await();
        var matcher = MESSAGES.matcher(requestLine);
        int messages = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        for (int i = 1; i <= messages; i++) {
            writeFrame(out, 0x1, ("m" + i).getBytes(StandardCharsets.UTF_8));
        }
        out.flush();

        while (true) {
            int b0 = in.read();
            if (b0 < 0) {
                return;
            }
            int b1 = in.read();
            long length = b1 & 0x7f;
            if (length == 126) {
                length = (in.read() << 8) | in.read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | in.read();
                }
            }
            if ((b1 & 0x80) != 0) {
                in.readNBytes(4); // mask; the payload is never looked at
            }
            in.readNBytes((int) length);
            if ((b0 & 0x0f) == 0x8) {
                closes.incrementAndGet();
                writeFrame(out, 0x8, new byte[]{0x03, (byte) 0xe8}); // 1000, normal closure
                out.flush();
                return;
            }
        }
    }

    private static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode); // FIN
        out.write(payload.length); // all our payloads are < 126 bytes, server frames are unmasked
        out.write(payload);
    }

    private static String readLine(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int b = in.read(); b != '\n'; b = in.read()) {
            if (b < 0) {
                return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.US_ASCII);
            }
            if (b != '\r') {
                bytes.write(b);
            }
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }
}
