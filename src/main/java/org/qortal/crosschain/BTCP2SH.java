package org.qortal.crosschain;

import java.util.List;
import java.util.function.Function;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
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
import org.qortal.crypto.Crypto;
import org.qortal.utils.BitTwiddling;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;

public class BTCP2SH {

	public static final int SECRET_LENGTH = 32;
	public static final int MIN_LOCKTIME = 1500000000;

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
	 * Returns Bitcoin redeemScript used for cross-chain trading.
	 * <p>
	 * See comments in {@link BTCP2SH} for more details.
	 * 
	 * @param refunderPubKeyHash 20-byte HASH160 of P2SH funder's public key, for refunding purposes
	 * @param lockTime seconds-since-epoch threshold, after which P2SH funder can claim refund
	 * @param redeemerPubKeyHash 20-byte HASH160 of P2SH redeemer's public key
	 * @param secretHash 20-byte HASH160 of secret, used by P2SH redeemer to claim funds
	 * @return
	 */
	public static byte[] buildScript(byte[] refunderPubKeyHash, int lockTime, byte[] redeemerPubKeyHash, byte[] secretHash) {
		return Bytes.concat(redeemScript1, refunderPubKeyHash, redeemScript2, BitTwiddling.toLEByteArray((int) (lockTime & 0xffffffffL)),
				redeemScript3, redeemerPubKeyHash, redeemScript4, secretHash, redeemScript5);
	}

	/**
	 * Builds a custom transaction to spend P2SH.
	 * 
	 * @param amount output amount, should be total of input amounts, less miner fees
	 * @param spendKey key for signing transaction, and also where funds are 'sent' (output)
	 * @param fundingOutput output from transaction that funded P2SH address
	 * @param redeemScriptBytes the redeemScript itself, in byte[] form
	 * @param lockTime (optional) transaction nLockTime, used in refund scenario
	 * @param scriptSigBuilder function for building scriptSig using transaction input signature
	 * @return Signed Bitcoin transaction for spending P2SH
	 */
	public static Transaction buildP2shTransaction(Coin amount, ECKey spendKey, List<TransactionOutput> fundingOutputs, byte[] redeemScriptBytes, Long lockTime, Function<byte[], Script> scriptSigBuilder) {
		NetworkParameters params = BTC.getInstance().getNetworkParameters();

		Transaction transaction = new Transaction(params);
		transaction.setVersion(2);

		// Output is back to P2SH funder
		transaction.addOutput(amount, ScriptBuilder.createP2PKHOutputScript(spendKey.getPubKeyHash()));

		for (int inputIndex = 0; inputIndex < fundingOutputs.size(); ++inputIndex) {
			TransactionOutput fundingOutput = fundingOutputs.get(inputIndex);

			// Input (without scriptSig prior to signing)
			TransactionInput input = new TransactionInput(params, null, redeemScriptBytes, fundingOutput.getOutPointFor());
			if (lockTime != null)
				input.setSequenceNumber(BTC.LOCKTIME_NO_RBF_SEQUENCE); // Use max-value, so no lockTime and no RBF
			else
				input.setSequenceNumber(BTC.NO_LOCKTIME_NO_RBF_SEQUENCE); // Use max-value - 1, so lockTime can be used but not RBF
			transaction.addInput(input);
		}

		// Set locktime after inputs added but before input signatures are generated
		if (lockTime != null)
			transaction.setLockTime(lockTime);

		for (int inputIndex = 0; inputIndex < fundingOutputs.size(); ++inputIndex) {
			// Generate transaction signature for input
			final boolean anyoneCanPay = false;
			TransactionSignature txSig = transaction.calculateSignature(inputIndex, spendKey, redeemScriptBytes, SigHash.ALL, anyoneCanPay);

			// Calculate transaction signature
			byte[] txSigBytes = txSig.encodeToBitcoin();

			// Build scriptSig using lambda and tx signature
			Script scriptSig = scriptSigBuilder.apply(txSigBytes);

			// Set input scriptSig
			transaction.getInput(inputIndex).setScriptSig(scriptSig);
		}

		return transaction;
	}

	/**
	 * Returns signed Bitcoin transaction claiming refund from P2SH address.
	 * 
	 * @param refundAmount refund amount, should be total of input amounts, less miner fees
	 * @param refundKey key for signing transaction, and also where refund is 'sent' (output)
	 * @param fundingOutput output from transaction that funded P2SH address
	 * @param redeemScriptBytes the redeemScript itself, in byte[] form
	 * @param lockTime transaction nLockTime - must be at least locktime used in redeemScript
	 * @return Signed Bitcoin transaction for refunding P2SH
	 */
	public static Transaction buildRefundTransaction(Coin refundAmount, ECKey refundKey, List<TransactionOutput> fundingOutputs, byte[] redeemScriptBytes, long lockTime) {
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

		return buildP2shTransaction(refundAmount, refundKey, fundingOutputs, redeemScriptBytes, lockTime, refundSigScriptBuilder);
	}

	/**
	 * Returns signed Bitcoin transaction redeeming funds from P2SH address.
	 * 
	 * @param redeemAmount redeem amount, should be total of input amounts, less miner fees
	 * @param redeemKey key for signing transaction, and also where funds are 'sent' (output)
	 * @param fundingOutput output from transaction that funded P2SH address
	 * @param redeemScriptBytes the redeemScript itself, in byte[] form
	 * @param secret actual 32-byte secret used when building redeemScript
	 * @return Signed Bitcoin transaction for redeeming P2SH
	 */
	public static Transaction buildRedeemTransaction(Coin redeemAmount, ECKey redeemKey, List<TransactionOutput> fundingOutputs, byte[] redeemScriptBytes, byte[] secret) {
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

		return buildP2shTransaction(redeemAmount, redeemKey, fundingOutputs, redeemScriptBytes, null, redeemSigScriptBuilder);
	}

	/** Returns 'secret', if any, given list of raw bitcoin transactions. */
	public static byte[] findP2shSecret(String p2shAddress, List<byte[]> rawTransactions) {
		NetworkParameters params = BTC.getInstance().getNetworkParameters();

		for (byte[] rawTransaction : rawTransactions) {
			Transaction transaction = new Transaction(params, rawTransaction);

			// Cycle through inputs, looking for one that spends our P2SH
			for (TransactionInput input : transaction.getInputs()) {
				Script scriptSig = input.getScriptSig();
				List<ScriptChunk> scriptChunks = scriptSig.getChunks();

				// Expected number of script chunks for redeem. Refund might not have the same number.
				int expectedChunkCount = 1 /*secret*/ + 1 /*sig*/ + 1 /*pubkey*/ + 1 /*redeemScript*/;
				if (scriptChunks.size() != expectedChunkCount)
					continue;

				// We're expecting last chunk to contain the actual redeemScript
				ScriptChunk lastChunk = scriptChunks.get(scriptChunks.size() - 1);
				byte[] redeemScriptBytes = lastChunk.data;

				// If non-push scripts, redeemScript will be null
				if (redeemScriptBytes == null)
					continue;

				byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
				Address inputAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

				if (!inputAddress.toString().equals(p2shAddress))
					// Input isn't spending our P2SH
					continue;

				byte[] secret = scriptChunks.get(0).data;
				if (secret.length != BTCP2SH.SECRET_LENGTH)
					continue;

				return secret;
			}
		}

		return null;
	}

}
