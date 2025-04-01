package node;

import lombok.Builder;
import lombok.Getter;
import lombok.Value;

import java.util.List;

/**
 * Configuration for a Raft node.
 */
@Value
@Builder
@Getter
public class NodeConfig {
    String nodeId;
    List<String> peerNodeIds;
    
    @Builder.Default
    long electionTimeout = 150 + (long)(Math.random() * 150); // Random timeout between 150-300ms
    
    @Builder.Default
    long heartbeatInterval = 50; // Send heartbeats every 50ms
} 