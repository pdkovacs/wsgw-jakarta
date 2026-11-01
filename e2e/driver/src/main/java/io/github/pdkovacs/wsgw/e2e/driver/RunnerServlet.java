package io.github.pdkovacs.wsgw.e2e.driver;

import io.github.pdkovacs.wsgw.e2e.driver.service.TestRun;
import io.github.pdkovacs.wsgw.e2e.driver.service.TestRunner;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;

public class RunnerServlet extends HttpServlet {

    public static final String PATH = "/run";

    private static final CtxLogger logger = CtxLogger.of(RunnerServlet.class);

    private final HttpClient httpClient;
    private final TestRunner testRunner;

    public RunnerServlet(HttpClient httpClient, TestRunner testRunner) {
        this.httpClient = httpClient;
        this.testRunner = testRunner;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        final int userCount;
        try {
            userCount = Integer.parseInt(req.getParameter("user-count"));
        } catch (NumberFormatException e) { // also thrown for a missing parameter (null)
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "user-count must be an integer");
            return;
        }

        final int messageCount;
        String messageCountParam = req.getParameter("message-count");
        try {
            // One message per user unless asked otherwise
            messageCount = messageCountParam == null ? userCount : Integer.parseInt(messageCountParam);
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "message-count must be an integer");
            return;
        }

        try {
            String runId = testRunner.createStartTestRun(userCount, messageCount);
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("UTF-8");
            resp.setStatus(HttpServletResponse.SC_ACCEPTED);
            resp.getWriter().write(runId);
        } catch (IllegalArgumentException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            logger.error("Exception in RunnerServlet", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
