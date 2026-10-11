package io.github.pdkovacs.wsgw.appward;

/**
 * Why a relay attempt did not end in a delivery, as the {@code reason} tag of
 * {@code wsgw.relay.failures} (docs/backpressure.md §2.5.2). Bounded on purpose: raw status codes would
 * make the tag unbounded, and the class is what an operator reads off it.
 * <p>
 * A timeout expiry is not a failure in this sense: the retry loop has its own counters for it.
 */
public enum FailureReason {
    /** The app answered 4xx. */
    CLIENT_ERROR("4xx"),
    /** The app answered 5xx. */
    SERVER_ERROR("5xx"),
    /** The app answered with a non-2xx status that is neither of the above (e.g. an unfollowed 3xx). */
    OTHER_STATUS("other"),
    /** No answer at all: refused, reset, unresolvable. A reachability problem, not a latency. */
    UNREACHABLE("unreachable");

    public static final String TAG = "reason";

    private final String tagValue;

    FailureReason(String tagValue) {
        this.tagValue = tagValue;
    }

    public String tagValue() {
        return tagValue;
    }

    /** The reason for a non-2xx {@code statusCode}. */
    public static FailureReason of(int statusCode) {
        return switch (statusCode / 100) {
            case 4 -> CLIENT_ERROR;
            case 5 -> SERVER_ERROR;
            default -> OTHER_STATUS;
        };
    }
}
