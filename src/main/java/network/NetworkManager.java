package network;

import lombok.extern.slf4j.Slf4j;
import node.RaftNode;
import rpc.*;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.HashSet;

/**
 * Manages network communication between Raft nodes.
 */
@Slf4j
public class NetworkManager {
    private final Map<String, RaftNode> nodes;
    private final Map<String, Set<String>> networkPartitions;
    private final Map<String, BiConsumer<String, RequestVote>> requestVoteHandlers;
    private final Map<String, BiConsumer<String, AppendEntries>> appendEntriesHandlers;
    private final Map<String, Set<String>> partitionNodes; // Track which nodes are in which partition
    private final Map<String, Boolean> failedNodes; // Track failed nodes

    public NetworkManager() {
        this.nodes = new ConcurrentHashMap<>();
        this.networkPartitions = new ConcurrentHashMap<>();
        this.requestVoteHandlers = new ConcurrentHashMap<>();
        this.appendEntriesHandlers = new ConcurrentHashMap<>();
        this.partitionNodes = new ConcurrentHashMap<>();
        this.failedNodes = new ConcurrentHashMap<>();
    }

    public void registerNode(String nodeId, BiConsumer<String, RequestVote> requestVoteHandler, 
                           BiConsumer<String, AppendEntries> appendEntriesHandler) {
        // Register handlers first
        requestVoteHandlers.put(nodeId, requestVoteHandler);
        appendEntriesHandlers.put(nodeId, appendEntriesHandler);
        log.info("Registered node {} with network manager", nodeId);
    }

    public void setNode(String nodeId, RaftNode node) {
        nodes.put(nodeId, node);
        log.info("Set node {} in network manager", nodeId);
    }

    public void markNodeAsFailed(String nodeId) {
        failedNodes.put(nodeId, true);
        log.info("Marked node {} as failed", nodeId);
    }

    public void markNodeAsActive(String nodeId) {
        failedNodes.remove(nodeId);
        log.info("Marked node {} as active", nodeId);
    }

    public boolean isNodeFailed(String nodeId) {
        return failedNodes.getOrDefault(nodeId, false);
    }

    public void sendRequestVote(String fromNodeId, String toNodeId, RequestVote request) {
        if (!canCommunicate(fromNodeId, toNodeId)) {
            log.debug("Cannot send vote request from {} to {}: {}",
                fromNodeId, toNodeId,
                isNodeFailed(toNodeId) ? "node is failed" : "nodes are partitioned");
            return;
        }
        
        BiConsumer<String, RequestVote> handler = requestVoteHandlers.get(toNodeId);
        if (handler != null) {
            handler.accept(fromNodeId, request);
        }
    }

    public void sendAppendEntries(String fromNodeId, String toNodeId, AppendEntries request) {
        if (!canCommunicate(fromNodeId, toNodeId)) {
            log.debug("Cannot send append entries from {} to {}: {}",
                fromNodeId, toNodeId,
                isNodeFailed(toNodeId) ? "node is failed" : "nodes are partitioned");
            return;
        }
        
        BiConsumer<String, AppendEntries> handler = appendEntriesHandlers.get(toNodeId);
        if (handler != null) {
            handler.accept(fromNodeId, request);
        }
    }

    public void sendRequestVoteResponse(String fromNodeId, String toNodeId, RequestVoteResponse response) {
        if (!canCommunicate(fromNodeId, toNodeId)) {
            log.debug("Cannot send vote response from {} to {}: {}",
                fromNodeId, toNodeId,
                isNodeFailed(toNodeId) ? "node is failed" : "nodes are partitioned");
            return;
        }
        
        RaftNode targetNode = nodes.get(toNodeId);
        if (targetNode != null) {
            targetNode.handleRequestVoteResponse(fromNodeId, response);
        }
    }

    public void sendAppendEntriesResponse(String fromNodeId, String toNodeId, AppendEntriesResponse response) {
        if (!canCommunicate(fromNodeId, toNodeId)) {
            log.debug("Cannot send append entries response from {} to {}: {}",
                fromNodeId, toNodeId,
                isNodeFailed(toNodeId) ? "node is failed" : "nodes are partitioned");
            return;
        }
        
        RaftNode targetNode = nodes.get(toNodeId);
        if (targetNode != null) {
            targetNode.handleAppendEntriesResponse(fromNodeId, response);
        }
    }

    public void createPartition(String nodeId1, String nodeId2) {
        networkPartitions.computeIfAbsent(nodeId1, k -> ConcurrentHashMap.newKeySet()).add(nodeId2);
        networkPartitions.computeIfAbsent(nodeId2, k -> ConcurrentHashMap.newKeySet()).add(nodeId1);
        log.info("Created network partition between {} and {}", nodeId1, nodeId2);
    }

    public void removePartition(String nodeId1, String nodeId2) {
        if (networkPartitions.containsKey(nodeId1)) {
            networkPartitions.get(nodeId1).remove(nodeId2);
        }
        if (networkPartitions.containsKey(nodeId2)) {
            networkPartitions.get(nodeId2).remove(nodeId1);
        }
        log.info("Removed network partition between {} and {}", nodeId1, nodeId2);
    }

    public void createNetworkPartition(String partitionId, Set<String> nodesInPartition) {
        // Store which nodes are in this partition
        partitionNodes.put(partitionId, new HashSet<>(nodesInPartition));
        
        // Create partitions between nodes in this partition and all other nodes
        for (String nodeId : nodesInPartition) {
            for (String otherNodeId : nodes.keySet()) {
                // Create partition if the other node is not in this partition
                if (!nodesInPartition.contains(otherNodeId)) {
                    createPartition(nodeId, otherNodeId);
                }
            }
        }
        log.info("Created network partition {} with nodes: {}", partitionId, nodesInPartition);
    }

    public void removeNetworkPartition(String partitionId) {
        Set<String> nodesInPartition = partitionNodes.remove(partitionId);
        if (nodesInPartition != null) {
            // Remove partitions between nodes in this partition and nodes in other partitions
            for (String nodeId : nodesInPartition) {
                for (String otherNodeId : nodes.keySet()) {
                    if (!nodesInPartition.contains(otherNodeId)) {
                        // Check if the other node is in any other partition
                        boolean isInOtherPartition = false;
                        for (Map.Entry<String, Set<String>> entry : partitionNodes.entrySet()) {
                            if (!entry.getKey().equals(partitionId) && entry.getValue().contains(otherNodeId)) {
                                isInOtherPartition = true;
                                break;
                            }
                        }
                        if (!isInOtherPartition) {  // Only remove partition if the other node is NOT in another partition
                            removePartition(nodeId, otherNodeId);
                        }
                    }
                }
            }
            log.info("Removed network partition: {}", partitionId);
        }
    }

    public boolean canCommunicate(String fromNodeId, String toNodeId) {
        // If either node is failed, they can't communicate
        if (isNodeFailed(fromNodeId) || isNodeFailed(toNodeId)) {
            return false;
        }

        // Check if nodes are in different partitions
        return !networkPartitions.containsKey(fromNodeId) || 
               !networkPartitions.get(fromNodeId).contains(toNodeId);
    }

    public void shutdown() {
        nodes.clear();
        networkPartitions.clear();
        requestVoteHandlers.clear();
        appendEntriesHandlers.clear();
        partitionNodes.clear();
        failedNodes.clear();
        log.info("Network manager shutdown");
    }
} 