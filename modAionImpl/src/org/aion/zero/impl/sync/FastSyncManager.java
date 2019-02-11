package org.aion.zero.impl.sync;

import com.google.common.annotations.VisibleForTesting;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aion.base.util.ByteArrayWrapper;
import org.aion.zero.impl.AionBlockchainImpl;
import org.aion.zero.impl.types.AionBlock;

/**
 * Directs behavior for fast sync functionality.
 *
 * @author Alexandra Roatis
 */
public final class FastSyncManager {

    private boolean enabled;
    private final AtomicBoolean complete = new AtomicBoolean(false);

    private final Map<ByteArrayWrapper, byte[]> importedTrieNodes = new ConcurrentHashMap<>();

    private final BlockingQueue<ByteArrayWrapper> requiredWorldState = new LinkedBlockingQueue<>();

    private AionBlock pivot = null;
    private long pivotNumber = -1;
    private final AionBlockchainImpl chain;

    public FastSyncManager() {
        this.enabled = false;
        this.chain = null;
    }

    public FastSyncManager(AionBlockchainImpl chain) {
        this.enabled = true;
        this.chain = chain;
    }

    public void addImportedNode(ByteArrayWrapper key, byte[] value, DatabaseType dbType) {
        if (enabled) {
            // TODO: differentiate based on database
            importedTrieNodes.put(key, value);
        }
    }

    public boolean containsExact(ByteArrayWrapper key, byte[] value) {
        return enabled
                && importedTrieNodes.containsKey(key)
                && Arrays.equals(importedTrieNodes.get(key), value);
    }

    @VisibleForTesting
    void setPivot(AionBlock pivot) {
        Objects.requireNonNull(pivot);

        this.pivot = pivot;
        this.pivotNumber = pivot.getNumber();
    }

    /** Changes the pivot in case of import failure. */
    public void handleFailedImport(
            ByteArrayWrapper key, byte[] value, DatabaseType dbType, int peerId, String peer) {
        if (enabled) {
            // TODO: received incorrect or inconsistent state: change pivot??
            // TODO: consider case where someone purposely sends incorrect values
            // TODO: decide on how far back to move the pivot
        }
    }

    /**
     * Indicates the status of the fast sync process.
     *
     * @return {@code true} when fast sync is complete and secure, {@code false} while trie nodes
     *     are still required or completeness has not been confirmed yet
     */
    public boolean isComplete() {
        return !enabled || complete.get();
    }

    /**
     * Checks that all the conditions for completeness are fulfilled.
     *
     * @implNote Expensive functionality which should not be called frequently.
     */
    private void ensureCompleteness() {
        // already complete, do nothing
        if (isComplete()) {
            return;
        }

        // TODO: differentiate between requirements of light clients and full nodes

        // ensure all blocks were received
        if (!isCompleteBlockData()) {
            return;
        }

        // ensure all transaction receipts were received
        if (!isCompleteReceiptData()) {
            return;
        }

        // ensure complete world state for pivot was received
        if (!isCompleteWorldState()) {
            return;
        }

        // ensure complete contract details data was received
        if (!isCompleteContractDetails()) {
            return;
        }

        // ensure complete storage data was received
        if (!isCompleteStorage()) {
            return;
        }

        // everything is complete
        complete.set(true);
    }

    private boolean isCompleteBlockData() {
        // TODO: block requests should be made backwards from pivot
        // TODO: requests need to be based on hash instead of level
        return false;
    }

    private boolean isCompleteReceiptData() {
        // TODO: integrated implementation from separate branch
        return false;
    }

    @VisibleForTesting
    boolean isCompleteWorldState() {
        if (pivot == null) {
            return false;
        } else {
            // get root of pivot
            byte[] root = pivot.getStateRoot();

            // traverse trie from root to find missing nodes
            Set<ByteArrayWrapper> missing = chain.traverseTrieFromNode(root, DatabaseType.STATE);

            // clearing the queue to ensure we're not still requesting already received nodes
            requiredWorldState.clear();

            if (missing.isEmpty()) {
                return true;
            } else {
                requiredWorldState.addAll(missing);
                return false;
            }
        }
    }

    private boolean isCompleteContractDetails() {
        // TODO: implement
        return false;
    }

    private boolean isCompleteStorage() {
        // TODO: implement
        return false;
    }

    public void updateRequests(
            ByteArrayWrapper topmostKey,
            Set<ByteArrayWrapper> referencedKeys,
            DatabaseType dbType) {
        if (enabled) {
            // TODO: check what's still missing and send out requests
            // TODO: send state request to multiple peers

            // TODO: check for completeness when no requests remain
            ensureCompleteness();
        }
    }
}