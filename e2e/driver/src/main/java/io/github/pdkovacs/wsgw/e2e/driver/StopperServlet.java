package io.github.pdkovacs.wsgw.e2e.driver;

import io.github.pdkovacs.wsgw.e2e.driver.service.TestRunner;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class StopperServlet extends HttpServlet {
    private static final CtxLogger logger = CtxLogger.of(StopperServlet.class);

    private final TestRunner testRunner;

    public StopperServlet(TestRunner testRunner) {
        this.testRunner = testRunner;
    }

    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Expects DELETE <mapping>/{runId}, i.e. the servlet mapped to "/run/*"
        String pathInfo = req.getPathInfo(); // "/{runId}", or null when no path follows the mapping
        if (pathInfo == null || pathInfo.length() <= 1 || pathInfo.indexOf('/', 1) != -1) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "expected path: .../{runId}");
            return;
        }
        String runId = pathInfo.substring(1);

        try {
            testRunner.stopTestRun(runId);
        } catch (IllegalArgumentException e) {
            logger.error("Stopping test run with id " + runId + " failed", e);
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
        } catch (IllegalStateException e) {
            logger.error("Stopping test run with id " + runId + " failed", e);
            resp.sendError(HttpServletResponse.SC_CONFLICT);
        }
    }
}
