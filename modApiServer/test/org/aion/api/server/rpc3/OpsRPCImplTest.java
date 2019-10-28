package org.aion.api.server.rpc3;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.aion.base.AionTransaction;
import org.aion.base.AionTxReceipt;
import org.aion.crypto.HashUtil;
import org.aion.mcf.blockchain.Block;
import org.aion.rpc.types.RPCTypes.BlockSpecifier;
import org.aion.rpc.types.RPCTypes.BlockSpecifierUnion;
import org.aion.rpc.types.RPCTypes.ByteArray;
import org.aion.rpc.types.RPCTypes.ParamUnion;
import org.aion.rpc.types.RPCTypes.Request;
import org.aion.rpc.types.RPCTypes.VersionType;
import org.aion.rpc.types.RPCTypesConverter.BlockSpecifierConverter;
import org.aion.types.AionAddress;
import org.aion.types.Log;
import org.aion.util.types.ByteArrayWrapper;
import org.aion.zero.impl.types.AionBlock;
import org.aion.zero.impl.types.AionTxInfo;
import org.aion.zero.impl.types.StakingBlock;
import org.aion.zero.impl.types.StakingBlockHeader;
import org.junit.Before;
import org.junit.Test;

public class OpsRPCImplTest {
    private ChainHolder holder = mock(ChainHolder.class);
    private OpsRPCImpl opsRPC = new OpsRPCImpl(holder);
    private Block emptyPowBlock;
    private Block emptyPosBlock;
    private AionTxInfo txInfo;
    private List<AionTransaction> txList = new ArrayList<>();

    @Before
    public void setup() {
        emptyPowBlock = AionBlock.newEmptyBlock();
        emptyPowBlock.setMainChain();
        AionTxReceipt receipt = new AionTxReceipt();
        receipt.setError("");
        receipt.setExecutionResult(HashUtil.h256(BigInteger.ONE.toByteArray()));

        List<Log> infos = new ArrayList<>();
        receipt.setLogs(infos);
        receipt.setPostTxState(HashUtil.h256(BigInteger.ONE.toByteArray()));

        txInfo =
                AionTxInfo.newInstance(
                        receipt,
                        ByteArrayWrapper.wrap(HashUtil.h256(BigInteger.ZERO.toByteArray())),
                        0);
        txInfo.getReceipt()
                .setTransaction(
                        AionTransaction.createWithoutKey(
                                BigInteger.ZERO.toByteArray(),
                                new AionAddress(new byte[32]),
                                new AionAddress(new byte[32]),
                                BigInteger.ZERO.toByteArray(),
                                BigInteger.ZERO.toByteArray(),
                                10,
                                10,
                                (byte) 0b1,
                                HashUtil.h256(BigInteger.ZERO.toByteArray())));
        txList.add(txInfo.getReceipt().getTransaction());
        StakingBlockHeader.Builder builder =
                StakingBlockHeader.Builder.newInstance()
                        .withDefaultCoinbase()
                        .withDefaultDifficulty()
                        .withDefaultExtraData()
                        .withDefaultLogsBloom()
                        .withDefaultParentHash()
                        .withDefaultReceiptTrieRoot()
                        .withDefaultSeed()
                        .withDefaultSignature()
                        .withDefaultSigningPublicKey()
                        .withDefaultStateRoot()
                        .withDefaultTxTrieRoot();
        emptyPosBlock = new StakingBlock(builder.build(), txList);
        doReturn(BigInteger.ONE).when(holder).calculateReward(any());
        doReturn(emptyPowBlock).when(holder).getBlockByNumber(1);
        doReturn(emptyPosBlock).when(holder).getBlockByNumber(2);
        doReturn(emptyPosBlock).when(holder).getBlockByHash(emptyPosBlock.getHash());
        doReturn(emptyPowBlock).when(holder).getBlockByHash(emptyPowBlock.getHash());
        doReturn(emptyPowBlock).when(holder).getBestBlock();
        doReturn(txInfo).when(holder).getTransactionInfo(any());
        doReturn(BigInteger.ONE).when(holder).getTotalDifficultyByHash(any());
    }

    @Test
    public void ops_getBlockDetails() {
        opsRPC.ops_getBlockDetails(new BlockSpecifierUnion(1L));
        opsRPC.ops_getBlockDetails(new BlockSpecifierUnion(2L));
        opsRPC.ops_getBlockDetails(
                new BlockSpecifierUnion(ByteArray.wrap(emptyPowBlock.getHash())));
        opsRPC.ops_getBlockDetails(
                new BlockSpecifierUnion(ByteArray.wrap(emptyPosBlock.getHash())));
    }

    @Test
    public void executeRequest() {
        opsRPC.execute(
                new Request(
                        1,
                        "ops_getBlockDetails",
                        ParamUnion.wrap(new BlockSpecifier(new BlockSpecifierUnion(1L))),
                        VersionType.Version2));

        opsRPC.execute(
                new Request(
                        1,
                        "ops_getBlockDetails",
                        ParamUnion.wrap(BlockSpecifierConverter.decode("[latest]")),
                        VersionType.Version2));

        opsRPC.execute(
                new Request(
                        1,
                        "ops_getBlockDetails",
                        ParamUnion.wrap(
                                BlockSpecifierConverter.decode(
                                        "{\"block\": \""
                                                + ByteArray.wrap(emptyPowBlock.getHash())
                                                + "\"}")),
                        VersionType.Version2));
    }
}