package rpc;

import lombok.Builder;
import lombok.Value;

/**
 * Response to RequestVote RPC.
 */
@Value
@Builder
public class RequestVoteResponse {
    int term;           // Current term, for candidate to update itself
    boolean voteGranted; // True means candidate received vote
} 