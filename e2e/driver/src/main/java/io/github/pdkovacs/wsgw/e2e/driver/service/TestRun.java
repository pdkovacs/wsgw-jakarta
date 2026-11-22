package io.github.pdkovacs.wsgw.e2e.driver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pdkovacs.wsgw.e2e.common.E2eAppPaths;
import io.github.pdkovacs.wsgw.e2e.common.LogKeys;
import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.common.dto.E2EMessage;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;
import io.github.pdkovacs.wsgw.e2e.driver.service.DeliveryTracker.Result;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import java.util.stream.Gatherers;

/**
 * One e2e test run, following the single-sender model: the run's users only receive, and every
 * message is sent with {@code senderCredentials}. The gateway never sees who the sender is, so
 * senders per user would not change the path under test. For the same reason the number of
 * messages is independent of the number of users.
 */
public class TestRun implements Runnable {

    private static final CtxLogger logger = CtxLogger.of(TestRun.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * With at most this many candidates, a message goes to all of them; above it, to a random subset.
     */
    private static final int ALL_RECIPIENTS_UP_TO = 10;

    private static final Duration SEND_MESSAGE_CHUNK_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STOP_JOIN_TIMEOUT = Duration.ofSeconds(30);

    private final CtxLogger iLogger;
    private final HttpClient httpClient;
    private final String wsgwServer;
    private final URI appServiceUrl;
    private final PerRunParams params;
    private final String testRunId;
    private final List<PasswordCredentials> testUsers;
    private final PasswordCredentials senderCredentials;
    private final Monitoring monitoring;
    private final DeliveryTracker deliveryTracker = new DeliveryTracker();
    private final Map<String, Receiver> receivers = new ConcurrentHashMap<>();
    private final Runnable onDone;

    private Thread testRunThread;
    private List<Message> messages;

    private TestRun(HttpClient httpClient, String wsgwServer, URI appServiceUrl, PerRunParams params,
                    String testRunId, List<PasswordCredentials> testUsers, PasswordCredentials senderCredentials,
                    Runnable onDone, Monitoring monitoring) {
        this.iLogger = logger.with(LogKeys.RUN_ID, testRunId);
        this.httpClient = httpClient;
        this.wsgwServer = wsgwServer;
        this.appServiceUrl = appServiceUrl;
        this.params = params;
        this.testRunId = testRunId;
        this.testUsers = testUsers;
        this.senderCredentials = senderCredentials;
        this.monitoring = monitoring;
        this.onDone = onDone;
    }

    // -----------------------------------------------------------------------------
    // --- lifecycle ---
    // -----------------------------------------------------------------------------

    /**
     * @param params the run's own parameters; its user count selects the users from the start of {@code allCredentials}
     * @throws IllegalArgumentException if either count is out of range
     */
    public static TestRun create(HttpClient httpClient, String wsgwServer, URI appServiceUrl, PerRunParams params,
                                 List<PasswordCredentials> allCredentials, PasswordCredentials senderCredentials,
                                 Runnable onDone, Monitoring monitoring) {
        int userCount = params.userCount();
        int messageCount = params.messageCount();
        if (userCount < 1 || userCount > allCredentials.size()) {
            throw new IllegalArgumentException(
                    "user-count must be between 1 and %d, got %d".formatted(allCredentials.size(), userCount));
        }
        if (messageCount < 1) {
            throw new IllegalArgumentException("message-count must be at least 1, got %d".formatted(messageCount));
        }
        return new TestRun(httpClient, wsgwServer, appServiceUrl, params, UUID.randomUUID().toString(),
                List.copyOf(allCredentials.subList(0, userCount)), senderCredentials, onDone, monitoring);
    }

    public String getTestRunId() {
        return testRunId;
    }

    /**
     * If any user fails to connect, the run ends without creating messages, as they could never all
     * be delivered. The sessions opened by then are closed either way.
     */
    public void run() {
        final CompletableFuture<Result> allDeliveredPromise;
        String stage = "initial";
        try {
            stage = "connecting receivers";
            connectReceivers();

            stage = "creating messages";
            messages = createMessages();

            allDeliveredPromise = deliveryTracker.allDelivered();

            stage = "delivering messages";
            startSendingMessages();

            stage = "waiting for all messages to be delivered";
            allDeliveredPromise.get(params.timeout().toNanos(), TimeUnit.NANOSECONDS);
            iLogger.info("Test run finished for {}: {}", testRunId, deliveryTracker.getResult());
        } catch (TimeoutException e) {
            iLogger.error("Timeout while {}: {}", stage, deliveryTracker.getResult(), e);
        } catch (Exception e) {
            iLogger.error("Exception occurred while {}: {}", stage, deliveryTracker.getResult(), e);
        } finally {
            try {
                closeSessions();
            } finally {
                onDone.run();
            }
        }
    }

    public void start() {
        try {
            testRunThread = Thread.ofVirtual().start(this);
        } catch (Throwable t) {
            logger.error("Test run failed", t);
            throw new RuntimeException(t);
        }
    }


    public void stop() throws InterruptedException {
        testRunThread.interrupt();
        if (!testRunThread.join(STOP_JOIN_TIMEOUT)) {
            iLogger.warn("Thread.join() timed out");
        }
    }

    /**
     * Waits for every user's attempt to finish, then throws the first failure, if there was any.
     */
    private void connectReceivers() throws InterruptedException {
        List<Future<?>> attempts = new ArrayList<>(testUsers.size());
        try (var connecting = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var user : testUsers) {
                attempts.add(connecting.submit(() -> {
                    try {
                        var receiver = new Receiver(httpClient, wsgwServer, user, deliveryTracker, monitoring, testRunId);
                        receiver.connectAndListen();
                        receivers.put(user.username(), receiver);
                        return null;
                    } catch (Exception e) {
                        iLogger.with(LogKeys.USER, user.username()).error("Failed to connect", e);
                        throw e;
                    }
                }));
            }
        }

        int failures = 0;
        for (var attempt : attempts) {
            try {
                attempt.get();
            } catch (ExecutionException e) {
                failures++; // already logged, with its user
            }
        }
        if (failures > 0) {
            throw new IllegalStateException("%d of %d users failed to connect".formatted(failures, attempts.size()));
        }
    }

    private void closeSessions() {
        try (var closing = Executors.newVirtualThreadPerTaskExecutor()) {
            receivers.forEach((username, receiver) -> closing.submit(() -> {
                try {
                    receiver.closeSession();
                } catch (Exception e) {
                    iLogger.with(LogKeys.USER, username).warn("Failed to close the session cleanly", e);
                }
            }));
        }
    }

    private void startSendingMessages() {
        List<E2EMessage> messagesToSend = new ArrayList<>();

        for  (var message : messages) {
            var msgDto = new E2EMessage(
                    testRunId,
                    message.getId(),
                    senderCredentials.username(),
                    message.getRecipientsWaiting(),
                    message.getText(),
                    null,
                    null,
                    null);
            messagesToSend.add(msgDto);
        }

        List<List<E2EMessage>> chunks = messagesToSend.stream()
                .gather(Gatherers.windowFixed(params.messageSendChunkSize()))
                .toList();

        AtomicReference<Exception> error = new AtomicReference<>();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var completionService = new ExecutorCompletionService<Void>(executor);
            for (int index = 0; index < chunks.size() && error.get() == null; index++) {
                List<E2EMessage> chunkToSend = chunks.get(index);
                completionService.submit(() -> {
                    boolean firstError = false;
                    if (error.get() != null) {
                        return null;
                    }
                    try {
                        // TODO: workload tuning based on backpressure should be put to work here
                        sendMessagesChunk(chunkToSend);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        firstError = error.compareAndSet(null, e);
                        throw new RuntimeException(e);
                    } finally {
                        if (firstError) {
                            executor.shutdownNow();
                        }
                    }
                    return null;
                });
            }
            awaitAllOrCancel(completionService, executor, chunks.size(), error);
            if (error.get() != null) {
                iLogger.error("Failed to send messages (first error recorded)", error.get());
                throw new RuntimeException(error.get());
            }
        } catch (RejectedExecutionException e) {
            iLogger.error("Execution of message submission has been rejected", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            iLogger.error("Interrupted while sending messages");
        } catch (ExecutionException e) {
            iLogger.error("Error while sending messages", e);
            throw new RuntimeException(e);
        } finally {
            executor.close();
        }
    }

    /**
     * Waits for {@code taskCount} tasks of {@code completionService} to finish, stopping early once
     * {@code error} is set. If the calling thread is interrupted meanwhile, cancels the tasks still
     * running on {@code executor} and rethrows: interrupting the calling thread doesn't reach the
     * tasks' threads by itself.
     */
    private static void awaitAllOrCancel(CompletionService<Void> completionService, ExecutorService executor,
                                         int taskCount, AtomicReference<Exception> error)
            throws InterruptedException, ExecutionException {
        try {
            for (var index = 0; index < taskCount && error.get() == null; index++) {
                completionService.take().get();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            throw e;
        }
    }

    private void sendMessagesChunk(List<E2EMessage> chunkToSend) throws InterruptedException {
        var mLogger = logger.with("method", "sendMessagesChunk");
        try {
            var timestampedMessages = chunkToSend.stream().map(E2EMessage::withSentAtNow).toList();
            var messagesToSend = MAPPER.writer().writeValueAsString(timestampedMessages);
            var authnHeader = "Basic " + Base64.getEncoder().encodeToString(
                            "%s:%s".formatted(senderCredentials.username(), senderCredentials.password())
                            .getBytes(StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(appServiceUrl.resolve(E2eAppPaths.BULK_SEND_MESSAGES))
                    .method("POST", HttpRequest.BodyPublishers.ofString(messagesToSend))
                    .header("Authorization", authnHeader)
                    .header("Content-Type", "application/json")
                    .timeout(SEND_MESSAGE_CHUNK_TIMEOUT)
                    .build();
            var reply = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode() != HttpServletResponse.SC_NO_CONTENT) {
                mLogger.error("Failed to send messages: {}", reply.statusCode());
                throw new RuntimeException("Failed to send messages");
            }
        } catch (JsonProcessingException e) {
            mLogger.error("Failed to write messages to JSON", e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    // -----------------------------------------------------------------------------
    // --- test data ---
    // -----------------------------------------------------------------------------

    List<Message> createMessages() {
        List<String> recipientCandidates = testUsers.stream().map(PasswordCredentials::username).toList();

        List<Message> created = new ArrayList<>(params.messageCount());
        for (int i = 0; i < params.messageCount(); i++) {
            var message = createMessage(recipientCandidates);
            created.add(message);
            deliveryTracker.addDelivery(message);
        }
        return created;
    }

    private Message createMessage(List<String> recipientCandidates) {
        List<String> recipients = selectRecipients(recipientCandidates, ThreadLocalRandom.current());
        monitoring.incMsgRecipientToReachCounter().accept(recipients.size(), testRunId);

        var msgId = UUID.randomUUID().toString();
        var text = "[%s] %s".formatted(msgId, "a test message");
        var msg = new Message(testRunId, msgId, text, recipients);

        monitoring.incMsgCreatedCounter().accept(testRunId);

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
