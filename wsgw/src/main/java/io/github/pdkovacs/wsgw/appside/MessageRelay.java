package io.github.pdkovacs.wsgw.appside;

import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface MessageRelay {
    void relay(Map<String, List<String>> connectHeaders, String connectionId, String msg);
}