package rpc;

import lombok.Builder;
import lombok.Value;

/**
 * Response to AppendEntries RPC.
 */
@Value
@Builder
public class AppendEntriesResponse {
    int term;           // Current term, for leader to update itself
    boolean success;    // True if follower contained entry matching prevLogIndex and prevLogTerm
    int conflictIndex;  // If false, the index of the conflicting entry
    int conflictTerm;   // If false, the term of the conflicting entry
} 