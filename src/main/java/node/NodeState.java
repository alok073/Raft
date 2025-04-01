package node;

/**
 * Represents the possible states a Raft node can be in.
 */
public enum NodeState {
    FOLLOWER,
    CANDIDATE,
    LEADER
} 