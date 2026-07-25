package io.github.pdkovacs.wsgw.clientward;

import java.io.IOException;

public interface SessionCloser {
    void close(String connectionId) throws IOException;
}
