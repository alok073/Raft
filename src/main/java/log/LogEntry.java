package log;

import lombok.Value;

/**
 * Represents a single entry in the Raft log.
 */
@Value
public class LogEntry {
    int term;
    int index;
    byte[] command;

    public byte[] getCommand() {
        return command.clone();
    }

    @Override
    public String toString() {
        return String.format("LogEntry{term=%d, index=%d}", term, index);
    }
} 