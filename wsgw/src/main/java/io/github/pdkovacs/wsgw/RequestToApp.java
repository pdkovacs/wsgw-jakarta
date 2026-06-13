package io.github.pdkovacs.wsgw;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class RequestToApp {

    private static final Logger log = LoggerFactory.getLogger(RequestToApp.class);

    static final String CONN_ID_HEADER = "X-WSGW-CONNECTION-ID";
    // Hop-by-hop WebSocket upgrade headers (plus host/content-length, which the
    // java.net.http client manages itself). These are stripped before relaying
    // the client's headers to the backend, since the WS upgrade happens between
    // the client and wsgw, not on the wsgw->backend leg.
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "host",
            "upgrade",
            "connection",
            "content-length",
            "sec-websocket-key",
            "sec-websocket-version",
            "sec-websocket-extensions",
            "sec-websocket-protocol");

    // One shared client for the whole gateway: its selector, thread pool and
    // (keep-alive) connection pool are reused across every WS connection, instead
    // of being built up and torn down per request.
    public static HttpClient appClient = createHttpClient();


    public static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    private static boolean isRestricted(String headerName) {
        return RESTRICTED_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }

    static HttpResponse<Void> send(String appBaseUrl, Map<String, List<String>> reqHeaders, String connectionId,
                                   String appPath, String reqMethod, String body) throws IOException, InterruptedException {
        log.debug("Building request {} to app at {}", connectionId, appPath);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(appBaseUrl + appPath));
        requestBuilder.setHeader(Wsgw.X_WSGW_CONNECTION_ID, connectionId);
        log.debug("Setting request method ({})", connectionId);
        if (reqMethod != null) {
            requestBuilder.method(reqMethod, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        }

        log.debug("Assembling headers... ({}): {}", connectionId, reqHeaders);
        var headerNames = reqHeaders.keySet();
        for (String headerName : headerNames) {
            if (isRestricted(headerName)) {
                continue;
            }
            // header() (vs setHeader()) preserves multi-valued headers.
            reqHeaders.get(headerName).forEach(value -> requestBuilder.header(headerName, value));
        }

        log.debug("Sending request... ({})", connectionId);
        var response = appClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
        log.debug("Request {} returned {}", connectionId, response.statusCode());
        return response;
    }

    public static Map<String, List<String>> getRequestHeaders(HttpServletRequest req) {
        var map = new HashMap<String, List<String>>();
        for (var headerName : Collections.list(req.getHeaderNames())) {
            map.put(headerName, List.of(req.getHeader(headerName)));
        }
        return map;
    }
}
