package io.github.pdkovacs.wsgw.unit;

import io.github.pdkovacs.wsgw.clientside.WsConnections;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;

public class WsConnectionsTest {
    @Test
    @DisplayName("the happy path")
    void testLonelyPushSendsMessageOverRegisteredSession() throws IOException, InterruptedException {
        var testConnectionId = "some connection-id";
        var testMessage = "some message";
        var mockedSession = mock(Session.class);
        var mockedBasicRemote = mock(RemoteEndpoint.Basic.class);
        when(mockedSession.getBasicRemote()).thenReturn(mockedBasicRemote);
        var connections = new WsConnections(Duration.ofSeconds(5));

        connections.register(testConnectionId, mockedSession);
        connections.push(testConnectionId, testMessage);

        verify(mockedBasicRemote, timeout(500).times(1)).sendText(testMessage);
        verifyNoMoreInteractions(mockedBasicRemote);
    }

    // @Test
    // // register lands within registrationWait → send succeeds (race absorbed).
    // public void testPushToleratesPushBeforeRegister() {}
    // @Test
    // // register never lands → registration-timeout failure.
    // public void testPushTimesOutWhenRegisterNeverArrives() {}
    // @Test
    // // send-path busy → backpressure failure.
    // public void testPushFailsFastWhenSendPathSaturated() {}

}
