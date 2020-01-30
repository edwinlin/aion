package org.aion.zero.impl.db;

import static org.aion.util.types.ByteArrayWrapper.wrap;

import java.util.Iterator;
import java.util.Optional;
import org.aion.db.impl.ByteArrayKeyValueDatabase;
import org.aion.db.store.JournalPruneDataSource;
import org.aion.mcf.db.InternalVmType;
import org.aion.rlp.RLP;
import org.aion.rlp.RLPElement;
import org.aion.rlp.RLPList;
import org.aion.types.AionAddress;
import org.aion.util.types.ByteArrayWrapper;
import org.slf4j.Logger;

/** Detail data storage , */
public class DetailsDataStore {
    private JournalPruneDataSource storageDSPrune;

    private ByteArrayKeyValueDatabase detailsSrc;
    private ByteArrayKeyValueDatabase storageSrc;
    private ByteArrayKeyValueDatabase graphSrc;
    private Logger log;

    public DetailsDataStore(
            ByteArrayKeyValueDatabase detailsCache,
            ByteArrayKeyValueDatabase storageCache,
            ByteArrayKeyValueDatabase graphCache,
            Logger log) {
        this.detailsSrc = detailsCache;
        this.storageSrc = storageCache;
        this.graphSrc = graphCache;
        this.log = log;
        this.storageDSPrune = new JournalPruneDataSource(storageSrc, log);
    }

    /**
     * Fetches the ContractDetails with the given root.
     *
     * @param vm the virtual machine used at contract deployment
     * @param key the contract address as bytes
     * @param storageRoot the requested storage root
     * @return a snapshot of the contract details with the requested root
     */
    public synchronized AionContractDetailsImpl getSnapshot(InternalVmType vm, byte[] key, byte[] storageRoot) {
        Optional<byte[]> rawDetails = detailsSrc.get(key);

        if (rawDetails.isPresent()) {
            // decode raw details and return snapshot
            AionContractDetailsImpl detailsImpl = new AionContractDetailsImpl(storageDSPrune, graphSrc);
            detailsImpl.setVmType(vm);
            detailsImpl.decode(fromEncoding(rawDetails.get()));
            return detailsImpl.getSnapshotTo(storageRoot, vm);
        } else {
            return null;
        }
    }

    /** Determine if the contract exists in the database. */
    public synchronized boolean isPresent(byte[] key) {
        Optional<byte[]> rawDetails = detailsSrc.get(key);
        return rawDetails.isPresent();
    }

    public synchronized void update(AionAddress key, AionContractDetailsImpl contractDetails) {

        contractDetails.setAddress(key);
        contractDetails.setObjectGraphSource(graphSrc);

        // Put into cache.
        byte[] rawDetails = contractDetails.getEncoded();
        detailsSrc.put(key.toByteArray(), rawDetails);

        contractDetails.syncStorage();
    }

    public synchronized void remove(byte[] key) {
        detailsSrc.delete(key);
    }

    public JournalPruneDataSource getStorageDSPrune() {
        return storageDSPrune;
    }

    public synchronized Iterator<ByteArrayWrapper> keys() {
        return new DetailsIteratorWrapper(detailsSrc.keys());
    }

    public synchronized void close() {
        try {
            detailsSrc.close();
            storageSrc.close();
            graphSrc.close();
        } catch (Exception e) {
            throw new RuntimeException("error closing db");
        }
    }

    /**
     * A wrapper for the iterator needed by {@link DetailsDataStore} conforming to the {@link
     * Iterator} interface.
     *
     * @author Alexandra Roatis
     */
    private class DetailsIteratorWrapper implements Iterator<ByteArrayWrapper> {
        private Iterator<byte[]> sourceIterator;

        /**
         * @implNote Building two wrappers for the same {@link Iterator} will lead to inconsistent
         *     behavior.
         */
        DetailsIteratorWrapper(final Iterator<byte[]> sourceIterator) {
            this.sourceIterator = sourceIterator;
        }

        @Override
        public boolean hasNext() {
            return sourceIterator.hasNext();
        }

        @Override
        public ByteArrayWrapper next() {
            return wrap(sourceIterator.next());
        }
    }

    /**
     * Container used to store a partial decoding of contract details.
     *
     * @author Alexandra Roatis
     */
    public static class RLPContractDetails {
        public final AionAddress address;
        public final boolean isExternalStorage;
        public final RLPElement storageRoot;
        public final RLPElement storageTrie;
        public final RLPElement code;

        public RLPContractDetails(
                AionAddress address,
                boolean isExternalStorage,
                RLPElement storageRoot,
                RLPElement storageTrie,
                RLPElement code) {
            this.address = address;
            this.isExternalStorage = isExternalStorage;
            this.storageRoot = storageRoot;
            this.storageTrie = storageTrie;
            this.code = code;
        }
    }

    /**
     * Extracts an RLPContractDetails object from the RLP encoding.
     *
     * Accepted encodings:
     * <ul>
     *   <li>{ 0:address, 1:isExternalStorage, 2:storageRoot, 3:storageTrie, 4:code }
     *   <li>{ 0:address, 1:storageRoot, 2:code }
     * </ul>
     *
     * @param encoding The encoded ContractDetails to decode.
     */
    public static RLPContractDetails fromEncoding(byte[] encoding) {
        if (encoding == null) {
            throw new NullPointerException("Cannot decode ContractDetails from null RLP encoding.");
        }
        if (encoding.length == 0) {
            throw new IllegalArgumentException("Cannot decode ContractDetails from empty RLP encoding.");
        }

        RLPList decoded = (RLPList) (RLP.decode2(encoding)).get(0);
        int elements = decoded.size();

        if (elements == 3 || elements == 5) {
            // extract 0:address from the encoding
            AionAddress address;
            RLPElement addressRLP = decoded.get(0);
            if (addressRLP == null || addressRLP.getRLPData() == null || addressRLP.getRLPData().length != AionAddress.LENGTH) {
                throw new IllegalArgumentException("Cannot decode ContractDetails with invalid contract address.");
            } else {
                address = new AionAddress(addressRLP.getRLPData());
            }
            if (elements == 3) {
                return new RLPContractDetails(address, true, decoded.get(1), null, decoded.get(2));
            } else {
                boolean isExternalStorage = decoded.get(1).getRLPData().length > 0;
                return new RLPContractDetails(address, isExternalStorage, decoded.get(2), decoded.get(3), decoded.get(4));
            }
        } else {
            throw new IllegalStateException("Incompatible data storage. Please shutdown the kernel and perform database migration to version 1.0 (Denali) of the kernel as instructed in the release.");
        }
    }
}
