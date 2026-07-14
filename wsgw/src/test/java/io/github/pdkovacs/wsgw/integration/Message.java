package io.github.pdkovacs.wsgw.integration;

public sealed interface Message {

    record Text(String text) implements Message {}

    enum EndOfStream implements Message {
        INSTANCE
    }
}
