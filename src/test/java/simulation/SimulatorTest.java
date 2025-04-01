package simulation;

import node.NodeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorTest {
    private Simulator simulator;
    private static final int NUM_NODES = 5;
    private static final Logger log = LoggerFactory.getLogger(SimulatorTest.class);

    @BeforeEach
    void setUp() {
        simulator = new Simulator();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testLeaderElection() throws InterruptedException {
        simulator.start();

        // Wait for initial leader election
        Thread.sleep(2000);

        // Verify a leader was elected
        String leaderId = simulator.getCurrentLeader();
        assertNotNull(leaderId, "A leader should be elected");

        // Verify all nodes are followers except the leader
        for (int i = 0; i < NUM_NODES; i++) {
            String nodeId = "node" + i;
            if (!nodeId.equals(leaderId)) {
                assertEquals(NodeState.FOLLOWER, simulator.getNodeState(nodeId),
                        "Node " + nodeId + " should be a follower");
            } else {
                assertEquals(NodeState.LEADER, simulator.getNodeState(nodeId),
                        "Node " + nodeId + " should be the leader");
            }
        }

        // Verify all nodes have the same term
        int leaderTerm = simulator.getNodeTerm(leaderId);
        for (int i = 0; i < NUM_NODES; i++) {
            String nodeId = "node" + i;
            assertEquals(leaderTerm, simulator.getNodeTerm(nodeId),
                    "Node " + nodeId + " should have the same term as leader");
        }

        // Verify all nodes can communicate with each other
        for (int i = 0; i < NUM_NODES; i++) {
            for (int j = i + 1; j < NUM_NODES; j++) {
                String node1 = "node" + i;
                String node2 = "node" + j;
                assertTrue(simulator.canCommunicate(node1, node2),
                        "Nodes " + node1 + " and " + node2 + " should be able to communicate");
            }
        }

        // Wait for a few heartbeats to ensure stability
        Thread.sleep(2000);

        // Verify leader hasn't changed
        assertEquals(leaderId, simulator.getCurrentLeader(),
                "Leader should remain stable");

        simulator.stop();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testLeaderReElection() throws InterruptedException {
        simulator.start();

        // Wait for initial leader election
        Thread.sleep(2000);

        // Get initial leader
        String initialLeaderId = simulator.getCurrentLeader();
        assertNotNull(initialLeaderId, "Initial leader should be elected");

        // Kill the current leader
        simulator.killNode(initialLeaderId);
        assertTrue(simulator.isNodeFailed(initialLeaderId), "Leader should be marked as failed");

        // Wait for new leader election
        Thread.sleep(2000);

        // Verify a new leader was elected & the new leader is different from the old leader
        String newLeaderId = simulator.getCurrentLeader();
        assertNotNull(newLeaderId, "New leader should be elected");
        assertNotEquals(initialLeaderId, newLeaderId, "New leader should be different from old leader");

        // Verify new leader is in LEADER state
        assertEquals(NodeState.LEADER, simulator.getNodeState(newLeaderId),
                "New leader should be in LEADER state");

        // Verify all other nodes are followers
        for (int i = 0; i < NUM_NODES; i++) {
            String nodeId = "node" + i;
            if (!nodeId.equals(newLeaderId) && !nodeId.equals(initialLeaderId)) {
                assertEquals(NodeState.FOLLOWER, simulator.getNodeState(nodeId),
                        "Node " + nodeId + " should be a follower");
            }
        }

        // Verify all active nodes have the same term
        int newLeaderTerm = simulator.getNodeTerm(newLeaderId);
        for (int i = 0; i < NUM_NODES; i++) {
            String nodeId = "node" + i;
            if (!nodeId.equals(initialLeaderId)) {  // Skip the failed leader
                assertEquals(newLeaderTerm, simulator.getNodeTerm(nodeId),
                        "Node " + nodeId + " should have the same term as new leader");
            }
        }

        simulator.stop();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testSplitBrainScenario() throws InterruptedException {
        simulator.start();

        // Wait for initial leader election
        Thread.sleep(2000);

        // Get initial leader
        String initialLeader = simulator.getCurrentLeader();
        assertNotNull(initialLeader, "Initial leader should be elected");
        int initialLeaderTerm = simulator.getNodeTerm(initialLeader);
        log.info("Initial leader elected: {} with term: {}", initialLeader, initialLeaderTerm);

        // Kill the current leader
        simulator.killNode(initialLeader);
        int killedLeaderTerm = simulator.getNodeTerm(initialLeader);
        log.info("Killed leader: {} with term: {}", initialLeader, killedLeaderTerm);
        assertEquals(initialLeaderTerm, killedLeaderTerm,
                "Killed leader should maintain its original term");

        // Wait for new leader election
        Thread.sleep(2000);

        // Verify new leader was elected
        String newLeader = simulator.getCurrentLeader();
        assertNotNull(newLeader, "New leader should be elected");
        assertNotEquals(initialLeader, newLeader, "New leader should be different from old leader");
        int newLeaderTerm = simulator.getNodeTerm(newLeader);
        log.info("New leader elected: {} with term: {}", newLeader, newLeaderTerm);

        // Verify new leader has higher term
        int oldLeaderTerm = simulator.getNodeTerm(initialLeader);
        log.info("Old leader term: {}, New leader term: {}", oldLeaderTerm, newLeaderTerm);
        assertTrue(newLeaderTerm > oldLeaderTerm,
                "New leader should have a higher term than the old leader");

        // Restart the old leader
        simulator.restartNode(initialLeader);
        log.info("Restarted old leader: {} with term: {}", initialLeader, simulator.getNodeTerm(initialLeader));

        // verify the state of the restored LEADER is still a LEADER
        assertEquals(NodeState.LEADER, simulator.getNodeState(initialLeader),
                "Restored leader should be in LEADER state");

        // Wait for term synchronization
        Thread.sleep(2000);

        // Verify old leader has updated its term
        int finalOldLeaderTerm = simulator.getNodeTerm(initialLeader);
        log.info("Final old leader term: {}, New leader term: {}", finalOldLeaderTerm, newLeaderTerm);
        assertEquals(newLeaderTerm, finalOldLeaderTerm,
                "Old leader should update its term to match new leader");

        simulator.stop();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testNetworkPartitionWithSplitBrain() throws InterruptedException {
        simulator.start();

        // Wait for initial leader election
        Thread.sleep(2000);

        // Get initial leader
        String initialLeader = simulator.getCurrentLeader();
        assertNotNull(initialLeader, "Initial leader should be elected");
        int initialTerm = simulator.getNodeTerm(initialLeader);
        log.info("Initial leader elected: {} with term: {}", initialLeader, initialTerm);

        // Create partitions such that:
        // partition1: contains current leader + 1 other node (2 nodes, no majority)
        // partition2: contains 3 other nodes (has majority)
        Set<String> partition1 = new HashSet<>();
        partition1.add(initialLeader);
        // Add one other node to partition1
        for (int i = 0; i < NUM_NODES; i++) {
            String nodeId = "node" + i;
            if (!nodeId.equals(initialLeader)) {
                partition1.add(nodeId);
                break;
            }
        }

        // Log state of all nodes before partition
        log.info("Node states before partition:");
        for (int i = 0; i < NUM_NODES; i++) {
            String nodeId = "node" + i;
            log.info("Node {}: state={}, term={}",
                    nodeId, simulator.getNodeState(nodeId), simulator.getNodeTerm(nodeId));
        }

        // Create the partitions
        simulator.createNetworkPartition("partition1", partition1);

        // Log state of all nodes after partition
        log.info("Node states after partition:");
        for (int i = 0; i < NUM_NODES; i++) {
            String nodeId = "node" + i;
            log.info("Node {}: state={}, term={}, canCommunicate={}",
                    nodeId, simulator.getNodeState(nodeId), simulator.getNodeTerm(nodeId),
                    simulator.canCommunicate(nodeId, "node" + ((i + 1) % NUM_NODES)));
        }

        // Wait for partition2 to elect its leader (it has majority)
        Thread.sleep(3000);

        // Log state of all nodes after waiting
        log.info("Node states after waiting:");
        for (int i = 0; i < NUM_NODES; i++) {
            String nodeId = "node" + i;
            log.info("Node {}: state={}, term={}",
                    nodeId, simulator.getNodeState(nodeId), simulator.getNodeTerm(nodeId));
        }

        // Verify partition1 still has the original leader (can't elect new leader)
        assertEquals(NodeState.LEADER, simulator.getNodeState(initialLeader),
                "Original leader should remain leader in partition1");

        // Remove the partitions
        simulator.removeNetworkPartition("partition1");

        // Wait for leader election to complete
        Thread.sleep(3000);

        // Verify only one leader exists
        String finalLeader = simulator.getCurrentLeader();
        assertNotNull(finalLeader, "Final leader should be elected");
        int finalTerm = simulator.getNodeTerm(finalLeader);
        log.info("Final leader elected: {} with term: {}", finalLeader, finalTerm);

        // Verify all nodes have the same term
        for (int i = 0; i < NUM_NODES; i++) {
            String nodeId = "node" + i;
            assertEquals(finalTerm, simulator.getNodeTerm(nodeId),
                    "Node " + nodeId + " should have the same term as final leader");
        }

        simulator.stop();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testNodeFailureAndRecovery() throws InterruptedException {
        simulator.start();

        // Wait for initial leader election
        Thread.sleep(1000);

        // Kill a node
        simulator.killNode("node0");
        assertTrue(simulator.isNodeFailed("node0"));
        assertFalse(simulator.canCommunicate("node0", "node1"));

        // Wait for new leader election
        Thread.sleep(2000);

        // Restart the node
        simulator.restartNode("node0");
        assertFalse(simulator.isNodeFailed("node0"));
        assertTrue(simulator.canCommunicate("node0", "node1"));

        simulator.stop();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testNetworkPartition() throws InterruptedException {
        simulator.start();

        // Wait for initial leader election
        Thread.sleep(1000);

        // Create a network partition between nodes 0,1 and nodes 2,3,4
        Set<String> partition1 = new HashSet<>(Arrays.asList("node0", "node1"));
        simulator.createNetworkPartition("partition1", partition1);

        // Verify communication is blocked between partitions
        assertFalse(simulator.canCommunicate("node0", "node2"));
        assertFalse(simulator.canCommunicate("node1", "node3"));
        assertTrue(simulator.canCommunicate("node0", "node1")); // Within same partition
        assertTrue(simulator.canCommunicate("node2", "node3")); // Within same partition

        // Remove the partition
        simulator.removeNetworkPartition("partition1");

        // Verify communication is restored
        assertTrue(simulator.canCommunicate("node0", "node2"));
        assertTrue(simulator.canCommunicate("node1", "node3"));

        simulator.stop();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testMultiplePartitions() throws InterruptedException {
        simulator.start();

        // Wait for initial leader election
        Thread.sleep(1000);

        // Create two partitions
        Set<String> partition1 = new HashSet<>(Arrays.asList("node0", "node1"));
        Set<String> partition2 = new HashSet<>(Arrays.asList("node2", "node3"));
        simulator.createNetworkPartition("partition1", partition1);
        simulator.createNetworkPartition("partition2", partition2);

        // Verify communication is blocked between all partitions
        assertFalse(simulator.canCommunicate("node0", "node2"));
        assertFalse(simulator.canCommunicate("node1", "node3"));
        assertFalse(simulator.canCommunicate("node0", "node4"));
        assertFalse(simulator.canCommunicate("node1", "node4"));

        // Verify communication within partitions
        assertTrue(simulator.canCommunicate("node0", "node1"));
        assertTrue(simulator.canCommunicate("node2", "node3"));

        // Remove one partition
        simulator.removeNetworkPartition("partition1");

        // Verify communication is restored for nodes in partition1
        assertTrue(simulator.canCommunicate("node0", "node2"));
        assertTrue(simulator.canCommunicate("node1", "node3"));

        simulator.stop();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testNodeFailureWithPartition() throws InterruptedException {
        simulator.start();

        // Wait for initial leader election
        Thread.sleep(1000);

        // Create a partition and kill a node
        Set<String> partition1 = new HashSet<>(Arrays.asList("node0", "node1"));
        simulator.createNetworkPartition("partition1", partition1);
        simulator.killNode("node2");

        // Verify communication is blocked due to both partition and failure
        assertFalse(simulator.canCommunicate("node0", "node2"));
        assertFalse(simulator.canCommunicate("node1", "node2"));
        assertFalse(simulator.canCommunicate("node2", "node3"));

        // Restart the node
        simulator.restartNode("node2");

        // Verify communication is still blocked due to partition
        assertFalse(simulator.canCommunicate("node0", "node2"));
        assertFalse(simulator.canCommunicate("node1", "node2"));
        assertTrue(simulator.canCommunicate("node2", "node3"));

        // Remove partition
        simulator.removeNetworkPartition("partition1");

        // Verify all communication is restored
        assertTrue(simulator.canCommunicate("node0", "node2"));
        assertTrue(simulator.canCommunicate("node1", "node2"));
        assertTrue(simulator.canCommunicate("node2", "node3"));

        simulator.stop();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testTermSynchronizationAfterNetworkIssues() throws InterruptedException {
        simulator.start();

        // Wait for initial leader election
        Thread.sleep(2000);

        // Create a temporary network partition
        Set<String> partition = new HashSet<>(Arrays.asList("node0", "node1"));
        simulator.createNetworkPartition("temp_partition", partition);

        // Wait for partition to stabilize
        Thread.sleep(2000);

        // Get terms before partition removal
        Map<String, Integer> termsBefore = new HashMap<>();
        for (int i = 0; i < NUM_NODES; i++) {
            String nodeId = "node" + i;
            termsBefore.put(nodeId, simulator.getNodeTerm(nodeId));
        }

        // Remove the partition
        simulator.removeNetworkPartition("temp_partition");

        // Wait for term synchronization
        Thread.sleep(2000);

        // Verify all nodes have synchronized to the highest term
        int maxTerm = termsBefore.values().stream().mapToInt(Integer::intValue).max().getAsInt();
        for (int i = 0; i < NUM_NODES; i++) {
            String nodeId = "node" + i;
            assertEquals(maxTerm, simulator.getNodeTerm(nodeId), 
                "Node " + nodeId + " should have synchronized to the highest term");
        }

        simulator.stop();
    }
} 