package node;

import network.NetworkManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class RaftNodeTest {
    private ScheduledExecutorService scheduler;
    private NodeConfig config;
    private NetworkManager networkManager;
    private RaftNode node;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(1);
        networkManager = new NetworkManager();
        config = NodeConfig.builder()
                .nodeId("node1")
                .peerNodeIds(Collections.emptyList())
                .build();
        node = new RaftNode(config, scheduler, networkManager);
    }

    @Test
    void testInitialState() {
        assertEquals(NodeState.FOLLOWER, node.getState());
        assertEquals(0, node.getCurrentTerm());
        assertNull(node.getVotedFor());
        assertNull(node.getCurrentLeader());
    }

    @Test
    void testNodeId() {
        assertEquals("node1", node.getNodeId());
    }
} 