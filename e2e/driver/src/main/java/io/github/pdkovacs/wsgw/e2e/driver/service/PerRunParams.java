package io.github.pdkovacs.wsgw.e2e.driver.service;

import java.time.Duration;

/**
 * What a caller chooses anew for every test run.
 *
 * @param userCount            how many users of the credentials pool connect, counted from its start
 * @param messageCount         how many messages are sent during the run
 * @param messageSendChunkSize how many messages are sent to the app service in one request
 * @param timeout              how long the run may take altogether
 */
public record PerRunParams(int userCount, int messageCount, int messageSendChunkSize, Duration timeout) {
}
