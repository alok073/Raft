package rpc;

import lombok.Builder;
import lombok.Value;

/**
 * RequestVote RPC arguments.
 */
@Value
@Builder
public class RequestVote {
    int term;                // Candidate's term
    String candidateId;      // Candidate requesting vote
    int lastLogIndex;        // Index of candidate's last log entry
    int lastLogTerm;         // Term of candidate's last log entry
} 