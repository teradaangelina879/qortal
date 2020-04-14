package org.qortal.crosschain;

import static org.ciyam.at.OpCode.calcOffset;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Arrays;
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
import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.qortal.account.Account;
import org.qortal.utils.Base58;
import org.qortal.utils.BitTwiddling;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;

public class BTCACCT {

	public static final Coin DEFAULT_BTC_FEE = Coin.valueOf(1000L); // 0.00001000 BTC
	public static final byte[] CODE_BYTES_HASH = HashCode.fromString("750012c7ae79d85a97e64e94c467c7791dd76cf3050b864f3166635a21d767c6").asBytes(); // SHA256 of AT code bytes

	public static class AtConstants {
		public final byte[] secretHash;
		public final BigDecimal initialPayout;
		public final BigDecimal redeemPayout;
		public final String recipient;
		public final int refundMinutes;

		public AtConstants(byte[] secretHash, BigDecimal initialPayout, BigDecimal redeemPayout, String recipient, int refundMinutes) {
			this.secretHash = secretHash;
			this.initialPayout = initialPayout;
			this.redeemPayout = redeemPayout;
			this.recipient = recipient;
			this.refundMinutes = refundMinutes;
		}
	}

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

	@SuppressWarnings("unused")
	public static byte[] buildQortalAT(byte[] secretHash, String recipientQortalAddress, int refundMinutes, BigDecimal initialPayout, BigDecimal redeemPayout) {
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
		final int addrInitialPayoutAmount = addrCounter++;
		final int addrRedeemPayoutAmount = addrCounter++;
		final int addrExpectedTxType = addrCounter++;
		final int addrHashIndex = addrCounter++;
		final int addrAddressIndex = addrCounter++;
		final int addrAddressTempIndex = addrCounter++;
		final int addrHashTempIndex = addrCounter++;
		final int addrHashTempLength = addrCounter++;
		final int addrEndOfConstants = addrCounter;
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

		// Initial payout amount
		assert dataByteBuffer.position() == addrInitialPayoutAmount * MachineState.VALUE_SIZE : "addrInitialPayoutAmount incorrect";
		dataByteBuffer.putLong(initialPayout.unscaledValue().longValue());

		// Redeem payout amount
		assert dataByteBuffer.position() == addrRedeemPayoutAmount * MachineState.VALUE_SIZE : "addrRedeemPayoutAmount incorrect";
		dataByteBuffer.putLong(redeemPayout.unscaledValue().longValue());

		// We're only interested in MESSAGE transactions
		assert dataByteBuffer.position() == addrExpectedTxType * MachineState.VALUE_SIZE : "addrExpectedTxType incorrect";
		dataByteBuffer.putLong(API.ATTransactionType.MESSAGE.value);

		// Index into data segment of hash, used by GET_B_IND
		assert dataByteBuffer.position() == addrHashIndex * MachineState.VALUE_SIZE : "addrHashIndex incorrect";
		dataByteBuffer.putLong(addrHashPart1);

		// Index into data segment of recipient address, used by SET_B_IND
		assert dataByteBuffer.position() == addrAddressIndex * MachineState.VALUE_SIZE : "addrAddressIndex incorrect";
		dataByteBuffer.putLong(addrAddressPart1);

		// Index into data segment of (temporary) transaction's sender's address, used by GET_B_IND
		assert dataByteBuffer.position() == addrAddressTempIndex * MachineState.VALUE_SIZE : "addrAddressTempIndex incorrect";
		dataByteBuffer.putLong(addrAddressTemp1);

		// Source location and length for hashing any passed secret
		assert dataByteBuffer.position() == addrHashTempIndex * MachineState.VALUE_SIZE : "addrHashTempIndex incorrect";
		dataByteBuffer.putLong(addrHashTemp1);
		assert dataByteBuffer.position() == addrHashTempLength * MachineState.VALUE_SIZE : "addrHashTempLength incorrect";
		dataByteBuffer.putLong(32L);

		assert dataByteBuffer.position() == addrEndOfConstants * MachineState.VALUE_SIZE : "dataByteBuffer position not at end of constants";

		// Code labels
		Integer labelTxLoop = null;
		Integer labelRefund = null;
		Integer labelCheckTx = null;

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);

		// Two-pass version
		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				/* Initialization */

				// Use AT creation 'timestamp' as starting point for finding transactions sent to AT
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTimestamp));
				// Calculate refund 'timestamp' by adding minutes to above 'timestamp', then save into addrRefundTimestamp
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.ADD_MINUTES_TO_TIMESTAMP, addrRefundTimestamp, addrLastTimestamp, addrRefundMinutes));

				// Load recipient's address into B register
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrAddressIndex));
				// Send initial payment to recipient so they have enough funds to message AT if all goes well
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrInitialPayoutAmount));

				// Set restart position to after this opcode
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				/* Main loop */

				// Fetch current block 'timestamp'
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_BLOCK_TIMESTAMP, addrBlockTimestamp));
				// If we're not past refund 'timestamp' then look for next transaction
				codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBlockTimestamp, addrRefundTimestamp, calcOffset(codeByteBuffer, labelTxLoop)));
				// We're past refund 'timestamp' so go refund everything back to AT creator
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRefund == null ? 0 : labelRefund));

				/* Transaction processing loop */
				labelTxLoop = codeByteBuffer.position();

				// Find next transaction to this AT since the last one (if any)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTimestamp));
				// If no transaction found, A will be zero. If A is zero, set addrComparator to 1, otherwise 0.
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrComparator));
				// If addrComparator is zero (i.e. A is non-zero, transaction was found) then go check transaction
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrComparator, calcOffset(codeByteBuffer, labelCheckTx)));
				// Stop and wait for next block
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				/* Check transaction */
				labelCheckTx = codeByteBuffer.position();

				// Update our 'last found transaction's timestamp' using 'timestamp' from transaction
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, addrLastTimestamp));
				// Extract transaction type (message/payment) from transaction and save type in addrTxType
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, addrTxType));
				// If transaction type is not MESSAGE type then go look for another transaction
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrTxType, addrExpectedTxType, calcOffset(codeByteBuffer, labelTxLoop)));

				/* Check transaction's sender */

				// Extract sender address from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrAddressTemp1 (as pointed to by addrAddressTempIndex)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrAddressTempIndex));
				// Compare each part of transaction's sender's address with expected address. If they don't match, look for another transaction.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrAddressTemp1, addrAddressPart1, calcOffset(codeByteBuffer, labelTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrAddressTemp2, addrAddressPart2, calcOffset(codeByteBuffer, labelTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrAddressTemp3, addrAddressPart3, calcOffset(codeByteBuffer, labelTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrAddressTemp4, addrAddressPart4, calcOffset(codeByteBuffer, labelTxLoop)));

				/* Check 'secret' in transaction's message */

				// Extract message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrHashTemp1 (as pointed to by addrHashTempIndex)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrHashTempIndex));
				// Load B register with expected hash result (as pointed to by addrHashIndex)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrHashIndex));
				// Perform HASH160 using source data at addrHashTemp1 through addrHashTemp4. (Location and length specified via addrHashTempIndex and addrHashTemplength).
				// Save the equality result (1 if they match, 0 otherwise) into addrComparator.
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.CHECK_HASH160_WITH_B, addrComparator, addrHashTempIndex, addrHashTempLength));
				// If hashes don't match, addrComparator will be zero so go find another transaction
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrComparator, calcOffset(codeByteBuffer, labelTxLoop)));

				/* Success! Pay arranged amount to intended recipient */

				// Load B register with intended recipient address (as pointed to by addrAddressIndex)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrAddressIndex));
				// Pay AT's balance to recipient
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrRedeemPayoutAmount));
				// We're finished forever
				codeByteBuffer.put(OpCode.FIN_IMD.compile());

				/* Refund balance back to AT creator */
				labelRefund = codeByteBuffer.position();

				// Load B register with AT creator's address.
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_CREATOR_INTO_B));
				// Pay AT's balance back to AT's creator.
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PAY_ALL_TO_ADDRESS_IN_B));
				// We're finished forever
				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile BTC-QORT ACCT?", e);
			}
		}

		codeByteBuffer.flip();

		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);

		final short ciyamAtVersion = 2;
		final short numCallStackPages = 0;
		final short numUserStackPages = 0;
		final long minActivationAmount = 0L;

		return MachineState.toCreationBytes(ciyamAtVersion, codeBytes, dataByteBuffer.array(), numCallStackPages, numUserStackPages, minActivationAmount);
	}

	public static AtConstants extractAtConstants(byte[] dataBytes) {
		ByteBuffer dataByteBuffer = ByteBuffer.wrap(dataBytes);

		byte[] secretHash = new byte[32];
		dataByteBuffer.get(secretHash);

		byte[] addressBytes = new byte[32];
		dataByteBuffer.get(addressBytes);
		String recipient = Base58.encode(Arrays.copyOf(addressBytes, Account.ADDRESS_LENGTH));

		int refundMinutes = (int) dataByteBuffer.getLong();

		BigDecimal initialPayout = BigDecimal.valueOf(dataByteBuffer.getLong(), 8);

		BigDecimal redeemPayout = BigDecimal.valueOf(dataByteBuffer.getLong(), 8);

		return new AtConstants(secretHash, initialPayout, redeemPayout, recipient, refundMinutes);
	}

}
