package org.aion.zero.impl.pendingState;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.aion.util.types.ByteArrayWrapper;
import org.aion.zero.impl.blockchain.AionImpl.NetworkBestBlockCallback;
import org.aion.zero.impl.blockchain.AionImpl.PendingTxCallback;
import org.aion.zero.impl.blockchain.AionImpl.TransactionBroadcastCallback;
import org.aion.zero.impl.types.PendingTxDetails;
import org.aion.zero.impl.vm.common.VmFatalException;
import org.aion.base.AionTransaction;
import org.aion.base.PooledTransaction;
import org.aion.base.TxUtil;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.mcf.blockchain.Block;
import org.aion.txpool.TxPoolA0;
import org.aion.zero.impl.blockchain.AionBlockchainImpl;
import org.aion.zero.impl.types.TxResponse;
import org.aion.base.AccountState;
import org.aion.mcf.db.RepositoryCache;
import org.aion.txpool.Constant;
import org.aion.txpool.ITxPool;
import org.aion.types.AionAddress;
import org.aion.util.bytes.ByteUtil;
import org.aion.util.conversions.Hex;
import org.aion.zero.impl.vm.common.BlockCachingContext;
import org.aion.zero.impl.vm.common.BulkExecutor;
import org.aion.zero.impl.SystemExitCodes;
import org.aion.zero.impl.types.AionTxInfo;
import org.aion.zero.impl.valid.TXValidator;
import org.aion.zero.impl.valid.TransactionTypeValidator;
import org.aion.base.AionTxExecSummary;
import org.aion.base.AionTxReceipt;
import org.slf4j.Logger;

public class AionPendingStateImpl implements IPendingState {

    private static final Logger LOGGER_TX = AionLoggerFactory.getLogger(LogEnum.TX.toString());
    private static final Logger LOGGER_VM = AionLoggerFactory.getLogger(LogEnum.VM.toString());

    private static final int MAX_VALIDATED_PENDING_TXS = 8192;

    private final int MAX_TXCACHE_FLUSH_SIZE = MAX_VALIDATED_PENDING_TXS >> 2;

    private final int MAX_REPLAY_TX_BUFFER_SIZE = MAX_VALIDATED_PENDING_TXS >> 2;

    private AionBlockchainImpl blockchain;

    private final ITxPool txPool;

    private RepositoryCache<AccountState> pendingState;

    private AtomicReference<Block> currentBestBlock;

    private PendingTxCache pendingTxCache;

    /**
     * This buffer stores txs that come in with double the energy price as an existing tx with the same nonce
     *  They will be applied between blocks so it is easier for us to manage the state of the repo.
     */
    private List<AionTransaction> replayTxBuffer;

    private boolean testingMode;

    private boolean poolDumpEnable;

    private boolean isSeedMode;

    private boolean poolBackUpEnable;

    private Map<byte[], byte[]> backupPendingPoolAdd;
    private Map<byte[], byte[]> backupPendingCacheAdd;
    private Set<byte[]> backupPendingPoolRemove;

    private boolean closeToNetworkBest = true;

    private AtomicBoolean pendingTxReceivedforMining;
    private PendingTxCallback pendingTxCallback;
    private NetworkBestBlockCallback networkBestBlockCallback;
    private TransactionBroadcastCallback transactionBroadcastCallback;

    private final int networkSyncingGap = 128;

    private synchronized void backupPendingTx() {

        if (!backupPendingPoolAdd.isEmpty()) {
            blockchain.getRepository().addTxBatch(backupPendingPoolAdd, true);
        }

        if (!backupPendingCacheAdd.isEmpty()) {
            blockchain.getRepository().addTxBatch(backupPendingCacheAdd, false);
        }

        if (!backupPendingPoolRemove.isEmpty()) {
            blockchain.getRepository().removeTxBatch(backupPendingPoolRemove, true);
        }

        blockchain.getRepository().removeTxBatch(pendingTxCache.getClearTxHash(), false);
        blockchain.getRepository().flush();

        backupPendingPoolAdd.clear();
        backupPendingCacheAdd.clear();
        backupPendingPoolRemove.clear();
        pendingTxCache.clearCacheTxHash();
    }

    public AionPendingStateImpl(
            AionBlockchainImpl blockchain,
            long energyUpperBound,
            int txPendingTimeout,
            int maxTxCacheSize,
            boolean seedMode,
            boolean poolBackup,
            boolean poolDump,
            PendingTxCallback pendingTxCallback,
            NetworkBestBlockCallback networkBestBlockCallback,
            TransactionBroadcastCallback transactionBroadcastCallback,
            boolean forTest) {

        this.testingMode = forTest;
        this.isSeedMode = seedMode;
        this.blockchain = blockchain;
        this.currentBestBlock = new AtomicReference<>(blockchain.getBestBlock());

        if (isSeedMode) {
            // seedMode has no txpool setup.
            txPool = null;
            LOGGER_TX.info("Seed mode is enabled");
        } else {
            Properties prop = new Properties();
            // The BlockEnergyLimit will be updated when the best block found.
            prop.put(ITxPool.PROP_BLOCK_NRG_LIMIT, String.valueOf(energyUpperBound));
            prop.put(ITxPool.PROP_BLOCK_SIZE_LIMIT, String.valueOf(Constant.MAX_BLK_SIZE));
            prop.put(ITxPool.PROP_TX_TIMEOUT, String.valueOf(txPendingTimeout));
            this.txPool = new TxPoolA0(prop);
        }

        this.pendingTxCallback = pendingTxCallback;
        this.networkBestBlockCallback = networkBestBlockCallback;
        this.transactionBroadcastCallback = transactionBroadcastCallback;
        this.pendingTxReceivedforMining = new AtomicBoolean();

        // seedMode has no pool.
        this.poolDumpEnable = poolDump && !seedMode;
        this.poolBackUpEnable = poolBackup && !seedMode;
        this.replayTxBuffer = new ArrayList<>();
        this.pendingState = blockchain.getRepository().startTracking();
        this.pendingTxCache = new PendingTxCache(maxTxCacheSize, poolBackUpEnable);

        if (poolBackUpEnable) {
            this.backupPendingPoolAdd = new HashMap<>();
            this.backupPendingCacheAdd = new HashMap<>();
            this.backupPendingPoolRemove = new HashSet<>();

            // Trying to recover the pool backup first.
            recoverPoolnCache();
        }
    }

    public synchronized RepositoryCache<?> getRepository() {
        // Todo : no class use this method.
        return pendingState;
    }

    public int getPendingTxSize() {
        return isSeedMode ? 0 : this.txPool.size();
    }

    @Override
    public synchronized List<AionTransaction> getPendingTransactions() {
        return isSeedMode ? new ArrayList<>() : this.txPool.snapshot();
    }

    /**
     * Transaction comes from the ApiServer. Validate it first then add into the pendingPool.
     * Synchronized it because multiple Api interfaces call this method.
     * @param tx transaction comes from the ApiServer.
     * @return the TxResponse.
     */
    public synchronized TxResponse addTransactionFromApiServer(AionTransaction tx) {

        TxResponse response = validateTx(tx);
        if (response.isFail()) {
            LOGGER_TX.error("tx is not valid - code:[{}] tx[{}]", response.getVal(), tx.toString());
            return response;
        }

        // SeedMode or the syncing status will just broadcast the transaction to the network.
        if (isSeedMode || !closeToNetworkBest) {
            transactionBroadcastCallback.broadcastTransactions(Collections.singletonList(tx));
            return TxResponse.SUCCESS;
        }

        return addPendingTransactions(Collections.singletonList(tx)).get(0);
    }

    /**
     * The transactions come from the p2p network. We validate it first then add into the pendingPool.
     * @param transactions transaction list come from the network.
     */
    public synchronized void addTransactionsFromNetwork(List<AionTransaction> transactions) {
        List<AionTransaction> validTransactions = new ArrayList<>();

        for (AionTransaction tx : transactions) {
            if (!TXValidator.isInCache(ByteArrayWrapper.wrap(tx.getTransactionHash())) && !validateTx(tx).isFail()) {
                validTransactions.add(tx);
            }
        }

        // SeedMode or the syncing status will just broadcast the transaction to the network.
        if (isSeedMode || !closeToNetworkBest) {
            transactionBroadcastCallback.broadcastTransactions(validTransactions);
        } else {
            addPendingTransactions(validTransactions);
        }
    }

    private TxResponse validateTx(AionTransaction tx) {
        TxResponse response = TXValidator.validateTx(tx, blockchain.isUnityForkEnabledAtNextBlock());
        if (response.isFail()) {
            return response;
        }

        if (!TransactionTypeValidator.isValid(tx)) {
            return TxResponse.INVALID_TX_TYPE;
        }

        if (!blockchain.beaconHashValidator.validateTxForPendingState(tx)) {
            return TxResponse.INVALID_TX_BEACONHASH;
        }

        return TxResponse.SUCCESS;
    }

    /**
     * For the transactions come from the cache or backup, we can just verify the beaconHash. And skip
     * the transactions broadcast.
     * @param transactions transaction list come from the cache or backup.
     */
    private void addTransactionsFromCacheOrBackup(List<AionTransaction> transactions) {
        List<AionTransaction> validTransactions = new ArrayList<>();

        for (AionTransaction tx : transactions) {
            if (blockchain.beaconHashValidator.validateTxForPendingState(tx)) {
                validTransactions.add(tx);
            } else {
                fireDroppedTx(tx, "INVALID_TX_BEACON_HASH");
            }
        }

        addPendingTransactions(validTransactions);
    }

    /**
     * Tries to add the given transactions to the PendingState
     *
     * @param transactions, the list of AionTransactions to be added
     * @return a list of TxResponses of the same size as the input param transactions The entries in
     *     the returned list of responses correspond one-to-one with the input txs
     */
    private List<TxResponse> addPendingTransactions(
            List<AionTransaction> transactions) {

        List<AionTransaction> newPending = new ArrayList<>();
        List<AionTransaction> newLargeNonceTx = new ArrayList<>();
        List<TxResponse> txResponses = new ArrayList<>();

        for (AionTransaction tx : transactions) {
            BigInteger txNonce = tx.getNonceBI();
            BigInteger bestPSNonce = bestPendingStateNonce(tx.getSenderAddress());
            AionAddress txFrom = tx.getSenderAddress();

            int cmp = txNonce.compareTo(bestPSNonce);

            // This case happens when we have already received a tx with a larger nonce
            // from the address txFrom
            if (cmp > 0) {
                if (isInTxCache(txFrom, txNonce)) {
                    txResponses.add(TxResponse.ALREADY_CACHED);
                } else {
                    newLargeNonceTx.add(tx);
                    addToTxCache(tx);

                    if (poolBackUpEnable) {
                        backupPendingCacheAdd.put(tx.getTransactionHash(), tx.getEncoded());
                    }

                    if (LOGGER_TX.isTraceEnabled()) {
                        LOGGER_TX.trace(
                                "addPendingTransactions addToCache due to largeNonce: from = {}, nonce = {}",
                                txFrom,
                                txNonce);
                    }

                    // Transaction cached due to large nonce
                    txResponses.add(TxResponse.CACHED_NONCE);
                }
            }
            // This case happens when this transaction has been received before, but was
            // cached for some reason
            else if (cmp == 0) {
                if (txPool.size() > MAX_VALIDATED_PENDING_TXS) {
                    if (isInTxCache(txFrom, txNonce)) {
                        txResponses.add(TxResponse.ALREADY_CACHED);
                    } else {
                        newLargeNonceTx.add(tx);
                        addToTxCache(tx);

                        if (poolBackUpEnable) {
                            backupPendingCacheAdd.put(tx.getTransactionHash(), tx.getEncoded());
                        }

                        if (LOGGER_TX.isTraceEnabled()) {
                            LOGGER_TX.trace(
                                    "addPendingTransactions addToCache due to poolMax: from = {}, nonce = {}",
                                    txFrom,
                                    txNonce);
                        }

                        // Transaction cached because the pool is full
                        txResponses.add(TxResponse.CACHED_POOLMAX);
                    }
                } else {
                    // TODO: need to implement better cache return Strategy
                    Map<BigInteger, AionTransaction> cache = pendingTxCache.getCacheTx(txFrom);

                    int limit = 0;
                    Set<AionAddress> addr = pendingTxCache.getCacheTxAccount();
                    if (!addr.isEmpty()) {
                        limit = MAX_TXCACHE_FLUSH_SIZE / addr.size();

                        if (limit == 0) {
                            limit = 1;
                        }
                    }

                    if (LOGGER_TX.isTraceEnabled()) {
                        LOGGER_TX.trace(
                                "addPendingTransactions from cache: from {}, size {}",
                                txFrom,
                                cache.size());
                    }

                    boolean added = false;

                    do {
                        TxResponse implResponse = addPendingTransactionImpl(tx);
                        if (!added) {
                            txResponses.add(implResponse);
                            added = true;
                        }
                        if (implResponse.equals(TxResponse.SUCCESS)) {
                            newPending.add(tx);

                            if (poolBackUpEnable) {
                                backupPendingPoolAdd.put(tx.getTransactionHash(), tx.getEncoded());
                            }
                        } else {
                            break;
                        }

                        if (LOGGER_TX.isTraceEnabled()) {
                            LOGGER_TX.trace("cache: from {}, nonce {}", txFrom, txNonce.toString());
                        }

                        txNonce = txNonce.add(BigInteger.ONE);
                    } while (cache != null
                            && (tx = cache.get(txNonce)) != null
                            && (limit-- > 0)
                            && txPool.size() < MAX_VALIDATED_PENDING_TXS);
                }
            }
            // This case happens when this tx was received before, but never sealed,
            // typically because of low energy
            else if (bestRepoNonce(txFrom).compareTo(txNonce) < 1) {
                // repay Tx
                TxResponse implResponse = addPendingTransactionImpl(tx);
                if (implResponse.equals(TxResponse.SUCCESS)) {
                    newPending.add(tx);
                    txResponses.add(TxResponse.REPAID);

                    if (poolBackUpEnable) {
                        backupPendingPoolAdd.put(tx.getTransactionHash(), tx.getEncoded());
                    }
                } else {
                    txResponses.add(implResponse);
                }
            }
            // This should mean that the transaction has already been sealed in the repo
            else {
                txResponses.add(TxResponse.ALREADY_SEALED);
            }
        }

        if (LOGGER_TX.isTraceEnabled()) {
            LOGGER_TX.trace(
                    "Wire transaction list added: total: {}, newPending: {}, cached: {}, valid (added to pending): {} pool_size:{}",
                    transactions.size(),
                    newPending,
                    newLargeNonceTx.size(),
                    txPool.size());
        }

        if (!newPending.isEmpty()) {
            pendingTxCallback.pendingTxReceivedCallback(newPending);
            pendingTxReceivedforMining.set(true);
        }

        if (!testingMode && (!newPending.isEmpty() || !newLargeNonceTx.isEmpty())) {
            transactionBroadcastCallback.broadcastTransactions(
                    Stream.concat(newPending.stream(), newLargeNonceTx.stream())
                            .collect(Collectors.toList()));
        }

        return txResponses;
    }

    private boolean inPool(BigInteger txNonce, AionAddress from) {
        return (this.txPool.bestPoolNonce(from).compareTo(txNonce) > -1);
    }

    private void fireTxUpdate(AionTxReceipt txReceipt, PendingTransactionState state, Block block) {
        LOGGER_TX.trace(
                String.format(
                        "PendingTransactionUpdate: (Tot: %3s) %12s : %s %8s %s [%s]",
                        getPendingTxSize(),
                        state,
                        txReceipt
                                .getTransaction()
                                .getSenderAddress()
                                .toString()
                                .substring(0, 8),
                        ByteUtil.byteArrayToLong(txReceipt.getTransaction().getNonce()),
                        block.getShortDescr(),
                        txReceipt.getError()));

        pendingTxCallback.pendingTxStateUpdateCallback(new PendingTxDetails(state.getValue(), txReceipt, block.getNumber()));
    }

    /**
     * Executes pending tx on the latest best block Fires pending state update
     *
     * @param tx transaction come from API or P2P
     * @return SUCCESS if transaction gets NEW_PENDING state, else appropriate message such as
     *     DROPPED, INVALID_TX, etc.
     */
    private TxResponse addPendingTransactionImpl(final AionTransaction tx) {

        AionTxExecSummary txSum;
        boolean ip = inPool(tx.getNonceBI(), tx.getSenderAddress());
        if (ip) {
            // check energy usage
            PooledTransaction poolTx = txPool.getPoolTx(tx.getSenderAddress(), tx.getNonceBI());
            if (poolTx == null) {
                LOGGER_TX.error(
                        "addPendingTransactionImpl no same tx nonce in the pool {}", tx.toString());
                fireDroppedTx(tx, "REPAYTX_POOL_EXCEPTION");
                return TxResponse.REPAYTX_POOL_EXCEPTION;
            } else {
                long price = (poolTx.tx.getEnergyPrice() << 1);
                if (price > 0 && price <= tx.getEnergyPrice()) {
                    if (replayTxBuffer.size() < MAX_REPLAY_TX_BUFFER_SIZE) {
                        replayTxBuffer.add(tx);
                        return TxResponse.REPAID;
                    } else {
                        return TxResponse.DROPPED;
                    }
                } else {
                    fireDroppedTx(tx, "REPAYTX_LOWPRICE");
                    return TxResponse.REPAYTX_LOWPRICE;
                }
            }
        } else {
            txSum = executeTx(tx, false);
        }

        if (txSum.isRejected()) {
            if (LOGGER_TX.isTraceEnabled()) {
                LOGGER_TX.trace(
                        "addPendingTransactionImpl tx "
                                + Hex.toHexString(tx.getTransactionHash())
                                + " is rejected due to: {}",
                        txSum.getReceipt().getError());
            }
            fireTxUpdate(txSum.getReceipt(), PendingTransactionState.DROPPED, currentBestBlock.get());
            return TxResponse.DROPPED;
        } else {
            PooledTransaction pendingTx = new PooledTransaction(tx, txSum.getReceipt().getEnergyUsed());

            if (LOGGER_TX.isTraceEnabled()) {
                LOGGER_TX.trace("addPendingTransactionImpl validTx {}", tx.toString());
            }

            PooledTransaction rtn = this.txPool.add(pendingTx);
            if (rtn != null && !rtn.equals(pendingTx)) {
                AionTxReceipt rp = new AionTxReceipt();
                rp.setTransaction(rtn.tx);

                if (poolBackUpEnable) {
                    backupPendingPoolRemove.add(tx.getTransactionHash().clone());
                }
                fireTxUpdate(rp, PendingTransactionState.DROPPED, currentBestBlock.get());
            }

            fireTxUpdate(txSum.getReceipt(), PendingTransactionState.NEW_PENDING, currentBestBlock.get());

            return TxResponse.SUCCESS;
        }
    }

    private void fireDroppedTx(AionTransaction tx, String error) {

        if (LOGGER_TX.isErrorEnabled()) {
            LOGGER_TX.error("Tx dropped {} [{}]", error, tx.toString());
        }

        AionTxReceipt rp = new AionTxReceipt();
        rp.setTransaction(tx);
        rp.setError(error);
        fireTxUpdate(rp, PendingTransactionState.DROPPED, currentBestBlock.get());
    }

    private AionTxReceipt createDroppedReceipt(PooledTransaction pooledTx, String error) {
        AionTxReceipt txReceipt = new AionTxReceipt();
        txReceipt.setTransaction(pooledTx.tx);
        txReceipt.setError(error);
        return txReceipt;
    }

    private Block findCommonAncestor(Block b1, Block b2) {
        while (!b1.isEqual(b2)) {
            if (b1.getNumber() >= b2.getNumber()) {
                b1 = blockchain.getBlockByHash(b1.getParentHash());
            }

            if (b1.getNumber() < b2.getNumber()) {
                b2 = blockchain.getBlockByHash(b2.getParentHash());
            }
            if (b2 == null) {
                // shouldn't happen
                throw new RuntimeException(
                        "Pending state can't find common ancestor: one of blocks has a gap");
            }
        }
        return b1;
    }

    /**
     * AKI-608
     * The method called by the AionblockchainImpl through callback, currently it will block the block import.
     * TODO :  Sync or Async from the callback.
     * @param newBlock
     * @param receipts
     */
    @Override
    public synchronized void applyBlockUpdate(Block newBlock, List<AionTxReceipt> receipts) {

        if (isSeedMode) {
            // seed mode doesn't need to update the pendingState
            return;
        }

        if (currentBestBlock.get() != null && !currentBestBlock.get().isParentOf(newBlock)) {

            // need to switch the state to another fork

            Block commonAncestor = findCommonAncestor(currentBestBlock.get(), newBlock);

            if (LOGGER_TX.isDebugEnabled()) {
                LOGGER_TX.debug(
                        "New best block from another fork: "
                                + newBlock.getShortDescr()
                                + ", old best: "
                                + currentBestBlock.get().getShortDescr()
                                + ", ancestor: "
                                + commonAncestor.getShortDescr());
            }

            // first return back the transactions from forked blocks
            Block rollback = currentBestBlock.get();
            while (!rollback.isEqual(commonAncestor)) {
                if (LOGGER_TX.isDebugEnabled()) {
                    LOGGER_TX.debug("Rollback: {}", rollback.getShortDescr());
                }
                List<AionTransaction> atl = rollback.getTransactionsList();
                for (AionTransaction atx : atl) {
                    /* We can add the Tx directly to the pool with a junk energyConsumed value
                     because all txs in the pool are going to be re-run in rerunTxsInPool(best.get()) */
                    txPool.add(new PooledTransaction(atx, 1));
                }
                rollback = blockchain.getBlockByHash(rollback.getParentHash());
            }

            // rollback the state snapshot to the ancestor
            pendingState = blockchain.getRepository().getSnapshotTo(commonAncestor.getStateRoot()).startTracking();

            // next process blocks from new fork
            Block main = newBlock;
            List<Block> mainFork = new ArrayList<>();
            while (!main.isEqual(commonAncestor)) {
                if (LOGGER_TX.isDebugEnabled()) {
                    LOGGER_TX.debug("Mainfork: {}", main.getShortDescr());
                }

                mainFork.add(main);
                main = blockchain.getBlockByHash(main.getParentHash());
            }

            // processing blocks from ancestor to new block
            for (int i = mainFork.size() - 1; i >= 0; i--) {
                processBestInternal(mainFork.get(i), null);
            }
        } else {
            if (LOGGER_TX.isDebugEnabled()) {
                LOGGER_TX.debug("PendingStateImpl.processBest: " + newBlock.getShortDescr());
            }
            //noinspection unchecked
            processBestInternal(newBlock, receipts);
        }

        currentBestBlock.set(newBlock);

        closeToNetworkBest = isCloseToNetworkBest();
        LOGGER_TX.debug("PendingStateImpl.processBest: close to the network best: {}", closeToNetworkBest ? "true" : "false");

        rerunTxsInPool(currentBestBlock.get());

        txPool.updateBlkNrgLimit(currentBestBlock.get().getNrgLimit());

        flushCachePendingTx();

        if (poolBackUpEnable) {
            long t1 = System.currentTimeMillis();
            backupPendingTx();
            long t2 = System.currentTimeMillis();
            LOGGER_TX.debug("Pending state backupPending took {} ms", t2 - t1);
        }

        // This is for debug purpose, do not use in the regular kernel running.
        if (this.poolDumpEnable) {
            DumpPool();
        }
    }

    @Override
    public void setNewPendingReceiveForMining(boolean newPendingTxReceived) {
        pendingTxReceivedforMining.set(newPendingTxReceived);
    }

    private void flushCachePendingTx() {
        Set<AionAddress> cacheTxAccount = this.pendingTxCache.getCacheTxAccount();

        if (cacheTxAccount.isEmpty()) {
            return;
        }

        if (LOGGER_TX.isDebugEnabled()) {
            LOGGER_TX.debug(
                    "PendingStateImpl.flushCachePendingTx: acc#[{}]", cacheTxAccount.size());
        }

        Map<AionAddress, BigInteger> nonceMap = new HashMap<>();
        for (AionAddress addr : cacheTxAccount) {
            nonceMap.put(addr, bestPendingStateNonce(addr));
        }

        List<AionTransaction> newPendingTx = this.pendingTxCache.flush(nonceMap);

        if (LOGGER_TX.isDebugEnabled()) {
            LOGGER_TX.debug(
                    "PendingStateImpl.flushCachePendingTx: newPendingTx_size[{}]",
                    newPendingTx.size());
        }

        if (!newPendingTx.isEmpty()) {
            addTransactionsFromCacheOrBackup(newPendingTx);
        }
    }

    private void processBestInternal(Block block, List<AionTxReceipt> receipts) {

        clearPending(block, receipts);

        clearOutdated(block.getNumber());
    }

    private void clearOutdated(final long blockNumber) {

        List<PooledTransaction> outdated = new ArrayList<>();

        final long timeout = this.txPool.getOutDateTime();
        for (PooledTransaction pooledTx : this.txPool.getOutdatedList()) {
            outdated.add(pooledTx);

            if (poolBackUpEnable) {
                backupPendingPoolRemove.add(pooledTx.tx.getTransactionHash().clone());
            }
            // @Jay
            // TODO : considering add new state - TIMEOUT
            fireTxUpdate(
                    createDroppedReceipt(
                            pooledTx, "Tx was not included into last " + timeout + " seconds"),
                    PendingTransactionState.DROPPED,
                currentBestBlock.get());
        }

        if (LOGGER_TX.isDebugEnabled()) {
            LOGGER_TX.debug("clearOutdated block#[{}] tx#[{}]", blockNumber, outdated.size());
        }

        if (outdated.isEmpty()) {
            return;
        }

        txPool.remove(outdated);
    }

    @SuppressWarnings("unchecked")
    private void clearPending(Block block, List<AionTxReceipt> receipts) {
        List<AionTransaction> txsInBlock = block.getTransactionsList();

        if (txsInBlock == null) return;

        if (LOGGER_TX.isDebugEnabled()) {
            LOGGER_TX.debug(
                    "clearPending block#[{}] tx#[{}]",
                    block.getNumber(),
                    txsInBlock.size());
        }

        Map<AionAddress, BigInteger> accountNonce = new HashMap<>();
        int cnt = 0;
        for (AionTransaction tx : block.getTransactionsList()) {
            accountNonce.computeIfAbsent(
                    tx.getSenderAddress(),
                    k -> this.blockchain.getRepository().getNonce(tx.getSenderAddress()));

            if (LOGGER_TX.isTraceEnabled()) {
                LOGGER_TX.trace(
                        "Clear pending transaction, addr: {} hash: {}",
                        tx.getSenderAddress().toString(),
                        Hex.toHexString(tx.getTransactionHash()));
            }

            AionTxReceipt receipt;
            if (receipts != null) {
                receipt = receipts.get(cnt);
            } else {
                AionTxInfo info = getTransactionInfo(tx.getTransactionHash(), block.getHash());
                receipt = info.getReceipt();
            }

            if (poolBackUpEnable) {
                backupPendingPoolRemove.add(tx.getTransactionHash().clone());
            }
            fireTxUpdate(receipt, PendingTransactionState.INCLUDED, block);

            cnt++;
        }

        if (!accountNonce.isEmpty()) {
            this.txPool.removeTxsWithNonceLessThan(accountNonce);
        }
    }

    private AionTxInfo getTransactionInfo(byte[] txHash, byte[] blockHash) {
        AionTxInfo info = blockchain.getTransactionStore().getTxInfo(txHash, blockHash);
        AionTransaction tx =
                blockchain
                        .getBlockByHash(info.getBlockHash())
                        .getTransactionsList()
                        .get(info.getIndex());
        info.setTransaction(tx);
        return info;
    }

    @SuppressWarnings("UnusedReturnValue")
    private List<AionTransaction> rerunTxsInPool(Block block) {

        pendingState = blockchain.getRepository().startTracking();

        for (AionTransaction tx : replayTxBuffer) {
            // Add a junk energyConsumed value because it will get rerun soon after it is added
            txPool.add(new PooledTransaction(tx, tx.getEnergyLimit()));
        }
        replayTxBuffer.clear();

        List<AionTransaction> pendingTxl = this.txPool.snapshotAll();
        List<AionTransaction> rtn = new ArrayList<>();
        if (LOGGER_TX.isInfoEnabled()) {
            LOGGER_TX.info("rerunTxsInPool - snapshotAll tx[{}]", pendingTxl.size());
        }
        for (AionTransaction tx : pendingTxl) {
            if (LOGGER_TX.isTraceEnabled()) {
                LOGGER_TX.trace("rerunTxsInPool - loop: " + tx.toString());
            }

            AionTxExecSummary txSum = executeTx(tx, false);
            AionTxReceipt receipt = txSum.getReceipt();
            receipt.setTransaction(tx);

            if (txSum.isRejected()) {
                if (LOGGER_TX.isDebugEnabled()) {
                    LOGGER_TX.debug("Invalid transaction in txpool: {}", tx);
                }
                txPool.remove(new PooledTransaction(tx, receipt.getEnergyUsed()));

                if (poolBackUpEnable) {
                    backupPendingPoolRemove.add(tx.getTransactionHash().clone());
                }
                fireTxUpdate(receipt, PendingTransactionState.DROPPED, block);
            } else {
                fireTxUpdate(receipt, PendingTransactionState.PENDING, block);
                rtn.add(tx);
            }
        }

        return rtn;
    }

    private Set<AionAddress> getTxsAccounts(List<AionTransaction> txn) {
        Set<AionAddress> rtn = new HashSet<>();
        for (AionTransaction tx : txn) {
            rtn.add(tx.getSenderAddress());
        }
        return rtn;
    }

    private AionTxExecSummary executeTx(AionTransaction tx, boolean inPool) {

        Block bestBlk = currentBestBlock.get();
        if (LOGGER_TX.isTraceEnabled()) {
            LOGGER_TX.trace("executeTx: {}", Hex.toHexString(tx.getTransactionHash()));
        }

        try {
            // Booleans moved out here so their meaning is explicit.
            boolean isLocalCall = false;
            boolean incrementSenderNonce = !inPool;
            boolean checkBlockEnergyLimit = false;

            // this parameter should not be relevant to execution
            byte[] difficulty = bestBlk.getDifficulty();
            // the pending state is executed on top of the best block
            long currentBlockNumber = bestBlk.getNumber() + 1;
            // simulating future block
            long timestamp = bestBlk.getTimestamp() + 1;
            // the limit is not checked so making it unlimited
            long blockNrgLimit = Long.MAX_VALUE;
            // assuming same person will mine the future block
            AionAddress miner = bestBlk.getCoinbase();

            return BulkExecutor.executeTransactionWithNoPostExecutionWork(
                    difficulty,
                    currentBlockNumber,
                    timestamp,
                    blockNrgLimit,
                    miner,
                    tx,
                    pendingState,
                    isLocalCall,
                    incrementSenderNonce,
                    blockchain.forkUtility.is040ForkActive(currentBlockNumber),
                    checkBlockEnergyLimit,
                    LOGGER_VM,
                    BlockCachingContext.PENDING,
                    bestBlk.getNumber(),
                    blockchain.forkUtility.isUnityForkActive(currentBlockNumber));
        } catch (VmFatalException e) {
            LOGGER_VM.error("Shutdown due to a VM fatal error.", e);
            System.exit(SystemExitCodes.FATAL_VM_ERROR);
            return null;
        }
    }

    public synchronized BigInteger bestPendingStateNonce(AionAddress addr) {
        // Because the seedmode has no pendingPool concept, it only pass the transaction to the network directly.
        // So we will return the chainRepo nonce instead of pendingState nonce.
        return isSeedMode ? blockchain.getRepository().getNonce(addr) : this.pendingState.getNonce(addr);
    }

    private BigInteger bestRepoNonce(AionAddress addr) {
        return this.blockchain.getRepository().getNonce(addr);
    }

    private void addToTxCache(AionTransaction tx) {
        this.pendingTxCache.addCacheTx(tx);
    }

    private boolean isInTxCache(AionAddress addr, BigInteger nonce) {
        return this.pendingTxCache.isInCache(addr, nonce);
    }

    public synchronized void DumpPool() {
        List<AionTransaction> txn = txPool.snapshotAll();
        Set<AionAddress> addrs = new HashSet<>();
        LOGGER_TX.info("");
        LOGGER_TX.info("=========== SnapshotAll");
        for (AionTransaction tx : txn) {
            addrs.add(tx.getSenderAddress());
            LOGGER_TX.info("{}", tx.toString());
        }

        txn = txPool.snapshot();
        LOGGER_TX.info("");
        LOGGER_TX.info("=========== Snapshot");
        for (AionTransaction tx : txn) {
            LOGGER_TX.info("{}", tx.toString());
        }

        LOGGER_TX.info("");
        LOGGER_TX.info("=========== Pool best nonce");
        for (AionAddress addr : addrs) {
            LOGGER_TX.info("{} {}", addr.toString(), txPool.bestPoolNonce(addr));
        }

        LOGGER_TX.info("");
        LOGGER_TX.info("=========== Cache pending tx");
        Set<AionAddress> cacheAddr = pendingTxCache.getCacheTxAccount();
        for (AionAddress addr : cacheAddr) {
            Map<BigInteger, AionTransaction> cacheMap = pendingTxCache.getCacheTx(addr);
            if (cacheMap != null) {
                for (AionTransaction tx : cacheMap.values()) {
                    LOGGER_TX.info("{}", tx.toString());
                }
            }
        }

        LOGGER_TX.info("");
        LOGGER_TX.info("=========== db nonce");
        addrs.addAll(cacheAddr);
        for (AionAddress addr : addrs) {
            LOGGER_TX.info("{} {}", addr.toString(), bestRepoNonce(addr));
        }

        LOGGER_TX.info("");
        LOGGER_TX.info("=========== ps nonce");
        addrs.addAll(cacheAddr);
        for (AionAddress addr : addrs) {
            LOGGER_TX.info("{} {}", addr.toString(), bestPendingStateNonce(addr));
        }
    }

    private void recoverPoolnCache() {
        recoverPool();
        recoverCache();
    }

    private void recoverCache() {

        LOGGER_TX.info("pendingCacheTx loading from DB");
        long t1 = System.currentTimeMillis();
        //noinspection unchecked
        List<byte[]> pendingCacheTxBytes = blockchain.getRepository().getCacheTx();

        List<AionTransaction> pendingTx = new ArrayList<>();
        for (byte[] b : pendingCacheTxBytes) {
            try {
                pendingTx.add(TxUtil.decode(b));
            } catch (Exception e) {
                LOGGER_TX.error("loadingPendingCacheTx error ", e);
            }
        }

        Map<AionAddress, SortedMap<BigInteger, AionTransaction>> sortedMap = new HashMap<>();
        for (AionTransaction tx : pendingTx) {
            if (sortedMap.get(tx.getSenderAddress()) == null) {
                SortedMap<BigInteger, AionTransaction> accountSortedMap = new TreeMap<>();
                accountSortedMap.put(tx.getNonceBI(), tx);

                sortedMap.put(tx.getSenderAddress(), accountSortedMap);
            } else {
                sortedMap.get(tx.getSenderAddress()).put(tx.getNonceBI(), tx);
            }
        }

        int cnt = 0;
        for (Map.Entry<AionAddress, SortedMap<BigInteger, AionTransaction>> e :
                sortedMap.entrySet()) {
            for (AionTransaction tx : e.getValue().values()) {
                pendingTxCache.addCacheTx(tx);
                cnt++;
            }
        }

        long t2 = System.currentTimeMillis() - t1;
        LOGGER_TX.info("{} pendingCacheTx loaded from DB into the pendingCache, {} ms", cnt, t2);
    }

    private void recoverPool() {

        LOGGER_TX.info("pendingPoolTx loading from DB");
        long t1 = System.currentTimeMillis();
        //noinspection unchecked
        List<byte[]> pendingPoolTxBytes = blockchain.getRepository().getPoolTx();

        List<AionTransaction> pendingTx = new ArrayList<>();
        for (byte[] b : pendingPoolTxBytes) {
            try {
                pendingTx.add(TxUtil.decode(b));
            } catch (Exception e) {
                LOGGER_TX.error("loadingCachePendingTx error ", e);
            }
        }

        Map<AionAddress, SortedMap<BigInteger, AionTransaction>> sortedMap = new HashMap<>();
        for (AionTransaction tx : pendingTx) {
            if (sortedMap.get(tx.getSenderAddress()) == null) {
                SortedMap<BigInteger, AionTransaction> accountSortedMap = new TreeMap<>();
                accountSortedMap.put(tx.getNonceBI(), tx);

                sortedMap.put(tx.getSenderAddress(), accountSortedMap);
            } else {
                sortedMap.get(tx.getSenderAddress()).put(tx.getNonceBI(), tx);
            }
        }

        List<AionTransaction> pendingPoolTx = new ArrayList<>();

        for (Map.Entry<AionAddress, SortedMap<BigInteger, AionTransaction>> e :
                sortedMap.entrySet()) {
            pendingPoolTx.addAll(e.getValue().values());
        }

        addTransactionsFromCacheOrBackup(pendingPoolTx);
        long t2 = System.currentTimeMillis() - t1;
        LOGGER_TX.info(
                "{} pendingPoolTx loaded from DB loaded into the txpool, {} ms",
                pendingPoolTx.size(),
                t2);
    }

    public String getVersion() {
        return isSeedMode ? "0" : this.txPool.getVersion();
    }

    private boolean isCloseToNetworkBest() {
        return currentBestBlock.get().getNumber() >= (networkBestBlockCallback.getNetworkBestBlockNumber() - networkSyncingGap);
    }
}
