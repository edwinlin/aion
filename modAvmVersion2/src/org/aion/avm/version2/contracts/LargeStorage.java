package org.aion.avm.version2.contracts;

import avm.Blockchain;
import java.math.BigInteger;
import org.aion.avm.tooling.abi.Callable;

/**
 * This contract provides methods for adding data to storage. It counts and logs the number of items
 * that have been added to storage. It also allows querying the stored data.
 */
public class LargeStorage {

    private static BigInteger count = BigInteger.ZERO;

    @Callable
    public static void putStorage(byte[] key, byte[] value) {
        Blockchain.putStorage(convertToFittingKey(key), value);
        count = count.add(BigInteger.ONE);
        Blockchain.log(count.toByteArray());
    }

    @Callable
    public static String getStorage(byte[] key) {
        byte[] payload = Blockchain.getStorage(convertToFittingKey(key));
        return (null != payload) ? new String(payload) : null;
    }

    private static byte[] convertToFittingKey(byte[] raw) {
        // The key needs to be 32-bytes so either truncate or 0x0-pad the bytes from the string.
        byte[] key = new byte[32];
        int length = StrictMath.min(key.length, raw.length);
        System.arraycopy(raw, 0, key, 0, length);
        return key;
    }
}
