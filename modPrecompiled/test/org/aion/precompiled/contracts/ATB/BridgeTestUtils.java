package org.aion.precompiled.contracts.ATB;

import org.aion.base.type.AionAddress;
import org.aion.crypto.AddressSpecs;
import org.aion.crypto.HashUtil;
import org.aion.fastvm.ExecutionContext;
import org.aion.mcf.vm.types.DataWord;

public class BridgeTestUtils {
    static ExecutionContext dummyContext() {
        return context(AionAddress.ZERO_ADDRESS(), AionAddress.ZERO_ADDRESS(), new byte[0]);
    }

    static ExecutionContext context(AionAddress from, AionAddress to, byte[] txData) {
        final byte[] transactionHash = HashUtil.h256("transaction".getBytes());
        final AionAddress address = to;
        final AionAddress origin = from;
        final AionAddress caller = origin;
        final DataWord nrgPrice = DataWord.ONE;
        final long nrgLimit = 21000L;
        final DataWord callValue = DataWord.ZERO;
        final byte[] callData = txData;
        final int callDepth = 1;
        final int flag = 0;
        final int kind = 0;
        final AionAddress blockCoinbase =
                new AionAddress(
                        AddressSpecs.computeA0Address(HashUtil.h256("coinbase".getBytes())));
        long blockNumber = 0;
        long blockTimestamp = 0;
        long blockNrgLimit = 0;
        DataWord blockDifficulty = DataWord.ZERO;

        return new ExecutionContext(
                null,
                transactionHash,
                address,
                origin,
                caller,
                nrgPrice,
                nrgLimit,
                callValue,
                callData,
                callDepth,
                flag,
                kind,
                blockCoinbase,
                blockNumber,
                blockTimestamp,
                blockNrgLimit,
                blockDifficulty);
    }
}
