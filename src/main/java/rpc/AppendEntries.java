package rpc;

import lombok.Builder;
import lombok.Value;
import log.LogEntry;

import java.util.List;

/**
 * AppendEntries RPC arguments.
 */
@Value
@Builder
public class AppendEntries {
    int term;                    // Leader's term
    String leaderId;             // Leader's ID
    int prevLogIndex;            // Index of log entry immediately preceding new ones
    int prevLogTerm;             // Term of prevLogIndex entry
    List<LogEntry> entries;      // Log entries to store (empty for heartbeat)
    int leaderCommit;            // Leader's commitIndex
} 