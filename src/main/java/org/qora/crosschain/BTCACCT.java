package org.qora.crosschain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.script.ScriptOpCodes;
import org.bitcoinj.script.Script.ScriptType;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.qora.utils.BitTwiddling;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;

public class BTCACCT {

	public static final Coin DEFAULT_BTC_FEE = Coin.valueOf(1000L); // 0.00001000 BTC

	private static final byte[] redeemScript1 = HashCode.fromString("76a820").asBytes(); // OP_DUP OP_SHA256 push(0x20 bytes)
	private static final byte[] redeemScript2 = HashCode.fromString("87637576a914").asBytes(); // OP_EQUAL OP_IF OP_DROP OP_DUP OP_HASH160 push(0x14 bytes)
	private static final byte[] redeemScript3 = HashCode.fromString("88ac6704").asBytes(); // OP_EQUALVERIFY OP_CHECKSIG OP_ELSE push(0x4 bytes)
	private static final byte[] redeemScript4 = HashCode.fromString("b17576a914").asBytes(); // OP_CHECKLOCKTIMEVERIFY OP_DROP OP_DUP OP_HASH160 push(0x14 bytes)
	private static final byte[] redeemScript5 = HashCode.fromString("88ac68").asBytes(); // OP_EQUALVERIFY OP_CHECKSIG OP_ENDIF

	/**
	 * Returns Bitcoin redeem script.
	 * <p>
	 * <pre>
	 * OP_DUP OP_SHA256 push(0x20) &lt;SHA256 of secret&gt; OP_EQUAL
	 * OP_IF
	 * 	OP_DROP OP_DUP OP_HASH160 push(0x14) &lt;HASH160 of recipient pubkey&gt;
	 *	OP_EQUALVERIFY OP_CHECKSIG
	 * OP_ELSE
	 * 	push(0x04) &lt;refund locktime&gt; OP_CHECKLOCKTIMEVERIFY
	 *	OP_DROP OP_DUP OP_HASH160 push(0x14) &lt;HASH160 of sender pubkey&gt;
	 *	OP_EQUALVERIFY OP_CHECKSIG
	 * OP_ENDIF
	 * </pre>
	 * 
	 * @param secretHash
	 * @param senderPubKey
	 * @param recipientPubKey
	 * @param lockTime
	 * @return
	 */
	public static byte[] buildRedeemScript(byte[] secretHash, byte[] senderPubKey, byte[] recipientPubKey, int lockTime) {
		byte[] senderPubKeyHash160 = BTC.hash160(senderPubKey);
		byte[] recipientPubKeyHash160 = BTC.hash160(recipientPubKey);

		return Bytes.concat(redeemScript1, secretHash, redeemScript2, recipientPubKeyHash160, redeemScript3, BitTwiddling.toLEByteArray((int) (lockTime & 0xffffffffL)),
				redeemScript4, senderPubKeyHash160, redeemScript5);
	}

	public static Transaction buildRefundTransaction(Coin refundAmount, ECKey senderKey, TransactionOutput fundingOutput, byte[] redeemScriptBytes, long lockTime) {
		NetworkParameters params = BTC.getInstance().getNetworkParameters();

		Transaction refundTransaction = new Transaction(params);
		refundTransaction.setVersion(2);

		refundAmount = refundAmount.subtract(DEFAULT_BTC_FEE);

		// Output is back to P2SH funder
		refundTransaction.addOutput(refundAmount, ScriptBuilder.createOutputScript(Address.fromKey(params, senderKey, ScriptType.P2PKH)));

		// Input (without scriptSig prior to signing)
		TransactionInput input = new TransactionInput(params, null, new byte[0], fundingOutput.getOutPointFor());
		input.setSequenceNumber(0); // Use 0, not max-value, so lockTime can be used
		refundTransaction.addInput(input);

		// Set locktime after inputs added but before input signatures are generated
		refundTransaction.setLockTime(lockTime);

		// Generate transaction signature for input
		final boolean anyoneCanPay = false;
		TransactionSignature txSig = refundTransaction.calculateSignature(0, senderKey, redeemScriptBytes, SigHash.ALL, anyoneCanPay);

		// Build scriptSig with...
		ScriptBuilder scriptBuilder = new ScriptBuilder();

		// transaction signature
		byte[] txSigBytes = txSig.encodeToBitcoin();
		scriptBuilder.addChunk(new ScriptChunk(txSigBytes.length, txSigBytes));

		// sender's public key
		byte[] senderPubKey = senderKey.getPubKey();
		scriptBuilder.addChunk(new ScriptChunk(senderPubKey.length, senderPubKey));

		/// redeem script
		scriptBuilder.addChunk(new ScriptChunk(ScriptOpCodes.OP_PUSHDATA1, redeemScriptBytes));

		refundTransaction.getInput(0).setScriptSig(scriptBuilder.build());

		return refundTransaction;
	}

	public static byte[] buildCiyamAT(byte[] secretHash, byte[] destinationQortalPubKey, long refundMinutes) {
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
		final int addrRefundTimestamp = addrCounter++;
		final int addrLastTimestamp = addrCounter++;
		final int addrBlockTimestamp = addrCounter++;
		final int addrTxType = addrCounter++;
		final int addrComparator = addrCounter++;
		final int addrAddressTemp1 = addrCounter++;
		final int addrAddressTemp2 = addrCounter++;
		final int addrAddressTemp3 = addrCounter++;
		final int addrAddressTemp4 = addrCounter++;

		// Data segment
		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * 8).order(ByteOrder.LITTLE_ENDIAN);

		// Hash of secret into HashPart1-4
		dataByteBuffer.put(secretHash);

		// Destination Qortal account's public key
		dataByteBuffer.put(destinationQortalPubKey);

		// Expiry in minutes
		dataByteBuffer.putLong(refundMinutes);

		// Code labels
		final int addrTxLoop = 0x36;
		final int addrCheckTx = 0x4b;
		final int addrCheckSender = 0x64;
		final int addrCheckMessage = 0xab;
		final int addrPayout = 0xdf;
		final int addrRefund = 0x102;
		final int addrEndOfCode = 0x109;

		int tempPC;
		ByteBuffer codeByteBuffer = ByteBuffer.allocate(addrEndOfCode * 1).order(ByteOrder.LITTLE_ENDIAN);

		// init:
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_CREATION_TIMESTAMP.value).putInt(addrRefundTimestamp);
		codeByteBuffer.put(OpCode.SET_DAT.value).putInt(addrLastTimestamp).putInt(addrRefundTimestamp);
		codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.value).putShort(FunctionCode.ADD_MINUTES_TO_TIMESTAMP.value).putInt(addrRefundTimestamp)
				.putInt(addrRefundTimestamp).putInt(addrRefundMinutes);
		codeByteBuffer.put(OpCode.SET_PCS.value);

		// loop:
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_BLOCK_TIMESTAMP.value).putInt(addrBlockTimestamp);
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BLT_DAT.value).putInt(addrBlockTimestamp).putInt(addrRefundTimestamp).put((byte) (addrTxLoop - tempPC));
		codeByteBuffer.put(OpCode.JMP_ADR.value).putInt(addrRefund);

		// txloop:
		assert codeByteBuffer.position() == addrTxLoop : "addrTxLoop incorrect";
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.PUT_TX_AFTER_TIMESTAMP_IN_A.value).putInt(addrLastTimestamp);
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
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_B1.value).putInt(addrAddressTemp1);
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_B2.value).putInt(addrAddressTemp2);
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_B3.value).putInt(addrAddressTemp3);
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.GET_B4.value).putInt(addrAddressTemp4);
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
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.SWAP_A_AND_B.value);
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B1.value).putInt(addrHashPart1);
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B2.value).putInt(addrHashPart2);
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B3.value).putInt(addrHashPart3);
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B4.value).putInt(addrHashPart4);
		codeByteBuffer.put(OpCode.EXT_FUN_RET.value).putShort(FunctionCode.CHECK_SHA256_A_WITH_B.value).putInt(addrComparator);
		tempPC = codeByteBuffer.position();
		codeByteBuffer.put(OpCode.BNZ_DAT.value).putInt(addrComparator).put((byte) (addrPayout - tempPC));
		codeByteBuffer.put(OpCode.JMP_ADR.value).putInt(addrTxLoop);

		// payout:
		assert codeByteBuffer.position() == addrPayout : "addrPayout incorrect";
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B1.value).putInt(addrAddressPart1);
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B2.value).putInt(addrAddressPart2);
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B3.value).putInt(addrAddressPart3);
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.value).putShort(FunctionCode.SET_B4.value).putInt(addrAddressPart4);
		codeByteBuffer.put(OpCode.EXT_FUN.value).putShort(FunctionCode.MESSAGE_A_TO_ADDRESS_IN_B.value);
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
