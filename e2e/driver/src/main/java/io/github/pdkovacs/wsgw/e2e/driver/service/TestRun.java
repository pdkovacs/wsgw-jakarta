package io.github.pdkovacs.wsgw.e2e.driver.service;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;
import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * One e2e test run, following the single-sender model: the run's users only receive, and every
 * message is sent with {@code senderCredentials}. The gateway never sees who the sender is, so
 * senders per user would not change the path under test. For the same reason the number of
 * messages is independent of the number of users.
 */
public class TestRun implements Runnable {

    private static final CtxLogger logger = CtxLogger.of(TestRun.class);

    /**
     * With at most this many candidates, a message goes to all of them; above it, to a random subset.
     */
    private static final int ALL_RECIPIENTS_UP_TO = 10;

    private final HttpClient httpClient;
    private final String testRunId;
    private final List<PasswordCredentials> testUsers;
    private final int messageCount;
    private final PasswordCredentials senderCredentials;
    private final Monitoring monitoring;

    private Map<String, Receiver> receivers;
    private List<Message> messages;

    private TestRun(HttpClient httpClient, String testRunId, List<PasswordCredentials> testUsers, int messageCount,
                    PasswordCredentials senderCredentials, Monitoring monitoring) {
        this.httpClient = httpClient;
        this.testRunId = testRunId;
        this.testUsers = testUsers;
        this.messageCount = messageCount;
        this.senderCredentials = senderCredentials;
        this.monitoring = monitoring;
    }

    // -----------------------------------------------------------------------------
    // --- lifecycle ---
    // -----------------------------------------------------------------------------

    /**
     * @param userCount    how many users of {@code allCredentials} connect, counted from its start
     * @param messageCount how many messages are sent during the run
     * @throws IllegalArgumentException if either count is out of range
     */
    public static TestRun create(HttpClient httpClient, int userCount, int messageCount,
                                 List<PasswordCredentials> allCredentials, PasswordCredentials senderCredentials,
                                 Monitoring monitoring) {
        if (userCount < 1 || userCount > allCredentials.size()) {
            throw new IllegalArgumentException(
                    "user-count must be between 1 and %d, got %d".formatted(allCredentials.size(), userCount));
        }
        if (messageCount < 1) {
            throw new IllegalArgumentException("message-count must be at least 1, got %d".formatted(messageCount));
        }
        return new TestRun(httpClient, UUID.randomUUID().toString(), List.copyOf(allCredentials.subList(0, userCount)),
                messageCount, senderCredentials, monitoring);
    }

    public String getTestRunId() {
        return testRunId;
    }

    public void run() {
        try {
            receivers = connectReceivers();
            messages = createMessages();
        } catch (Exception e) {
            logger.error("Exception occurred while connecting to test run", e);
        } finally {
            logger.info("Test run finished" + testRunId);
        }
    }

    public void start() {
        try {
            Thread.ofVirtual().start(this);
        } catch (Throwable t) {
            logger.error("Test run failed", t);
            throw new RuntimeException(t);
        }
    }

    private Map<String, Receiver> connectReceivers() {
        return null;
    }

    // -----------------------------------------------------------------------------
    // --- test data ---
    // -----------------------------------------------------------------------------

    List<Message> createMessages() {
        List<String> recipientCandidates = testUsers.stream().map(PasswordCredentials::username).toList();

        List<Message> created = new ArrayList<>(messageCount);
        for (int i = 0; i < messageCount; i++) {
            created.add(createMessage(recipientCandidates));
        }
        return created;
    }

    private Message createMessage(List<String> recipientCandidates) {
        List<String> recipients = selectRecipients(recipientCandidates, ThreadLocalRandom.current());
        monitoring.incMsgRecipientToReachCounter().accept(recipients.size());

        var msgId = UUID.randomUUID().toString();
        var text = "[%s] %s".formatted(msgId, "a test message");
        var msg = new Message(testRunId, msgId, text, recipients);

        monitoring.incMsgCreatedCounter().run();

        return msg;
    }

    /**
     * Above {@link #ALL_RECIPIENTS_UP_TO} candidates, selects between half of them and all but a
     * couple, each subset of that size being equally likely (selection sampling).
     */
    static List<String> selectRecipients(List<String> candidates, RandomGenerator random) {
        int n = candidates.size();
        if (n <= ALL_RECIPIENTS_UP_TO) {
            return candidates;
        }

        int howMany = random.nextInt(n / 2 - 1) + n / 2;

        List<String> result = new ArrayList<>(howMany);
        int k = howMany; // elements still needed

        for (int i = 0; i < n; i++) {
            int remaining = n - i; // elements left to visit (including current)

            // Probability of selecting current element = k / remaining
            if (random.nextInt(remaining) < k) {
                result.add(candidates.get(i));
                k--; // one less element needed
                if (k == 0) {
                    break; // done selecting
                }
            }
        }

        return result;
    }
}
