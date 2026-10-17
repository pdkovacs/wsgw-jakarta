package io.github.pdkovacs.wsgw.version;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Serves a module's {@link VersionInfo} as JSON. */
public class VersionServlet extends HttpServlet {

    public static final String PATH = "/version";

    private final String json;

    /** @param anchor a class of the module whose build info is reported (see {@link VersionInfo#load}) */
    public VersionServlet(Class<?> anchor) {
        this.json = VersionInfo.load(anchor).toJson();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(json);
    }
}
