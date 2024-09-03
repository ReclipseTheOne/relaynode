package org.relay.relaynode.util;

import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class CustomFormatter extends Formatter {

    @Override
    public String format(LogRecord record) {
        String message = record.getMessage();

        if (message != null && !message.trim().isEmpty()) {
            return "INFO: " + message + System.lineSeparator();
        }

        return "";
    }
}
