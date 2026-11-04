package io.github.pdkovacs.wsgw.e2e.driver.service;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.driver.FakeWsServer;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRunTest {

    @ParameterizedTest
    @CsvSource({"3, 1", "3, 3", "3, 7"})
    void messageCountIsIndependentOfUserCount(int userCount, int messageCount) {
        var messages = testRun(10, userCount, messageCount, Monitoring.noop()).createMessages();

        assertThat(messages).hasSize(messageCount);
    }

    @Test
    void upToTheThresholdEveryMessageGoesToAllOfTheRunsUsers() {
        var messages = testRun(20, 10, 3, Monitoring.noop()).createMessages();

        assertThat(messages).allSatisfy(message ->
                assertThat(message.getRecipientsWaiting()).containsExactlyElementsOf(usernames(10)));
    }

    @Test
    void aboveTheThresholdRecipientsComeOnlyFromTheRunsUsers() {
        var messages = testRun(20, 12, 50, Monitoring.noop()).createMessages();

        assertThat(messages).allSatisfy(message ->
                assertThat(usernames(12)).containsAll(message.getRecipientsWaiting()));
    }

    @Test
    void messagesDoNotShareTheirRecipientsWaiting() {
        var messages = testRun(3, 3, 2, Monitoring.noop()).createMessages();

        messages.getFirst().setRecipientServed("user1");

        assertThat(messages.getFirst().getRecipientsWaiting()).containsExactly("user2", "user3");
        assertThat(messages.getLast().getRecipientsWaiting()).containsExactly("user1", "user2", "user3");
    }

    @Test
    void countsCreatedMessagesAndTheirRecipients() {
        var messagesCreated = new AtomicInteger();
        var recipientsToReach = new AtomicInteger();
        var monitoring = new Monitoring(recipientsToReach::addAndGet, messagesCreated::incrementAndGet,
                () -> {
                }, () -> {
        }, () -> {
        }, duration -> {
        }, duration -> {
        });

        testRun(5, 4, 3, monitoring).createMessages();

        assertThat(messagesCreated).hasValue(3);
        assertThat(recipientsToReach).hasValue(12);
    }

    @ParameterizedTest
    @CsvSource({"1", "2", "10"})
    void upToTheThresholdAllCandidatesAreSelected(int candidateCount) {
        var candidates = usernames(candidateCount);

        assertThat(TestRun.selectRecipients(candidates, new Random(42))).isEqualTo(candidates);
    }

    @ParameterizedTest
    @CsvSource({"11, 5, 8", "12, 6, 10", "1024, 512, 1022"})
    void aboveTheThresholdSelectionCoversExactlyTheExpectedRange(int candidateCount, int min, int max) {
        var candidates = usernames(candidateCount);
        var random = new Random(42);

        IntSummaryStatistics selected = IntStream.range(0, 5000)
                .map(i -> {
                    var recipients = TestRun.selectRecipients(candidates, random);
                    assertThat(recipients).doesNotHaveDuplicates();
                    return recipients.size();
                })
                .summaryStatistics();

        assertThat(selected.getMin()).isEqualTo(min);
        assertThat(selected.getMax()).isEqualTo(max);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "-1, 1", "4, 1", "1, 0", "1, -1"})
    void rejectsCountsOutOfRange(int userCount, int messageCount) {
        assertThatThrownBy(() -> testRun(3, userCount, messageCount, Monitoring.noop()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runConnectsItsUsersCreatesTheMessagesThenClosesTheSessions() throws Exception {
        var messagesCreated = new AtomicInteger();
        try (var gateway = new FakeWsServer(); var http = HttpClient.newHttpClient()) {
            var run = TestRun.create(http, gateway.hostAndPort(), 3, 2, Duration.ofSeconds(1), credentials(5),
                    new PasswordCredentials("sender", "secret"), countingCreatedMessages(messagesCreated));

            run.run();

            assertThat(gateway.requests)
                    .allSatisfy(request -> assertThat(request.requestLine()).startsWith("GET /connect "))
                    .extracting(request -> request.headers().get("authorization"))
                    .containsExactlyInAnyOrder(authorization("user1"), authorization("user2"), authorization("user3"));
            assertThat(gateway.closes).hasValue(3);
            assertThat(messagesCreated).hasValue(2);
        }
    }

    @Test
    void runEndsWithoutMessagesIfAUserCannotConnectButClosesTheOthersSessions() throws Exception {
        var messagesCreated = new AtomicInteger();
        try (var gateway = new FakeWsServer(); var http = HttpClient.newHttpClient()) {
            gateway.rejectAuthorization(authorization("user2"));
            var run = TestRun.create(http, gateway.hostAndPort(), 3, 2, Duration.ofSeconds(1), credentials(3),
                    new PasswordCredentials("sender", "secret"), countingCreatedMessages(messagesCreated));

            run.run();

            assertThat(gateway.requests).hasSize(3);
            assertThat(gateway.closes).hasValue(2);
            assertThat(messagesCreated).hasValue(0);
        }
    }

    // -----------------------------------------------------------------------------
    // --- helpers ---
    // -----------------------------------------------------------------------------

    private static TestRun testRun(int poolSize, int userCount, int messageCount, Monitoring monitoring) {
        return TestRun.create(null, "wsgw:8080", userCount, messageCount, Duration.ofSeconds(1), credentials(poolSize),
                new PasswordCredentials("sender", "secret"), monitoring);
    }

    private static Monitoring countingCreatedMessages(AtomicInteger messagesCreated) {
        return new Monitoring(count -> {
        }, messagesCreated::incrementAndGet,
                () -> {
                }, () -> {
        }, () -> {
        }, duration -> {
        }, duration -> {
        });
    }

    private static String authorization(String username) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":secret").getBytes(StandardCharsets.UTF_8));
    }

    private static List<PasswordCredentials> credentials(int count) {
        return usernames(count).stream().map(username -> new PasswordCredentials(username, "secret")).toList();
    }

    private static List<String> usernames(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "user" + i).toList();
    }
}
