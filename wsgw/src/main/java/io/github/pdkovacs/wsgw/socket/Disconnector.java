package io.github.pdkovacs.wsgw.socket;

// Called only from Endpoint.onClose: every way a connection ends -- the client closing its
// session, the app asking for a disconnect -- goes through the session close, so this is the
// single place a connection is released.
public interface Disconnector {
    void disconnect(String connectionId);
}
