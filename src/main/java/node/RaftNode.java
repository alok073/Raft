package node;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import log.LogEntry;
import log.LogManager;
import network.NetworkManager;
import rpc.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a single node in the Raft cluster.
 */
@Slf4j
public class RaftNode {
    private final NodeConfig config;
    private final LogManager logManager;
    private final Random random;
    private final ScheduledExecutorService scheduler;
    private final NetworkManager networkManager;
    
    // Volatile state
    @Getter
    private volatile NodeState state;
    private volatile String currentLeader;
    private final AtomicInteger currentTerm;
    private volatile String votedFor;
    
    // Timer related
    private volatile ScheduledFuture<?> electionTimer;
    private volatile ScheduledFuture<?> heartbeatTimer;
    
    // Leader state
    private final Map<String, Integer> nextIndex;
    private final Map<String, Integer> matchIndex;
    private final Set<String> votesReceived;
    
    private volatile boolean isProcessing = false;  // Start as inactive
    private volatile boolean isStarted = false;     // Track if node has been started

    public RaftNode(NodeConfig config, ScheduledExecutorService scheduler, NetworkManager networkManager) {
        this.config = config;
        this.scheduler = scheduler;
        this.networkManager = networkManager;
        this.logManager = new LogManager();
        this.random = new Random();
        
        // Initialize state
        this.state = NodeState.FOLLOWER;
        this.currentTerm = new AtomicInteger(0);
        this.votedFor = null;
        this.currentLeader = null;
        
        // Initialize leader state
        this.nextIndex = new ConcurrentHashMap<>();
        this.matchIndex = new ConcurrentHashMap<>();
        this.votesReceived = Collections.newSetFromMap(new ConcurrentHashMap<>());
        
        log.info("Initialized RaftNode with ID: {}", config.getNodeId());
    }

    public synchronized void start() {
        if (isStarted) return;
        
        isStarted = true;
        isProcessing = true;
        
        log.info("Node {} starting. Current state: {}, Current term: {}", 
            config.getNodeId(), state, currentTerm.get());
        
        // Register RPC handlers
        networkManager.registerNode(
            config.getNodeId(),
            (fromNodeId, request) -> handleRequestVote(fromNodeId, request),
            (fromNodeId, request) -> handleAppendEntries(fromNodeId, request)
        );
        
        // Start election timer
        resetElectionTimer();
        
        log.info("Started RaftNode with ID: {}", config.getNodeId());
    }

    public synchronized void stop() {
        if (!isStarted) return;
        
        log.info("Node {} stopping. Current state: {}, Current term: {}", 
            config.getNodeId(), state, currentTerm.get());
        
        isStarted = false;
        isProcessing = false;
        
        // Stop all timers
        stopElectionTimer();
        stopHeartbeat();
        
        // Don't reset state or term - they should persist across restarts
        votedFor = null;
        currentLeader = null;
        
        log.info("Stopped RaftNode with ID: {} with term: {}", config.getNodeId(), currentTerm.get());
    }

    public synchronized void resetToFollower() {
        state = NodeState.FOLLOWER;
        currentTerm.set(0);
        votedFor = null;
        currentLeader = null;
        stopHeartbeat();
        resetElectionTimer();
        log.info("Node {} reset to follower state", config.getNodeId());
    }

    private void resetElectionTimer() {
        if (electionTimer != null) {
            electionTimer.cancel(false);
        }
        
        electionTimer = scheduler.schedule(
            this::startElection,
            config.getElectionTimeout(),
            TimeUnit.MILLISECONDS
        );
        
        log.debug("Reset election timer with timeout: {}ms", config.getElectionTimeout());
    }

    private synchronized void startElection() {
        if (state == NodeState.LEADER) {
            return;
        }
        
        // Convert to candidate
        state = NodeState.CANDIDATE;
        int oldTerm = currentTerm.get();
        currentTerm.incrementAndGet();
        log.info("Node {} starting election. Old term: {}, New term: {}, State: {}", 
            config.getNodeId(), oldTerm, currentTerm.get(), state);
        votedFor = config.getNodeId();
        currentLeader = null;
        votesReceived.clear();
        votesReceived.add(config.getNodeId());
        
        log.info("Node {} started election for term {} with state {}", 
            config.getNodeId(), currentTerm.get(), state);
        
        // Send RequestVote RPCs to all other nodes
        RequestVote request = RequestVote.builder()
            .term(currentTerm.get())
            .candidateId(config.getNodeId())
            .lastLogIndex(logManager.getLastLogIndex())
            .lastLogTerm(logManager.getLastLogTerm())
            .build();
            
        for (String peerId : config.getPeerNodeIds()) {
            networkManager.sendRequestVote(config.getNodeId(), peerId, request);
        }
        
        // Reset election timer
        resetElectionTimer();
    }

    public void handleRequestVote(String fromNodeId, RequestVote request) {
        synchronized (this) {
            log.info("Node {} received RequestVote from {} with term {}. Current term: {}", 
                config.getNodeId(), fromNodeId, request.getTerm(), currentTerm.get());
            
            if (request.getTerm() < currentTerm.get()) {
                // Reject request with current term
                sendRequestVoteResponse(fromNodeId, false);
                return;
            }
            
            if (request.getTerm() > currentTerm.get()) {
                // Update term and become follower
                int oldTerm = currentTerm.get();
                currentTerm.set(request.getTerm());
                log.info("Node {} updating term from {} to {} due to RequestVote from {}", 
                    config.getNodeId(), oldTerm, currentTerm.get(), fromNodeId);
                becomeFollower(request.getTerm());
            }
            
            // Check if we can grant vote
            boolean canGrantVote = votedFor == null || votedFor.equals(fromNodeId);
            boolean logIsUpToDate = request.getLastLogTerm() > logManager.getLastLogTerm() ||
                (request.getLastLogTerm() == logManager.getLastLogTerm() &&
                 request.getLastLogIndex() >= logManager.getLastLogIndex());
            
            if (canGrantVote && logIsUpToDate) {
                votedFor = fromNodeId;
                sendRequestVoteResponse(fromNodeId, true);
                resetElectionTimer();
            } else {
                sendRequestVoteResponse(fromNodeId, false);
            }
        }
    }

    private void sendRequestVoteResponse(String candidateId, boolean voteGranted) {
        RequestVoteResponse response = RequestVoteResponse.builder()
            .term(currentTerm.get())
            .voteGranted(voteGranted)
            .build();
            
        // Actually send the response
        networkManager.sendRequestVoteResponse(config.getNodeId(), candidateId, response);
        log.debug("Sent vote response to {}: {}", candidateId, voteGranted);
    }

    public void handleRequestVoteResponse(String candidateId, RequestVoteResponse response) {
        synchronized (this) {
            log.info("Node {} received RequestVoteResponse from {} with term {}. Current term: {}", 
                config.getNodeId(), candidateId, response.getTerm(), currentTerm.get());
            
            if (state != NodeState.CANDIDATE) {
                return;
            }
            
            if (response.getTerm() > currentTerm.get()) {
                int oldTerm = currentTerm.get();
                currentTerm.set(response.getTerm());
                log.info("Node {} updating term from {} to {} due to RequestVoteResponse from {}", 
                    config.getNodeId(), oldTerm, currentTerm.get(), candidateId);
                becomeFollower(response.getTerm());
                return;
            }
            
            if (response.isVoteGranted()) {
                votesReceived.add(candidateId);
                
                // Count peers in the same partition (including self)
                int peersInPartition = 1; // Start with 1 for self
                for (String peerId : config.getPeerNodeIds()) {
                    if (networkManager.canCommunicate(config.getNodeId(), peerId)) {
                        peersInPartition++;
                    }
                }
                
                // Calculate majority based on peers in the same partition
                int majority = (peersInPartition + 1) / 2;
                if (votesReceived.size() >= majority) {
                    becomeLeader();
                }
            }
        }
    }

    private synchronized void becomeLeader() {
        if (state != NodeState.CANDIDATE) {
            return;
        }
        
        log.info("Node {} becoming leader for term {}", config.getNodeId(), currentTerm.get());
        
        state = NodeState.LEADER;
        currentLeader = config.getNodeId();
        stopElectionTimer();
        
        // Initialize leader state
        for (String peerId : config.getPeerNodeIds()) {
            nextIndex.put(peerId, logManager.getLastLogIndex() + 1);
            matchIndex.put(peerId, 0);
        }
        
        // Start sending heartbeats
        startHeartbeat();
        
        log.info("Node {} became leader for term {}", config.getNodeId(), currentTerm.get());
    }

    private void startHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(false);
        }
        
        // Send initial heartbeat immediately
        sendHeartbeats();
        
        // Then schedule regular heartbeats
        heartbeatTimer = scheduler.scheduleAtFixedRate(
            this::sendHeartbeats,
            config.getHeartbeatInterval(),
            config.getHeartbeatInterval(),
            TimeUnit.MILLISECONDS
        );
    }

    private void sendHeartbeats() {
        if (state != NodeState.LEADER) {
            return;
        }
        
        AppendEntries heartbeat = AppendEntries.builder()
            .term(currentTerm.get())
            .leaderId(config.getNodeId())
            .prevLogIndex(logManager.getLastLogIndex())
            .prevLogTerm(logManager.getLastLogTerm())
            .entries(Collections.emptyList())
            .leaderCommit(logManager.getCommitIndex())
            .build();
            
        for (String peerId : config.getPeerNodeIds()) {
            networkManager.sendAppendEntries(config.getNodeId(), peerId, heartbeat);
        }
    }

    public void handleAppendEntries(String fromNodeId, AppendEntries request) {
        synchronized (this) {
            log.info("Node {} received AppendEntries from {} with term {}. Current term: {}", 
                config.getNodeId(), fromNodeId, request.getTerm(), currentTerm.get());
            
            // Check if we can communicate with the leader
            if (!networkManager.canCommunicate(fromNodeId, config.getNodeId())) {
                log.info("Node {} ignoring AppendEntries from {} - nodes are partitioned", 
                    config.getNodeId(), fromNodeId);
                return;
            }
            
            if (request.getTerm() < currentTerm.get()) {
                // Reject request with current term
                sendAppendEntriesResponse(fromNodeId, false);
                return;
            }
            
            if (request.getTerm() > currentTerm.get()) {
                // Update term and become follower
                int oldTerm = currentTerm.get();
                currentTerm.set(request.getTerm());
                log.info("Node {} updating term from {} to {} due to AppendEntries from {}", 
                    config.getNodeId(), oldTerm, currentTerm.get(), fromNodeId);
                becomeFollower(request.getTerm());
            }
            
            // Update current leader
            currentLeader = fromNodeId;
            
            // Reset election timer
            resetElectionTimer();
            
            // If this is a heartbeat (no entries), just respond
            if (request.getEntries() == null || request.getEntries().isEmpty()) {
                sendAppendEntriesResponse(fromNodeId, true);
                return;
            }
            
            // Process log entries
            // Check log consistency
            LogEntry prevEntry = logManager.getEntry(request.getPrevLogIndex());
            if (prevEntry == null || prevEntry.getTerm() != request.getPrevLogTerm()) {
                sendAppendEntriesResponse(fromNodeId, false);
                return;
            }
            
            // Append new entries
            for (LogEntry entry : request.getEntries()) {
                logManager.appendEntry(entry);
            }
            
            // Update commit index
            if (request.getLeaderCommit() > logManager.getCommitIndex()) {
                logManager.setCommitIndex(
                    Math.min(request.getLeaderCommit(), logManager.getLastLogIndex())
                );
            }
            
            sendAppendEntriesResponse(fromNodeId, true);
        }
    }

    private void sendAppendEntriesResponse(String leaderId, boolean success) {
        AppendEntriesResponse response = AppendEntriesResponse.builder()
            .term(currentTerm.get())
            .success(success)
            .build();
            
        // Actually send the response
        networkManager.sendAppendEntriesResponse(config.getNodeId(), leaderId, response);
        log.debug("Sent append entries response to {}: {}", leaderId, success);
    }

    public void handleAppendEntriesResponse(String followerId, AppendEntriesResponse response) {
        synchronized (this) {
            if (state != NodeState.LEADER) {
                return;
            }
            
            if (response.getTerm() > currentTerm.get()) {
                currentTerm.set(response.getTerm());
                becomeFollower(response.getTerm());
                return;
            }
            
            if (response.isSuccess()) {
                // Update nextIndex and matchIndex
                nextIndex.put(followerId, logManager.getLastLogIndex() + 1);
                matchIndex.put(followerId, logManager.getLastLogIndex());
            } else {
                // Decrement nextIndex and retry
                nextIndex.put(followerId, Math.max(0, nextIndex.get(followerId) - 1));
            }
        }
    }

    public synchronized void becomeFollower(int term) {
        currentTerm.set(term);
        state = NodeState.FOLLOWER;
        votedFor = null;
        stopHeartbeat();
        resetElectionTimer();
        
        log.info("Node {} became follower for term {}", config.getNodeId(), term);
    }

    private void stopElectionTimer() {
        if (electionTimer != null) {
            electionTimer.cancel(false);
            electionTimer = null;
        }
    }

    private void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(false);
            heartbeatTimer = null;
        }
    }

    public int getCurrentTerm() {
        return currentTerm.get();
    }

    public String getCurrentLeader() {
        return currentLeader;
    }

    public String getVotedFor() {
        return votedFor;
    }

    public String getNodeId() {
        return config.getNodeId();
    }

    public void stopProcessing() {
        isProcessing = false;
        log.info("Node {} stopped processing", config.getNodeId());
    }

    public void startProcessing() {
        isProcessing = true;
        log.info("Node {} started processing", config.getNodeId());
    }
} 