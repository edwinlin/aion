package org.aion.zero.impl.precompiled.contracts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.aion.crypto.ECKey;
import org.aion.crypto.ECKeyFac;
import org.aion.precompiled.ContractInfo;
import org.aion.precompiled.PrecompiledTransactionResult;
import org.aion.precompiled.contracts.TotalCurrencyContract;
import org.aion.precompiled.type.CapabilitiesProvider;
import org.aion.types.AionAddress;
import org.aion.util.types.DataWord;
import org.aion.zero.impl.precompiled.ExternalStateForTests;
import org.aion.zero.impl.vm.precompiled.ExternalCapabilitiesForPrecompiled;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TotalCurrencyContractTest {
    private static final AionAddress ADDR = ContractInfo.TOTAL_CURRENCY.contractAddress;
    private static final long COST = 21000L;
    private static final BigInteger AMT = BigInteger.valueOf(1000);
    private TotalCurrencyContract tcc;
    private ECKey ownerKey;
    private ExternalStateForTests state;

    @BeforeClass
    public static void setupCapabilities() {
        CapabilitiesProvider.installExternalCapabilities(new ExternalCapabilitiesForPrecompiled());
    }

    @AfterClass
    public static void teardownCapabilities() {
        CapabilitiesProvider.removeExternalCapabilities();
    }

    @Before
    public void setup() {
        state = ExternalStateForTests.usingDefaultRepository();
        ownerKey = ECKeyFac.inst().create();
        tcc = new TotalCurrencyContract(state, ADDR, new AionAddress(ownerKey.getAddress()));
    }

    @After
    public void tearDown() {
        state = null;
        ownerKey = null;
        tcc = null;
    }

    /**
     * Constructs the input for an update request on the TotalCurrencyContract using AMT as the
     * value to update by and signs it using ownerKey.
     *
     * @param chainID The chain to update.
     * @param signum 0 for addition, 1 for subtraction.
     * @return the input byte array.
     */
    private byte[] constructUpdateInput(byte chainID, byte signum) {
        ByteBuffer buffer = ByteBuffer.allocate(18);
        buffer.put(chainID).put(signum).put(new DataWord(AMT.toByteArray()).getData());

        byte[] payload = buffer.array();
        buffer = ByteBuffer.allocate(18 + 96);

        return buffer.put(payload).put(ownerKey.sign(payload).toBytes()).array();
    }

    // <------------------------------------------------------------------------------------------->

    @Test
    public void TestGetTotalAmount() {
        System.out.println("Running TestGetTotalAmount.");

        byte[] payload = new byte[] {0}; // input == chainID
        PrecompiledTransactionResult res = tcc.execute(payload, COST);

        System.out.println("Contract result: " + res.toString());
        assertTrue(res.getStatus().isSuccess());
        assertEquals(0L, res.getEnergyRemaining());
    }

    @Test
    public void TestGetTotalAmountEmptyPayload() {
        System.out.println("Running TestGetTotalAmountEmptyPayload.");

        byte[] payload = new byte[0]; // zero size input
        PrecompiledTransactionResult res = tcc.execute(payload, COST);

        System.out.println("Contract result: " + res.toString());
        assertTrue(res.getStatus().isFailed());
    }

    @Test
    public void TestGetTotalAmountInsufficientNrg() {
        System.out.println("Running TestGetTotalAmountInsufficientNrg");

        byte[] payload = new byte[] {0};
        PrecompiledTransactionResult res = tcc.execute(payload, COST - 1);

        assertEquals("OUT_OF_NRG", res.getStatus().causeOfError);
        assertEquals(0, res.getEnergyRemaining());
    }

    @Test
    public void TestUpdateTotalAmount() {
        System.out.println("Running TestUpdateTotalAmount.");

        byte[] input = constructUpdateInput((byte) 0x0, (byte) 0x0);
        PrecompiledTransactionResult res = tcc.execute(input, COST);

        assertTrue(res.getStatus().isSuccess());
        assertEquals(0L, res.getEnergyRemaining());
    }

    @Test
    public void TestUpdateAndGetTotalAmount() {
        System.out.println("Running TestUpdateAndGetTotalAmount.");

        byte[] input = constructUpdateInput((byte) 0x0, (byte) 0x0);
        PrecompiledTransactionResult res = tcc.execute(input, COST);

        assertTrue(res.getStatus().isSuccess());
        assertEquals(0L, res.getEnergyRemaining());

        tcc = new TotalCurrencyContract(state, ADDR, new AionAddress(ownerKey.getAddress()));
        input = new byte[] {(byte) 0x0};
        res = tcc.execute(input, COST);

        assertTrue(res.getStatus().isSuccess());
        assertEquals(AMT, new BigInteger(1, res.getReturnData()));
    }

    @Test
    public void TestUpdateAndGetDiffChainIds() {
        System.out.println("Running TestUpdateAndGetDiffChainIds.");

        byte[] input = constructUpdateInput((byte) 0x0, (byte) 0x0);
        PrecompiledTransactionResult res = tcc.execute(input, COST);

        assertTrue(res.getStatus().isSuccess());
        assertEquals(0L, res.getEnergyRemaining());

        res = tcc.execute(new byte[] {(byte) 0x1}, COST); // query a diff chainID

        assertTrue(res.getStatus().isSuccess());
        assertEquals(BigInteger.ZERO, new BigInteger(1, res.getReturnData()));
    }

    @Test
    public void TestMultipleUpdates() {
        System.out.println("Running TestMultipleUpdates.");

        byte[] input = constructUpdateInput((byte) 0x0, (byte) 0x0);
        tcc.execute(input, COST);
        tcc.execute(input, COST);
        tcc.execute(input, COST);
        tcc.execute(input, COST);

        PrecompiledTransactionResult res = tcc.execute(new byte[] {(byte) 0x0}, COST);

        assertTrue(res.getStatus().isSuccess());
        assertEquals(AMT.multiply(BigInteger.valueOf(4)), new BigInteger(res.getReturnData()));
    }

    @Test
    public void TestGetTotalInsufficientNrg() {
        System.out.println("Running TestUpdateTotalInsufficientNrg.");

        PrecompiledTransactionResult res = tcc.execute(new byte[] {(byte) 0x0}, COST - 1);
        assertEquals("OUT_OF_NRG", res.getStatus().causeOfError);
        assertEquals(0L, res.getEnergyRemaining());
    }

    @Test
    public void TestUpdateTotalIncorrectSigSize() {
        System.out.println("Running TestUpdateTotalIncorrectSigSize.");

        byte[] input =
                Arrays.copyOfRange(
                        constructUpdateInput((byte) 0x0, (byte) 0x0), 0, 100); // cut sig short.
        PrecompiledTransactionResult res = tcc.execute(input, COST);

        assertEquals("FAILURE", res.getStatus().causeOfError);
        assertEquals(0L, res.getEnergyRemaining());
    }

    @Test
    public void TestUpdateTotalNotOwner() {
        System.out.println("Running TestUpdateTotalNotOwner.");
        TotalCurrencyContract contract =
                new TotalCurrencyContract(
                        state,
                        ADDR,
                        new AionAddress(ECKeyFac.inst().create().getAddress())); // diff owner.

        byte[] input = constructUpdateInput((byte) 0x0, (byte) 0x0);
        PrecompiledTransactionResult res = contract.execute(input, COST);

        assertEquals("FAILURE", res.getStatus().causeOfError);
        assertEquals(0L, res.getEnergyRemaining());
    }

    @Test
    public void TestUpdateTotalIncorrectSig() {
        System.out.println("Running TestUpdateTotalIncorrectSig.");

        byte[] input = constructUpdateInput((byte) 0x0, (byte) 0x0);
        input[30] = (byte) ~input[30]; // flip a bit

        PrecompiledTransactionResult res = tcc.execute(input, COST);
        assertEquals("FAILURE", res.getStatus().causeOfError);
        assertEquals(0L, res.getEnergyRemaining());
    }

    @Test
    public void TestSubtractTotalAmount() {
        System.out.println("Running TestSubtractTotalAmount.");

        // First give some positive balance to take away.
        byte[] input = constructUpdateInput((byte) 0x0, (byte) 0x0);
        tcc.execute(input, COST);
        tcc.execute(input, COST);
        tcc.execute(input, COST);

        // Remove the balance.
        input = constructUpdateInput((byte) 0x0, (byte) 0x1);
        tcc.execute(input, COST);

        PrecompiledTransactionResult res = tcc.execute(new byte[] {(byte) 0x0}, COST);
        assertTrue(res.getStatus().isSuccess());
        assertEquals(AMT.multiply(BigInteger.valueOf(2)), new BigInteger(res.getReturnData()));

        tcc.execute(input, COST);
        tcc.execute(input, COST);

        res = tcc.execute(new byte[] {(byte) 0x0}, COST);
        assertTrue(res.getStatus().isSuccess());
        assertEquals(BigInteger.ZERO, new BigInteger(res.getReturnData()));
    }

    @Test
    public void TestSubtractTotalAmountBelowZero() {
        System.out.println("Running TestSubtractTotalAmountBelowZero.");

        byte[] input = constructUpdateInput((byte) 0x0, (byte) 0x1); // 0x1 == subtract
        PrecompiledTransactionResult res = tcc.execute(input, COST);

        assertEquals("FAILURE", res.getStatus().causeOfError);
        assertEquals(0L, res.getEnergyRemaining());

        // Verify total amount is non-negative.
        res = tcc.execute(new byte[] {(byte) 0x0}, COST);
        assertEquals(BigInteger.ZERO, new BigInteger(1, res.getReturnData()));
    }

    @Test
    public void TestBadSignum() {
        System.out.println("Running TestBadSigum.");

        byte[] input = constructUpdateInput((byte) 0x0, (byte) 0x2); // only 0, 1 are valid.
        PrecompiledTransactionResult res = tcc.execute(input, COST);

        assertEquals("FAILURE", res.getStatus().causeOfError);
        assertEquals(0L, res.getEnergyRemaining());
    }

    @Test
    public void TestUpdateMultipleChains() {
        System.out.println("Running TestUpdateMultipleChains.");

        byte[] input0 = constructUpdateInput((byte) 0x0, (byte) 0x0);
        byte[] input1 = constructUpdateInput((byte) 0x1, (byte) 0x0);
        byte[] input2 = constructUpdateInput((byte) 0x10, (byte) 0x0);

        tcc.execute(input0, COST);
        tcc.execute(input1, COST);
        tcc.execute(input1, COST);
        tcc.execute(input2, COST);
        tcc.execute(input2, COST);
        tcc.execute(input2, COST);
        tcc.execute(input2, COST);

        PrecompiledTransactionResult res =
                tcc.execute(new byte[] {(byte) 0x0}, COST); // get chain 0.
        assertTrue(res.getStatus().isSuccess());
        assertEquals(AMT, new BigInteger(res.getReturnData()));

        res = tcc.execute(new byte[] {(byte) 0x1}, COST); // get chain 1.
        assertTrue(res.getStatus().isSuccess());
        assertEquals(AMT.multiply(BigInteger.valueOf(2)), new BigInteger(res.getReturnData()));

        res = tcc.execute((new byte[] {(byte) 0x10}), COST); // get chain 16.
        assertTrue(res.getStatus().isSuccess());
        assertEquals(AMT.multiply(BigInteger.valueOf(4)), new BigInteger(res.getReturnData()));
    }
}
