package org.qortal.network.message;

@SuppressWarnings("serial")
public class UnsupportedMessageException extends MessageException {
    public UnsupportedMessageException() {
    }

    public UnsupportedMessageException(String message) {
        super(message);
    }

    public UnsupportedMessageException(String message, Throwable cause) {
        super(message, cause);
    }

    public UnsupportedMessageException(Throwable cause) {
        super(cause);
    }
}
