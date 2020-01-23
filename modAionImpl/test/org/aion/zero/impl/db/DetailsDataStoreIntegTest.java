package org.aion.zero.impl.db;

import static org.aion.zero.impl.blockchain.BlockchainTestUtils.addMiningBlock;
import static org.aion.zero.impl.blockchain.BlockchainTestUtils.addStakingBlock;
import static org.aion.zero.impl.blockchain.BlockchainTestUtils.deployLargeStorageContractTransaction;
import static org.aion.zero.impl.blockchain.BlockchainTestUtils.generateAccounts;
import static org.aion.zero.impl.blockchain.BlockchainTestUtils.generateNextMiningBlock;
import static org.aion.zero.impl.blockchain.BlockchainTestUtils.generateNextStakingBlock;
import static org.aion.zero.impl.blockchain.BlockchainTestUtils.generateRandomUnityChain;
import static org.aion.zero.impl.blockchain.BlockchainTestUtils.putToLargeStorageTransaction;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.aion.base.AionTransaction;
import org.aion.base.TransactionTypeRule;
import org.aion.crypto.ECKey;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.log.LogLevel;
import org.aion.types.AionAddress;
import org.aion.zero.impl.blockchain.StandaloneBlockchain;
import org.aion.zero.impl.vm.AvmPathManager;
import org.aion.zero.impl.vm.AvmTestConfig;
import org.aion.zero.impl.vm.TestResourceProvider;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit and integration tests for {@link DetailsDataStore}.
 *
 * @author Alexandra Roatis
 */
public class DetailsDataStoreIntegTest {
    private static final long unityForkBlock = 2;
    private TestResourceProvider resourceProvider;

    @Before
    public void setup() throws ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
        // reduce default logging levels
        Map<LogEnum, LogLevel> cfg = new HashMap<>();
//        cfg.put(LogEnum.TX, LogLevel.DEBUG);
//        cfg.put(LogEnum.VM, LogLevel.DEBUG);
        AionLoggerFactory.initAll(cfg);

        TransactionTypeRule.allowAVMContractTransaction();
        resourceProvider = TestResourceProvider.initializeAndCreateNewProvider(AvmPathManager.getPathOfProjectRootDirectory());
        // enable both AVMs without overlap
        AvmTestConfig.supportBothAvmVersions(0, unityForkBlock, 0);
    }

    @After
    public void tearDown() {
        AvmTestConfig.clearConfigurations();
    }

    /** Creates a blockchain with large storage. Ensures that only new contracts are updated. */
    @Test
    public void syncLargeStorage() {

        // setup accounts
        List<ECKey> accounts = generateAccounts(6);
        ECKey stakingRegistryOwner = accounts.get(0);
        List<ECKey> stakersOnChain = List.of(accounts.get(1), accounts.get(2), accounts.get(3));
        Map<Integer, Pair<ECKey, BigInteger>> contractOwners = new HashMap<>();
        contractOwners.put(0, Pair.of(accounts.get(4), BigInteger.ZERO));
        contractOwners.put(1, Pair.of(accounts.get(5), BigInteger.ZERO));

        // setup two identical blockchains
        StandaloneBlockchain.Builder builder = new StandaloneBlockchain.Builder();
        StandaloneBlockchain chain =
                builder.withValidatorConfiguration("simple")
                        .withDefaultAccounts(accounts)
                        .withAvmEnabled()
                        .build()
                        .bc;
        chain.forkUtility.enableUnityFork(unityForkBlock);

        // populate the chain for unity
        generateRandomUnityChain(chain, resourceProvider, 3, 1, stakersOnChain, stakingRegistryOwner, 0);

        // tracks all the deployed storage contracts
        LinkedList<AionAddress> deployed = new LinkedList<>();
        List<AionTransaction> txs = new ArrayList<>();

        for (int i = 0; i < 1; i++) {
            int accountNumber = i % 2;
            ECKey account = contractOwners.get(accountNumber).getLeft();
            BigInteger nonce =  contractOwners.get(accountNumber).getRight();

            // 1. create another storage contract
            AionTransaction tx = deployLargeStorageContractTransaction(resourceProvider.factoryForVersion2, account, nonce);
            deployed.addLast(tx.getDestinationAddress());
            nonce = nonce.add(BigInteger.ONE);

            addMiningBlock(chain, chain.getBestBlock(), List.of(tx));
            System.out.println(chain.getRepository().getTransactionStore().getTxInfo(tx.getTransactionHash()));

            // 2. call contracts to increase storage
            AionAddress contract = deployed.removeFirst();
            for (int j = 0; j < 10; j++) {
                txs.add(putToLargeStorageTransaction(
                                resourceProvider.factoryForVersion2,
                                account,
                                RandomUtils.nextBytes(10),
                                RandomUtils.nextBytes(10),
                                nonce,
                                contract));
                nonce = nonce.add(BigInteger.ONE);
            }

            contractOwners.put(accountNumber, Pair.of(account, nonce));

            addStakingBlock(chain, chain.getBestBlock(), txs, stakersOnChain.get(i % 3));
        }
    }
}
