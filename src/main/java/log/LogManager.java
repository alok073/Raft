package log;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages the Raft log entries and related indices.
 */
@Slf4j
public class LogManager {
    private final List<LogEntry> logs;
    private final ReadWriteLock lock;
    
    @Getter
    private volatile int commitIndex = -1;
    
    @Getter
    private volatile int lastApplied = -1;

    public LogManager() {
        this.logs = new ArrayList<>();
        this.lock = new ReentrantReadWriteLock();
        // Add initial dummy entry at index 0
        logs.add(new LogEntry(0, 0, new byte[0]));
    }

    public LogEntry getEntry(int index) {
        lock.readLock().lock();
        try {
            if (index < 0 || index >= logs.size()) {
                return null;
            }
            return logs.get(index);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getLastLogIndex() {
        lock.readLock().lock();
        try {
            return logs.size() - 1;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getLastLogTerm() {
        lock.readLock().lock();
        try {
            return logs.get(getLastLogIndex()).getTerm();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void appendEntry(LogEntry entry) {
        lock.writeLock().lock();
        try {
            logs.add(entry);
            log.debug("Appended entry: {}", entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void deleteEntriesFrom(int index) {
        lock.writeLock().lock();
        try {
            while (logs.size() > index) {
                logs.remove(logs.size() - 1);
            }
            log.debug("Deleted entries from index: {}", index);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setCommitIndex(int commitIndex) {
        this.commitIndex = commitIndex;
        log.debug("Updated commit index to: {}", commitIndex);
    }

    public void setLastApplied(int lastApplied) {
        this.lastApplied = lastApplied;
        log.debug("Updated last applied to: {}", lastApplied);
    }
} 