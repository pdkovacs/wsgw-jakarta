package io.github.pdkovacs.wsgw.e2e.driver.service;

import io.github.pdkovacs.wsgw.e2e.common.auth.PasswordCredentials;
import io.github.pdkovacs.wsgw.e2e.driver.Monitoring;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;

public class Client {
    private final PasswordCredentials credentials;
    private final WsClient wsClient;
    private final DeliveryTracker outstandingMessages;
    private final Monitoring monitoring;
    private final String runId;
    private final List<PasswordCredentials> allCredentials;

    public Client(HttpClient httpClient, String wsgwUri, BlockingQueue<String> msgFromApp,
                  PasswordCredentials credentials, List<PasswordCredentials> allCredentials,
                  DeliveryTracker outstandingMessages, Monitoring monitoring, String runId) {
        this.credentials = credentials;
        this.allCredentials = allCredentials;
        this.wsClient = new WsClient(httpClient, wsgwUri, msgFromApp);
        this.outstandingMessages = outstandingMessages;
        this.monitoring = monitoring;
        this.runId = runId;
    }

    void connectAndListen(CountDownLatch latch) throws ExecutionException, InterruptedException {
        wsClient.connect(runId, credentials.username(), credentials.password())
                .get();
    }

    Message createMessage(List<String> recipients) {
        monitoring.incMsgRecipientToReachCounter().accept(recipients.size());

        var msgId = UUID.randomUUID().toString();
        var text = "[%s] %s".formatted(msgId, "a test message");
        var msg = new Message(runId, msgId, text, credentials, recipients);

        monitoring.incMsgCreatedCounter().run();

        return msg;
    }

    private List<String> selectRecipients(List<String> allCredentials) {
        int n = allCredentials.size();
        if (n <= 10) {
            return allCredentials;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int howMany = random.nextInt(n / 2 - 1) + n / 2;

        List<String> result = new ArrayList<>(howMany);
        int k = howMany; // elements still needed

        for (int i = 0; i < n; i++) {
            int remaining = n - i; // elements left to visit (including current)

            // Probability of selecting current element = k / remaining
            if (random.nextInt(remaining) < k) {
                result.add(allCredentials.get(i));
                k--; // one less element needed
                if (k == 0) {
                    break; // done selecting
                }
            }
        }

        return result;
    }
}
