# Raft Consensus Algorithm Implementation

This project implements the Raft consensus algorithm in Java. The best way to understand the implementation is through the test cases in `SimulatorTest.java`, which covers all the key scenarios of the Raft protocol:

1. Initial leader election
2. Node failures and recovery
3. Network partitions
4. Log replication
5. Leader election during partitions

Each test case demonstrates a specific aspect of the Raft protocol and how our implementation handles it.

## Raft Algorithm Overview

Raft is a consensus algorithm that ensures all nodes in a distributed system agree on a sequence of values. It works by:

1. Electing a leader
2. Having the leader manage log replication
3. Ensuring safety through leader election and log consistency

### Key Components

1. **Leader Election**
   - Each node starts as a follower
   - If a follower doesn't receive heartbeats, it becomes a candidate
   - A candidate requests votes from other nodes
   - If it gets votes from majority, it becomes leader
   - If another node becomes leader, it becomes follower

2. **Log Replication**
   - Leader receives client requests
   - Leader appends entries to its log
   - Leader sends AppendEntries RPCs to followers
   - Followers append entries to their logs
   - Leader commits entries when majority have replicated

3. **Safety**
   - Only nodes with up-to-date logs can become leader
   - Log consistency is maintained through AppendEntries
   - Leader election ensures single leader per term

### Pseudo Code

#### Node States
- FOLLOWER: Initial state, responds to RPCs
- CANDIDATE: During election, requests votes
- LEADER: Handles all client requests

#### Persistent State (survives crashes)
- currentTerm: Latest term seen
- votedFor: Node voted for in current term
- log: List of log entries

#### Volatile State (reset on restart)
- state: Current node state
- currentLeader: ID of current leader

#### Leader State (only used when leader)
- nextIndex[]: Next log entry to send to each follower
- matchIndex[]: Highest log entry replicated to each follower

#### Leader Election
```
On node start:
    state = FOLLOWER
    set random election timeout (150-300ms)
    wait for heartbeats

On election timeout:
    state = CANDIDATE
    currentTerm++
    votedFor = self
    votesReceived = {self}
    send RequestVote to all peers
    reset election timer

On receiving RequestVote:
    if term < currentTerm:
        reject
    if term > currentTerm:
        currentTerm = term
        state = FOLLOWER
    if votedFor is null and log is up-to-date:
        votedFor = candidateId
        reset election timer
        return true
    reject

On becoming leader:
    initialize nextIndex[] for each follower
    initialize matchIndex[] for each follower
    start sending heartbeats
```

#### Log Replication
```
On leader receiving client request:
    append entry to local log
    send AppendEntries to all followers
    wait for majority acknowledgment
    when majority acknowledged:
        update commitIndex
        apply to state machine
        send result to client

On follower receiving AppendEntries:
    if term < currentTerm:
        reject
    if term > currentTerm:
        currentTerm = term
        state = FOLLOWER
    if log doesn't match at prevLogIndex:
        reject
    append new entries
    update commitIndex
    apply to state machine
```

#### Network Partition Handling
```
On partition creation:
    nodes in partition1:
        keep existing leader
        continue normal operation
    nodes in partition2:
        don't receive heartbeats
        start election after timeout
        elect new leader if majority available

On partition healing:
    leaders discover each other
    higher term leader wins
    lower term leader becomes follower
    log consistency is restored
```

#### Node Failure Handling
```
On node failure:
    other nodes detect missing heartbeats
    start election if needed
    continue with remaining nodes

On node recovery:
    node starts as follower
    discovers current term
    syncs log with leader
    joins cluster
```

## Project Structure

```
src/
├── main/java/
│   ├── node/
│   │   ├── RaftNode.java       # Core Raft node implementation
│   │   └── NodeConfig.java     # Node configuration
│   ├── network/
│   │   └── NetworkManager.java # Network communication and partitioning
│   ├── log/
│   │   ├── LogEntry.java      # Log entry representation
│   │   └── LogManager.java    # Log management
│   └── rpc/
│       ├── RequestVote.java   # RequestVote RPC
│       └── AppendEntries.java # AppendEntries RPC
└── test/java/
    └── simulation/
        └── SimulatorTest.java # Test cases for Raft scenarios
```

## Key Features

1. **Leader Election**
   - Random election timeouts to prevent split votes
   - Majority-based leader election
   - Term-based election safety

2. **Log Replication**
   - Log consistency checking
   - Log matching optimization
   - Commit index management

3. **Network Partitioning**
   - Partition creation and removal
   - Communication blocking between partitions
   - Leader election during partitions

4. **Node Failures**
   - Node failure simulation
   - Recovery handling
   - State persistence across restarts 