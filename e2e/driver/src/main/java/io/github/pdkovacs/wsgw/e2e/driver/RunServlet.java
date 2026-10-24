package io.github.pdkovacs.wsgw.e2e.driver;

import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

public class RunServlet extends HttpServlet {

    private static final CtxLogger logger = CtxLogger.of(RunServlet.class);

    private final AtomicReference<TestRun> running = new AtomicReference<>();

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        var testRun = TestRun.create(() -> running.set(null));
        if (running.compareAndExchange(null, testRun) != null) {
            logger.info("A test is already running");
            resp.sendError(429 /*Too Many Requests*/, "Retry later");
            return;
        };
        try {
            testRun.start();
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("UTF-8");
            resp.setStatus(HttpServletResponse.SC_ACCEPTED);
            resp.getWriter().write(testRun.getTestRunId());
        } catch (Exception e) {
            logger.error("Exception in RunServlet", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
