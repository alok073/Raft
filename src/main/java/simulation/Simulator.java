package simulation;

import lombok.extern.slf4j.Slf4j;
import network.NetworkManager;
import node.NodeConfig;
import node.NodeState;
import node.RaftNode;
import rpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simulates a Raft cluster with multiple nodes.
 */
@Slf4j
public class Simulator {
    private final Map<String, RaftNode> nodes;
    private final NetworkManager networkManager;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Boolean> failedNodes;
    private final Map<String, Set<String>> networkPartitions;
    private volatile boolean isRunning = false;

    public Simulator() {
        this.nodes = new ConcurrentHashMap<>();
        this.networkManager = new NetworkManager();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.failedNodes = new ConcurrentHashMap<>();
        this.networkPartitions = new ConcurrentHashMap<>();
    }

    public void start() {
        if (isRunning) return;
        
        isRunning = true;
        log.info("Starting Raft simulation");
        
        // First, create and register all nodes
        for (int i = 0; i < 5; i++) {
            String nodeId = "node" + i;
            NodeConfig config = NodeConfig.builder()
                .nodeId(nodeId)
                .peerNodeIds(getPeerNodeIds(i))
                .electionTimeout(150 + (int)(Math.random() * 150)) // Random timeout between 150-300ms
                .heartbeatInterval(50) // Heartbeat every 50ms
                .build();
            
            RaftNode node = new RaftNode(config, scheduler, networkManager);
            
            // Set the node in network manager
            networkManager.setNode(nodeId, node);
            
            nodes.put(nodeId, node);
        }
        
        // Then start all nodes
        for (RaftNode node : nodes.values()) {
            node.start();
        }
    }

    public void stop() {
        if (!isRunning) return;
        
        isRunning = false;
        log.info("Stopping Raft simulation");
        
        // Stop all nodes
        for (RaftNode node : nodes.values()) {
            node.stop();
        }
        
        // Clear all nodes
        nodes.clear();
        
        // Shutdown network manager
        networkManager.shutdown();
        
        // Shutdown scheduler
        scheduler.shutdown();
    }

    private List<String> getPeerNodeIds(int nodeIndex) {
        List<String> peerIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (i != nodeIndex) {
                peerIds.add("node" + i);
            }
        }
        return peerIds;
    }

    public RaftNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public boolean isRunning() {
        return isRunning;
    }

    public String getCurrentLeader() {
        for (RaftNode node : nodes.values()) {
            // Skip failed nodes
            if (failedNodes.containsKey(node.getNodeId())) {
                continue;
            }
            if (node.getState() == NodeState.LEADER) {
                return node.getNodeId();
            }
        }
        return null;
    }

    public NodeState getNodeState(String nodeId) {
        RaftNode node = nodes.get(nodeId);
        return node != null ? node.getState() : null;
    }

    public int getNodeTerm(String nodeId) {
        RaftNode node = nodes.get(nodeId);
        return node != null ? node.getCurrentTerm() : -1;
    }

    public void killNode(String nodeId) {
        RaftNode node = findNode(nodeId);
        if (node != null) {
            failedNodes.put(nodeId, true);
            networkManager.markNodeAsFailed(nodeId);  // Mark node as failed in network manager
            node.stop();  // Stop the node completely
            log.info("Killed node: {} with term: {}", nodeId, node.getCurrentTerm());
        }
    }

    public void restartNode(String nodeId) {
        RaftNode node = findNode(nodeId);
        if (node != null) {
            failedNodes.remove(nodeId);
            networkManager.markNodeAsActive(nodeId);  // Mark node as active in network manager
            node.start();  // Start the node completely
            log.info("Restarted node: {} with term: {}", nodeId, node.getCurrentTerm());
        }
    }

    public void createNetworkPartition(String partitionId, Set<String> nodesInPartition) {
        networkPartitions.put(partitionId, new HashSet<>(nodesInPartition));
        log.info("Created network partition {} with nodes: {}", partitionId, nodesInPartition);
    }

    public void removeNetworkPartition(String partitionId) {
        networkPartitions.remove(partitionId);
        log.info("Removed network partition: {}", partitionId);
    }

    public boolean isNodeFailed(String nodeId) {
        return failedNodes.getOrDefault(nodeId, false);
    }

    public boolean canCommunicate(String fromNodeId, String toNodeId) {
        // Check if either node is failed
        if (isNodeFailed(fromNodeId) || isNodeFailed(toNodeId)) {
            return false;
        }

        // Check if nodes are in different partitions
        for (Set<String> partition : networkPartitions.values()) {
            if (partition.contains(fromNodeId) && !partition.contains(toNodeId)) {
                return false;
            }
        }

        return true;
    }

    private RaftNode findNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public static void main(String[] args) {
        Simulator simulator = new Simulator();
        simulator.start();
    }
} 