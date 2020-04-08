package org.qora.crosschain;

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
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.qora.utils.BitTwiddling;

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

	public static byte[] buildQortalAT(byte[] secretHash, String destinationQortalAddress, long refundMinutes, BigDecimal initialPayout) {
		// Labels for data segment addresses
		int addrCounter = 0;
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
		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * 8);

		// Hash of secret into HashPart1-4
		dataByteBuffer.put(secretHash);

		// Destination Qortal account's public key
		dataByteBuffer.put(Bytes.ensureCapacity(destinationQortalAddress.getBytes(), 32, 0));

		// Expiry in minutes
		dataByteBuffer.putLong(refundMinutes);

		// Temp buffer for hashing any passed secret
		dataByteBuffer.putLong(addrHashTemp1);
		dataByteBuffer.putLong(32L);
		
		// Initial payout amount
		dataByteBuffer.putLong(initialPayout.unscaledValue().longValue());

		// Code labels
		final int addrTxLoop = 0x36;
		final int addrCheckTx = 0x4b;
		final int addrCheckSender = 0x64;
		final int addrCheckMessage = 0xab;
		final int addrPayout = 0xdf;
		final int addrRefund = 0x102;
		final int addrEndOfCode = 0x109;

		int tempPC;
		ByteBuffer codeByteBuffer = ByteBuffer.allocate(addrEndOfCode * 1);

		// init:
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_CREATION_TIMESTAMP.value).putInt(addrRefundTimestamp);
		codeByteBuffer.put(OpCode.SET_DAT.value).putInt(addrLastTimestamp).putInt(addrRefundTimestamp);
		codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.value).putShort(FunctionCode.ADD_MINUTES_TO_TIMESTAMP.value).putInt(addrRefundTimestamp)
				.putInt(addrRefundTimestamp).putInt(addrRefundMinutes);

		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B_IND.value).putInt(addrAddressPart1);
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.PAY_TO_ADDRESS_IN_B.value).putInt(addrInitialPayoutAmount);

		codeByteBuffer.put(OpCode.SET_PCS.value);

		// loop:
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_BLOCK_TIMESTAMP.value).putInt(addrBlockTimestamp);
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BLT_DAT.value).putInt(addrBlockTimestamp).putInt(addrRefundTimestamp).put((byte) (addrTxLoop - tempPC));
		codeByteBuffer.put(OpCode.JMP_ADR.value).putInt(addrRefund);

		// txloop:
		assert codeByteBuffer.position() == addrTxLoop : "addrTxLoop incorrect";
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A.value).putInt(addrLastTimestamp);
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.CHECK_A_IS_ZERO.value).putInt(addrComparator);
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BZR_DAT.value).putInt(addrComparator).put((byte) (addrCheckTx - tempPC));
		codeByteBuffer.put(OpCode.STP_IMD.value);

		// checkTx:
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A.value).putInt(addrLastTimestamp);
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_TYPE_FROM_TX_IN_A.value).putInt(addrTxType);
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNZ_DAT.value).putInt(addrTxType).put((byte) (addrCheckSender - tempPC));
		codeByteBuffer.put(OpCode.JMP_ADR.value).putInt(addrTxLoop);

		// checkSender
		assert codeByteBuffer.position() == addrCheckSender : "addrCheckSender incorrect";
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B.value);
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_B_IND.value).putInt(addrAddressTemp1);
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNE_DAT.value).putInt(addrAddressTemp1).putInt(addrAddressPart1).put((byte) (addrTxLoop - tempPC));
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNE_DAT.value).putInt(addrAddressTemp2).putInt(addrAddressPart2).put((byte) (addrTxLoop - tempPC));
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNE_DAT.value).putInt(addrAddressTemp3).putInt(addrAddressPart3).put((byte) (addrTxLoop - tempPC));
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNE_DAT.value).putInt(addrAddressTemp4).putInt(addrAddressPart4).put((byte) (addrTxLoop - tempPC));

		// checkMessage:
		assert codeByteBuffer.position() == addrCheckMessage : "addrCheckMessage incorrect";
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B.value);
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_B_IND.value).putInt(addrHashTemp1);
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B_IND.value).putInt(addrHashPart1);
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.CHECK_HASH160_WITH_B.value).putInt(addrHashTempIndex).putInt(addrHashTempLength);
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNZ_DAT.value).putInt(addrComparator).put((byte) (addrPayout - tempPC));
		codeByteBuffer.put(OpCode.JMP_ADR.value).putInt(addrTxLoop);

		// payout:
		assert codeByteBuffer.position() == addrPayout : "addrPayout incorrect";
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B_IND.value).putInt(addrAddressPart1);
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.PAY_ALL_TO_ADDRESS_IN_B.value);
		codeByteBuffer.put(OpCode.FIN_IMD.value);

		// refund:
		assert codeByteBuffer.position() == addrRefund : "addrRefund incorrect";
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.PUT_CREATOR_INTO_B.value);
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.PAY_ALL_TO_ADDRESS_IN_B.value);
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
