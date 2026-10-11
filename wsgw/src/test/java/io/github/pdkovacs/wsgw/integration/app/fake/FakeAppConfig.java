package io.github.pdkovacs.wsgw.integration.app.fake;

import java.nio.file.Path;
import java.util.function.Consumer;

public class FakeAppConfig {

    private final Path tomcatBaseDir;
    private final String[] apiKey;

    private Runnable connectProcessingImpl;
    private Runnable disconnectProcessingImpl;
    // Receives the id of the connection the relayed message belongs to, so a test can hold up one
    // connection's relay while the others pass straight through.
    private Consumer<String> messageProcessingImpl;
    // The HTTP status the app answers a relayed message with. The message is recorded in the inbox either way.
    private int messageResponseStatus = 200;

    public FakeAppConfig(Path tomcatBaseDir, String[] apiKey) {
        this.tomcatBaseDir = tomcatBaseDir;
        this.apiKey = apiKey;
    }

    public Path getTomcatBaseDir() {
        return tomcatBaseDir;
    }

    public String[] getApiKey() {
        return apiKey;
    }

    public Runnable getConnectProcessingImpl() {
        return connectProcessingImpl;
    }

    public void setConnectProcessingImpl(Runnable connectProcessingImpl) {
        this.connectProcessingImpl = connectProcessingImpl;
    }

    public Runnable getDisconnectProcessingImpl() {
        return disconnectProcessingImpl;
    }

    public void setDisconnectProcessingImpl(Runnable disconnectProcessingImpl) {
        this.disconnectProcessingImpl = disconnectProcessingImpl;
    }

    public Consumer<String> getMessageProcessingImpl() {
        return messageProcessingImpl;
    }

    public void setMessageProcessingImpl(Consumer<String> messageProcessingImpl) {
        this.messageProcessingImpl = messageProcessingImpl;
    }

    public int getMessageResponseStatus() {
        return messageResponseStatus;
    }

    public void setMessageResponseStatus(int messageResponseStatus) {
        this.messageResponseStatus = messageResponseStatus;
    }
}
