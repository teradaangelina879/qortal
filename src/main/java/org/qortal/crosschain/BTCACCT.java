package org.qortal.crosschain;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.function.Function;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.script.ScriptOpCodes;
import org.ciyam.at.API;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.qortal.utils.Base58;
import org.qortal.utils.BitTwiddling;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;

public class BTCACCT {

	public static final Coin DEFAULT_BTC_FEE = Coin.valueOf(1000L); // 0.00001000 BTC

	/*
	 * OP_TUCK (to copy public key to before signature)
	 * OP_CHECKSIGVERIFY (sig & pubkey must verify or script fails)
	 * OP_HASH160 (convert public key to PKH)
	 * OP_DUP (duplicate PKH)
	 * <push 20 bytes> <refund PKH> OP_EQUAL (does PKH match refund PKH?)
	 * OP_IF
	 * 	OP_DROP (no need for duplicate PKH)
	 * 	<push 4 bytes> <locktime>
	 * 	OP_CHECKLOCKTIMEVERIFY (if this passes, leftover stack is <locktime> so script passes)
	 * OP_ELSE
	 * 	<push 20 bytes> <redeem PKH> OP_EQUALVERIFY (duplicate PKH must match redeem PKH or script fails)
	 * 	OP_HASH160 (hash secret)
	 * 	<push 20 bytes> <hash of secret> OP_EQUAL (do hashes of secrets match? if true, script passes else script fails)
	 * OP_ENDIF
	 */

	private static final byte[] redeemScript1 = HashCode.fromString("7dada97614").asBytes(); // OP_TUCK OP_CHECKSIGVERIFY OP_HASH160 OP_DUP push(0x14 bytes)
	private static final byte[] redeemScript2 = HashCode.fromString("87637504").asBytes(); // OP_EQUAL OP_IF OP_DROP push(0x4 bytes)
	private static final byte[] redeemScript3 = HashCode.fromString("b16714").asBytes(); // OP_CHECKLOCKTIMEVERIFY OP_ELSE push(0x14 bytes)
	private static final byte[] redeemScript4 = HashCode.fromString("88a914").asBytes(); // OP_EQUALVERIFY OP_HASH160 push(0x14 bytes)
	private static final byte[] redeemScript5 = HashCode.fromString("8768").asBytes(); // OP_EQUAL OP_ENDIF

	/**
	 * Returns Bitcoin redeem script.
	 * <p>
	 * <pre>
	 * OP_TUCK OP_CHECKSIGVERIFY
	 * OP_HASH160 OP_DUP push(0x14) &lt;refunder pubkeyhash&gt; OP_EQUAL
	 * OP_IF
	 * 	OP_DROP push(0x04 bytes) &lt;refund locktime&gt; OP_CHECKLOCKTIMEVERIFY
	 * OP_ELSE
	 * 	push(0x14) &lt;redeemer pubkeyhash&gt; OP_EQUALVERIFY
	 * 	OP_HASH160 push(0x14 bytes) &lt;hash of secret&gt; OP_EQUAL
	 * OP_ENDIF
	 * </pre>
	 * 
	 * @param refunderPubKeyHash
	 * @param senderPubKey
	 * @param recipientPubKey
	 * @param lockTime
	 * @return
	 */
	public static byte[] buildScript(byte[] refunderPubKeyHash, int lockTime, byte[] redeemerPubKeyHash, byte[] secretHash) {
		return Bytes.concat(redeemScript1, refunderPubKeyHash, redeemScript2, BitTwiddling.toLEByteArray((int) (lockTime & 0xffffffffL)),
				redeemScript3, redeemerPubKeyHash, redeemScript4, secretHash, redeemScript5);
	}

	/**
	 * Builds a custom transaction to spend P2SH.
	 * 
	 * @param amount
	 * @param spendKey
	 * @param recipientPubKeyHash
	 * @param fundingOutput
	 * @param redeemScriptBytes
	 * @param lockTime
	 * @param scriptSigBuilder
	 * @return
	 */
	public static Transaction buildP2shTransaction(Coin amount, ECKey spendKey, TransactionOutput fundingOutput, byte[] redeemScriptBytes, Long lockTime, Function<byte[], Script> scriptSigBuilder) {
		NetworkParameters params = BTC.getInstance().getNetworkParameters();

		Transaction transaction = new Transaction(params);
		transaction.setVersion(2);

		// Output is back to P2SH funder
		transaction.addOutput(amount, ScriptBuilder.createP2PKHOutputScript(spendKey.getPubKeyHash()));

		// Input (without scriptSig prior to signing)
		TransactionInput input = new TransactionInput(params, null, redeemScriptBytes, fundingOutput.getOutPointFor());
		if (lockTime != null)
			input.setSequenceNumber(BTC.LOCKTIME_NO_RBF_SEQUENCE); // Use max-value, so no lockTime and no RBF
		else
			input.setSequenceNumber(BTC.NO_LOCKTIME_NO_RBF_SEQUENCE); // Use max-value - 1, so lockTime can be used but not RBF
		transaction.addInput(input);

		// Set locktime after inputs added but before input signatures are generated
		if (lockTime != null)
			transaction.setLockTime(lockTime);

		// Generate transaction signature for input
		final boolean anyoneCanPay = false;
		TransactionSignature txSig = transaction.calculateSignature(0, spendKey, redeemScriptBytes, SigHash.ALL, anyoneCanPay);

		// Calculate transaction signature
		byte[] txSigBytes = txSig.encodeToBitcoin();

		// Build scriptSig using lambda and tx signature
		Script scriptSig = scriptSigBuilder.apply(txSigBytes);

		// Set input scriptSig
		transaction.getInput(0).setScriptSig(scriptSig);

		return transaction;
	}

	public static Transaction buildRefundTransaction(Coin refundAmount, ECKey refundKey, TransactionOutput fundingOutput, byte[] redeemScriptBytes, long lockTime) {
		Function<byte[], Script> refundSigScriptBuilder = (txSigBytes) -> {
			// Build scriptSig with...
			ScriptBuilder scriptBuilder = new ScriptBuilder();

			// transaction signature
			scriptBuilder.addChunk(new ScriptChunk(txSigBytes.length, txSigBytes));

			// redeem public key
			byte[] refundPubKey = refundKey.getPubKey();
			scriptBuilder.addChunk(new ScriptChunk(refundPubKey.length, refundPubKey));

			// redeem script
			scriptBuilder.addChunk(new ScriptChunk(ScriptOpCodes.OP_PUSHDATA1, redeemScriptBytes));

			return scriptBuilder.build();
		};

		return buildP2shTransaction(refundAmount, refundKey, fundingOutput, redeemScriptBytes, lockTime, refundSigScriptBuilder);
	}

	public static Transaction buildRedeemTransaction(Coin redeemAmount, ECKey redeemKey, TransactionOutput fundingOutput, byte[] redeemScriptBytes, byte[] secret) {
		Function<byte[], Script> redeemSigScriptBuilder = (txSigBytes) -> {
			// Build scriptSig with...
			ScriptBuilder scriptBuilder = new ScriptBuilder();

			// secret
			scriptBuilder.addChunk(new ScriptChunk(secret.length, secret));

			// transaction signature
			scriptBuilder.addChunk(new ScriptChunk(txSigBytes.length, txSigBytes));

			// redeem public key
			byte[] redeemPubKey = redeemKey.getPubKey();
			scriptBuilder.addChunk(new ScriptChunk(redeemPubKey.length, redeemPubKey));

			// redeem script
			scriptBuilder.addChunk(new ScriptChunk(ScriptOpCodes.OP_PUSHDATA1, redeemScriptBytes));

			return scriptBuilder.build();
		};

		return buildP2shTransaction(redeemAmount, redeemKey, fundingOutput, redeemScriptBytes, null, redeemSigScriptBuilder);
	}

	public static byte[] buildQortalAT(byte[] secretHash, String recipientQortalAddress, long refundMinutes, BigDecimal initialPayout) {
		// Labels for data segment addresses
		int addrCounter = 0;
		// Constants (with corresponding dataByteBuffer.put*() calls below)
		final int addrHashPart1 = addrCounter++;
		final int addrHashPart2 = addrCounter++;
		final int addrHashPart3 = addrCounter++;
		final int addrHashPart4 = addrCounter++;
		final int addrAddressPart1 = addrCounter++;
		final int addrAddressPart2 = addrCounter++;
		final int addrAddressPart3 = addrCounter++;
		final int addrAddressPart4 = addrCounter++;
		final int addrRefundMinutes = addrCounter++;
		final int addrHashTempIndex = addrCounter++;
		final int addrHashTempLength = addrCounter++;
		final int addrInitialPayoutAmount = addrCounter++;
		final int addrExpectedTxType = addrCounter++;
		// Variables
		final int addrRefundTimestamp = addrCounter++;
		final int addrLastTimestamp = addrCounter++;
		final int addrBlockTimestamp = addrCounter++;
		final int addrTxType = addrCounter++;
		final int addrComparator = addrCounter++;
		final int addrAddressTemp1 = addrCounter++;
		final int addrAddressTemp2 = addrCounter++;
		final int addrAddressTemp3 = addrCounter++;
		final int addrAddressTemp4 = addrCounter++;
		final int addrHashTemp1 = addrCounter++;
		final int addrHashTemp2 = addrCounter++;
		final int addrHashTemp3 = addrCounter++;
		final int addrHashTemp4 = addrCounter++;

		// Data segment
		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);

		// Hash of secret into HashPart1-4
		assert dataByteBuffer.position() == addrHashPart1 * MachineState.VALUE_SIZE : "addrHashPart1 incorrect";
		dataByteBuffer.put(Bytes.ensureCapacity(secretHash, 32, 0));

		// Recipient Qortal address, decoded from Base58
		assert dataByteBuffer.position() == addrAddressPart1 * MachineState.VALUE_SIZE : "addrAddressPart1 incorrect";
		byte[] recipientAddressBytes = Base58.decode(recipientQortalAddress);
		dataByteBuffer.put(Bytes.ensureCapacity(recipientAddressBytes, 32, 0));

		// Expiry in minutes
		assert dataByteBuffer.position() == addrRefundMinutes * MachineState.VALUE_SIZE : "addrRefundMinutes incorrect";
		dataByteBuffer.putLong(refundMinutes);

		// Source location and length for hashing any passed secret
		assert dataByteBuffer.position() == addrHashTempIndex * MachineState.VALUE_SIZE : "addrHashTempIndex incorrect";
		dataByteBuffer.putLong(addrHashTemp1);
		assert dataByteBuffer.position() == addrHashTempLength * MachineState.VALUE_SIZE : "addrHashTempLength incorrect";
		dataByteBuffer.putLong(32L);

		// Initial payout amount
		assert dataByteBuffer.position() == addrInitialPayoutAmount * MachineState.VALUE_SIZE : "addrInitialPayoutAmount incorrect";
		dataByteBuffer.putLong(initialPayout.unscaledValue().longValue());

		// We're only interested in MESSAGE transactions
		assert dataByteBuffer.position() == addrExpectedTxType * MachineState.VALUE_SIZE : "addrExpectedTxType incorrect";
		dataByteBuffer.putLong(API.ATTransactionType.MESSAGE.value);

		// Code labels
		final int addrTxLoop = 0x0036;
		final int addrCheckTx = 0x004b;
		final int addrRefund = 0x00c6;
		final int addrEndOfCode = 0x00cd;

		int tempPC;
		ByteBuffer codeByteBuffer = ByteBuffer.allocate(addrEndOfCode * 1);

		/* Initialization */

		// Use AT creation 'timestamp' as starting point for finding transactions sent to AT
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_CREATION_TIMESTAMP.value).putInt(addrLastTimestamp);
		// Calculate refund 'timestamp' by adding minutes to above 'timestamp', then save into addrRefundTimestamp
		codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.value).putShort(FunctionCode.ADD_MINUTES_TO_TIMESTAMP.value).putInt(addrRefundTimestamp)
				.putInt(addrLastTimestamp).putInt(addrRefundMinutes);

		// Load recipient's address into B register
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B_IND.value).putInt(addrAddressPart1);
		// Send initial payment to recipient so they have enough funds to message AT if all goes well
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.PAY_TO_ADDRESS_IN_B.value).putInt(addrInitialPayoutAmount);

		// Set restart position to after this opcode
		codeByteBuffer.put(OpCode.SET_PCS.value);

		/* Main loop */

		// Fetch current block 'timestamp'
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_BLOCK_TIMESTAMP.value).putInt(addrBlockTimestamp);
		// If we're past refund 'timestamp' then go refund everything back to AT creator
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BGE_DAT.value).putInt(addrBlockTimestamp).putInt(addrRefundTimestamp).put((byte) (addrRefund - tempPC));

		/* Transaction processing loop */
		assert codeByteBuffer.position() == addrTxLoop : "addrTxLoop incorrect";

		// Find next transaction to this AT since the last one (if any)
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A.value).putInt(addrLastTimestamp);
		// If no transaction found, A will be zero. If A is zero, set addrComparator to 1, otherwise 0.
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.CHECK_A_IS_ZERO.value).putInt(addrComparator);
		// If addrComparator is zero (i.e. A is non-zero, transaction was found) then branch to addrCheckTx
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BZR_DAT.value).putInt(addrComparator).put((byte) (addrCheckTx - tempPC));
		// Stop and wait for next block
		codeByteBuffer.put(OpCode.STP_IMD.value);

		/* Check transaction */
		assert codeByteBuffer.position() == addrCheckTx : "addrCheckTx incorrect";

		// Update our 'last found transaction's timestamp' using 'timestamp' from transaction
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A.value).putInt(addrLastTimestamp);
		// Extract transaction type (message/payment) from transaction and save type in addrTxType
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_TYPE_FROM_TX_IN_A.value).putInt(addrTxType);
		// If transaction type is not MESSAGE type then go look for another transaction
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNE_DAT.value).putInt(addrTxType).putInt(addrExpectedTxType).put((byte) (addrTxLoop - tempPC));

		/* Check transaction's sender */

		// Extract sender address from transaction into B register
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B.value);
		// Save B register into data segment starting at addrAddressTemp1
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_B_IND.value).putInt(addrAddressTemp1);
		// Compare each part of transaction's sender's address with expected address. If they don't match, look for another transaction.
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNE_DAT.value).putInt(addrAddressTemp1).putInt(addrAddressPart1).put((byte) (addrTxLoop - tempPC));
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNE_DAT.value).putInt(addrAddressTemp2).putInt(addrAddressPart2).put((byte) (addrTxLoop - tempPC));
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNE_DAT.value).putInt(addrAddressTemp3).putInt(addrAddressPart3).put((byte) (addrTxLoop - tempPC));
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNE_DAT.value).putInt(addrAddressTemp4).putInt(addrAddressPart4).put((byte) (addrTxLoop - tempPC));

		/* Check 'secret' in transaction's message */

		// Extract message from transaction into B register
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B.value);
		// Save B register into data segment starting at addrHashTemp1
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_B_IND.value).putInt(addrHashTemp1);
		// Load B register with expected hash result
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B_IND.value).putInt(addrHashPart1);
		// Perform HASH160 using source data at addrHashTemp1 through addrHashTemp4. (Location and length specified via addrHashTempIndex and addrHashTemplength).
		// Save the equality result (1 if they match, 0 otherwise) into addrComparator.
		codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.value).putShort(FunctionCode.CHECK_HASH160_WITH_B.value).putInt(addrComparator).putInt(addrHashTempIndex).putInt(addrHashTempLength);
		// If hashes don't match, addrComparator will be zero so go find another transaction
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BZR_DAT.value).putInt(addrComparator).put((byte) (addrTxLoop - tempPC));

		/* Success! Pay balance to intended recipient */

		// Load B register with intended recipient address.
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B_IND.value).putInt(addrAddressPart1);
		// Pay AT's balance to recipient
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.PAY_ALL_TO_ADDRESS_IN_B.value);
		// We're finished forever
		codeByteBuffer.put(OpCode.FIN_IMD.value);

		/* Refund balance back to AT creator */
		assert codeByteBuffer.position() == addrRefund : "addrRefund incorrect";

		// Load B register with AT creator's address.
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.PUT_CREATOR_INTO_B.value);
		// Pay AT's balance back to AT's creator.
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.PAY_ALL_TO_ADDRESS_IN_B.value);
		// We're finished forever
		codeByteBuffer.put(OpCode.FIN_IMD.value);

		// end-of-code
		assert codeByteBuffer.position() == addrEndOfCode : "addrEndOfCode incorrect";

		final short ciyamAtVersion = 2;
		final short numCallStackPages = 0;
		final short numUserStackPages = 0;
		final long minActivationAmount = 0L;

		return MachineState.toCreationBytes(ciyamAtVersion, codeByteBuffer.array(), dataByteBuffer.array(), numCallStackPages, numUserStackPages, minActivationAmount);
	}

}
