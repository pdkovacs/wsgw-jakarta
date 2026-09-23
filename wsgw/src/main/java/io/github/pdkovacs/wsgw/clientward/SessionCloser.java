package io.github.pdkovacs.wsgw.clientward;

import java.io.IOException;

public interface SessionCloser {
    void closeSession(String connectionId) throws IOException;
}
