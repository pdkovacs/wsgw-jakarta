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
        try {
            testRunner.stopTestRun(true);
        } catch (IllegalStateException e) {
            logger.error("Stopping test run with id failed", e);
            resp.sendError(HttpServletResponse.SC_CONFLICT);
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for Tomcat to stop");
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
