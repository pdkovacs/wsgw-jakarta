package io.github.pdkovacs.wsgw.socket;

import java.time.Duration;

public record Timeouts(Duration pushWaitForRegistration, Duration pushWaitForSendMessageDesaturation) {
}
