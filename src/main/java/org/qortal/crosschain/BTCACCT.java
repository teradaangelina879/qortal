package org.qortal.crosschain;

import static org.ciyam.at.OpCode.calcOffset;

import java.nio.ByteBuffer;
import java.util.Arrays;
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
import org.ciyam.at.API;
import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.ciyam.at.Timestamp;
import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.at.QortalAtLoggerFactory;
import org.qortal.block.BlockChain;
import org.qortal.block.BlockChain.CiyamAtSettings;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Base58;
import org.qortal.utils.BitTwiddling;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;

/*
 * Bob generates Bitcoin private key
 * 		private key required to sign P2SH redeem tx
 * 		private key can be used to create 'secret' (e.g. double-SHA256)
 * 		encrypted private key could be stored in Qortal AT for access by Bob from any node
 * Bob creates Qortal AT
 * Alice finds Qortal AT and wants to trade
 * 		Alice generates Bitcoin private key
 * 		Alice will need to send Bob her Qortal address and Bitcoin refund address
 * Bob sends Alice's Qortal address to Qortal AT
 * Qortal AT sends initial QORT payment to Alice (so she has QORT to send message to AT and claim funds)
 * Alice receives funds and checks Qortal AT to confirm it's locked to her
 * Alice creates/funds Bitcoin P2SH
 * 		Alice requires: Bob's redeem Bitcoin address, Alice's refund Bitcoin address, derived locktime
 * Bob checks P2SH is funded
 * 		Bob requires: Bob's redeem Bitcoin address, Alice's refund Bitcoin address, derived locktime
 * Bob uses secret to redeem P2SH
 * 		Qortal core/UI will need to create, and sign, this transaction
 * Alice scans P2SH redeem tx and uses secret to redeem Qortal AT
 */

public class BTCACCT {

	public static final int SECRET_LENGTH = 32;
	public static final int MIN_LOCKTIME = 1500000000;
	public static final byte[] CODE_BYTES_HASH = HashCode.fromString("edcdb1feb36e079c5f956faff2f24219b12e5fbaaa05654335e615e33218282f").asBytes(); // SHA256 of AT code bytes

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
	 * See comments in {@link BTCACCT} for more details.
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

	/**
	 * Returns Qortal AT creation bytes for cross-chain trading AT.
	 * <p>
	 * <tt>tradeTimeout</tt> (minutes) is the time window for the recipient to send the
	 * 32-byte secret to the AT, before the AT automatically refunds the AT's creator.
	 * 
	 * @param qortalCreator Qortal address for AT creator, also used for refunds
	 * @param bitcoinPublicKeyHash 20-byte HASH160 of creator's bitcoin public key
	 * @param secretHash 20-byte HASH160 of 32-byte secret
	 * @param tradeTimeout how many minutes, from start of 'trade mode' until AT auto-refunds AT creator
	 * @param initialPayout how much QORT to pay trade partner upon switch to 'trade mode'
	 * @param redeemPayout how much QORT to pay trade partner if they send correct 32-byte secret to AT
	 * @param bitcoinAmount how much BTC the AT creator is expecting to trade
	 * @return
	 */
	public static byte[] buildQortalAT(String qortalCreator, byte[] bitcoinPublicKeyHash, byte[] secretHash, int tradeTimeout, long initialPayout, long redeemPayout, long bitcoinAmount) {
		// Labels for data segment addresses
		int addrCounter = 0;

		// Constants (with corresponding dataByteBuffer.put*() calls below)

		final int addrQortalCreator1 = addrCounter++;
		final int addrQortalCreator2 = addrCounter++;
		final int addrQortalCreator3 = addrCounter++;
		final int addrQortalCreator4 = addrCounter++;

		final int addrBitcoinPublickeyHash = addrCounter;
		addrCounter += 4;

		final int addrSecretHash = addrCounter;
		addrCounter += 4;

		final int addrTradeTimeout = addrCounter++;
		final int addrInitialPayoutAmount = addrCounter++;
		final int addrRedeemPayoutAmount = addrCounter++;
		final int addrBitcoinAmount = addrCounter++;

		final int addrMessageTxType = addrCounter++;

		final int addrSecretHashPointer = addrCounter++;
		final int addrQortalRecipientPointer = addrCounter++;
		final int addrMessageSenderPointer = addrCounter++;

		final int addrMessageDataPointer = addrCounter++;
		final int addrMessageDataLength = addrCounter++;

		final int addrEndOfConstants = addrCounter;

		// Variables

		final int addrQortalRecipient1 = addrCounter++;
		final int addrQortalRecipient2 = addrCounter++;
		final int addrQortalRecipient3 = addrCounter++;
		final int addrQortalRecipient4 = addrCounter++;

		final int addrTradeRefundTimestamp = addrCounter++;
		final int addrLastTxTimestamp = addrCounter++;
		final int addrBlockTimestamp = addrCounter++;
		final int addrTxType = addrCounter++;
		final int addrResult = addrCounter++;

		final int addrMessageSender1 = addrCounter++;
		final int addrMessageSender2 = addrCounter++;
		final int addrMessageSender3 = addrCounter++;
		final int addrMessageSender4 = addrCounter++;

		final int addrMessageData = addrCounter;
		addrCounter += 4;

		// Data segment
		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);

		// AT creator's Qortal address, decoded from Base58
		assert dataByteBuffer.position() == addrQortalCreator1 * MachineState.VALUE_SIZE : "addrQortalCreator1 incorrect";
		byte[] qortalCreatorBytes = Base58.decode(qortalCreator);
		dataByteBuffer.put(Bytes.ensureCapacity(qortalCreatorBytes, 32, 0));

		// Bitcoin public key hash
		assert dataByteBuffer.position() == addrBitcoinPublickeyHash * MachineState.VALUE_SIZE : "addrBitcoinPublicKeyHash incorrect";
		dataByteBuffer.put(Bytes.ensureCapacity(bitcoinPublicKeyHash, 32, 0));

		// Hash of secret
		assert dataByteBuffer.position() == addrSecretHash * MachineState.VALUE_SIZE : "addrSecretHash incorrect";
		dataByteBuffer.put(Bytes.ensureCapacity(secretHash, 32, 0));

		// Trade timeout in minutes
		assert dataByteBuffer.position() == addrTradeTimeout * MachineState.VALUE_SIZE : "addrTradeTimeout incorrect";
		dataByteBuffer.putLong(tradeTimeout);

		// Initial payout amount
		assert dataByteBuffer.position() == addrInitialPayoutAmount * MachineState.VALUE_SIZE : "addrInitialPayoutAmount incorrect";
		dataByteBuffer.putLong(initialPayout);

		// Redeem payout amount
		assert dataByteBuffer.position() == addrRedeemPayoutAmount * MachineState.VALUE_SIZE : "addrRedeemPayoutAmount incorrect";
		dataByteBuffer.putLong(redeemPayout);

		// Expected Bitcoin amount
		assert dataByteBuffer.position() == addrBitcoinAmount * MachineState.VALUE_SIZE : "addrBitcoinAmount incorrect";
		dataByteBuffer.putLong(bitcoinAmount);

		// We're only interested in MESSAGE transactions
		assert dataByteBuffer.position() == addrMessageTxType * MachineState.VALUE_SIZE : "addrMessageTxType incorrect";
		dataByteBuffer.putLong(API.ATTransactionType.MESSAGE.value);

		// Index into data segment of hash, used by GET_B_IND
		assert dataByteBuffer.position() == addrSecretHashPointer * MachineState.VALUE_SIZE : "addrSecretHashPointer incorrect";
		dataByteBuffer.putLong(addrSecretHash);

		// Index into data segment of recipient address, used by SET_B_IND
		assert dataByteBuffer.position() == addrQortalRecipientPointer * MachineState.VALUE_SIZE : "addrQortalRecipientPointer incorrect";
		dataByteBuffer.putLong(addrQortalRecipient1);

		// Index into data segment of (temporary) transaction's sender's address, used by GET_B_IND
		assert dataByteBuffer.position() == addrMessageSenderPointer * MachineState.VALUE_SIZE : "addrMessageSenderPointer incorrect";
		dataByteBuffer.putLong(addrMessageSender1);

		// Source location and length for hashing any passed secret
		assert dataByteBuffer.position() == addrMessageDataPointer * MachineState.VALUE_SIZE : "addrMessageDataPointer incorrect";
		dataByteBuffer.putLong(addrMessageData);
		assert dataByteBuffer.position() == addrMessageDataLength * MachineState.VALUE_SIZE : "addrMessageDataLength incorrect";
		dataByteBuffer.putLong(32L);

		assert dataByteBuffer.position() == addrEndOfConstants * MachineState.VALUE_SIZE : "dataByteBuffer position not at end of constants";

		// Code labels
		Integer labelRefund = null;

		Integer labelOfferTxLoop = null;
		Integer labelCheckOfferTx = null;

		Integer labelTradeMode = null;
		Integer labelTradeTxLoop = null;
		Integer labelCheckTradeTx = null;

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);

		// Two-pass version
		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				/* Initialization */

				// Use AT creation 'timestamp' as starting point for finding transactions sent to AT
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));

				// Set restart position to after this opcode
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				/* Loop, waiting for message from AT owner containing trade partner details, or AT owner's address to cancel offer */

				/* Transaction processing loop */
				labelOfferTxLoop = codeByteBuffer.position();

				// Find next transaction to this AT since the last one (if any)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				// If no transaction found, A will be zero. If A is zero, set addrComparator to 1, otherwise 0.
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrResult));
				// If addrResult is zero (i.e. A is non-zero, transaction was found) then go check transaction
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelCheckOfferTx)));
				// Stop and wait for next block
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				/* Check transaction */
				labelCheckOfferTx = codeByteBuffer.position();

				// Update our 'last found transaction's timestamp' using 'timestamp' from transaction
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, addrLastTxTimestamp));
				// Extract transaction type (message/payment) from transaction and save type in addrTxType
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, addrTxType));
				// If transaction type is not MESSAGE type then go look for another transaction
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrTxType, addrMessageTxType, calcOffset(codeByteBuffer, labelOfferTxLoop)));

				/* Check transaction's sender */

				// Extract sender address from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrMessageSender1 (as pointed to by addrMessageSenderPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageSenderPointer));
				// Compare each part of transaction's sender's address with expected address. If they don't match, look for another transaction.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender1, addrQortalCreator1, calcOffset(codeByteBuffer, labelOfferTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender2, addrQortalCreator2, calcOffset(codeByteBuffer, labelOfferTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender3, addrQortalCreator3, calcOffset(codeByteBuffer, labelOfferTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender4, addrQortalCreator4, calcOffset(codeByteBuffer, labelOfferTxLoop)));

				/* Extract trade partner info from message */

				// Extract message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrQortalRecipient1 (as pointed to by addrQortalRecipientPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrQortalRecipientPointer));
				// Compare each of recipient address with creator's address (for offer-cancel scenario). If they don't match, assume recipient is trade partner.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalRecipient1, addrQortalCreator1, calcOffset(codeByteBuffer, labelTradeMode)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalRecipient2, addrQortalCreator2, calcOffset(codeByteBuffer, labelTradeMode)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalRecipient3, addrQortalCreator3, calcOffset(codeByteBuffer, labelTradeMode)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalRecipient4, addrQortalCreator4, calcOffset(codeByteBuffer, labelTradeMode)));
				// Recipient address is AT creator's address, so cancel offer and finish.
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRefund == null ? 0 : labelRefund));

				/* Switch to 'trade mode' */
				labelTradeMode = codeByteBuffer.position();

				// Send initial payment to recipient so they have enough funds to message AT if all goes well
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrInitialPayoutAmount));

				// Calculate trade timeout refund 'timestamp' by adding addrTradeTimeout minutes to above message's 'timestamp', then save into addrTradeRefundTimestamp
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.ADD_MINUTES_TO_TIMESTAMP, addrTradeRefundTimestamp, addrLastTxTimestamp, addrTradeTimeout));

				// Set restart position to after this opcode
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				/* Loop, waiting for trade timeout or message from Qortal trade recipient containing secret */

				// Fetch current block 'timestamp'
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_BLOCK_TIMESTAMP, addrBlockTimestamp));
				// If we're not past refund 'timestamp' then look for next transaction
				codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBlockTimestamp, addrTradeRefundTimestamp, calcOffset(codeByteBuffer, labelTradeTxLoop)));
				// We're past refund 'timestamp' so go refund everything back to AT creator
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRefund == null ? 0 : labelRefund));

				/* Transaction processing loop */
				labelTradeTxLoop = codeByteBuffer.position();

				// Find next transaction to this AT since the last one (if any)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				// If no transaction found, A will be zero. If A is zero, set addrComparator to 1, otherwise 0.
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrResult));
				// If addrResult is zero (i.e. A is non-zero, transaction was found) then go check transaction
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelCheckTradeTx)));
				// Stop and wait for next block
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				/* Check transaction */
				labelCheckTradeTx = codeByteBuffer.position();

				// Update our 'last found transaction's timestamp' using 'timestamp' from transaction
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, addrLastTxTimestamp));
				// Extract transaction type (message/payment) from transaction and save type in addrTxType
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, addrTxType));
				// If transaction type is not MESSAGE type then go look for another transaction
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrTxType, addrMessageTxType, calcOffset(codeByteBuffer, labelTradeTxLoop)));

				/* Check transaction's sender */

				// Extract sender address from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrMessageSender1 (as pointed to by addrMessageSenderPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageSenderPointer));
				// Compare each part of transaction's sender's address with expected address. If they don't match, look for another transaction.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender1, addrQortalRecipient1, calcOffset(codeByteBuffer, labelTradeTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender2, addrQortalRecipient2, calcOffset(codeByteBuffer, labelTradeTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender3, addrQortalRecipient3, calcOffset(codeByteBuffer, labelTradeTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender4, addrQortalRecipient4, calcOffset(codeByteBuffer, labelTradeTxLoop)));

				/* Check 'secret' in transaction's message */

				// Extract message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrMessageData (as pointed to by addrMessageDataPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageDataPointer));
				// Load B register with expected hash result (as pointed to by addrSecretHashPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrSecretHashPointer));
				// Perform HASH160 using source data at addrMessageData. (Location and length specified via addrMessageDataPointer and addrMessageDataLength).
				// Save the equality result (1 if they match, 0 otherwise) into addrResult.
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.CHECK_HASH160_WITH_B, addrResult, addrMessageDataPointer, addrMessageDataLength));
				// If hashes don't match, addrResult will be zero so go find another transaction
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelTradeTxLoop)));

				/* Success! Pay arranged amount to intended recipient */

				// Load B register with intended recipient address (as pointed to by addrQortalRecipientPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrQortalRecipientPointer));
				// Pay AT's balance to recipient
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrRedeemPayoutAmount));
				// Fall-through to refunding any remaining balance back to AT creator

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

		assert Arrays.equals(Crypto.digest(codeBytes), BTCACCT.CODE_BYTES_HASH)
			: String.format("BTCACCT.CODE_BYTES_HASH mismatch: expected %s, actual %s", HashCode.fromBytes(CODE_BYTES_HASH), HashCode.fromBytes(Crypto.digest(codeBytes)));

		final short ciyamAtVersion = 2;
		final short numCallStackPages = 0;
		final short numUserStackPages = 0;
		final long minActivationAmount = 0L;

		return MachineState.toCreationBytes(ciyamAtVersion, codeBytes, dataByteBuffer.array(), numCallStackPages, numUserStackPages, minActivationAmount);
	}

	/**
	 * Returns CrossChainTradeData with useful info extracted from AT.
	 * 
	 * @param repository
	 * @param atAddress
	 * @throws DataException
	 */
	public static CrossChainTradeData populateTradeData(Repository repository, ATData atData) throws DataException {
		String atAddress = atData.getATAddress();

		ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
		byte[] stateData = atStateData.getStateData();

		QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();
		byte[] dataBytes = MachineState.extractDataBytes(loggerFactory, stateData);

		CrossChainTradeData tradeData = new CrossChainTradeData();
		tradeData.qortalAtAddress = atAddress;
		tradeData.qortalCreator = Crypto.toAddress(atData.getCreatorPublicKey());
		tradeData.creationTimestamp = atData.getCreation();

		Account atAccount = new Account(repository, atAddress);
		tradeData.qortBalance = atAccount.getConfirmedBalance(Asset.QORT);

		ByteBuffer dataByteBuffer = ByteBuffer.wrap(dataBytes);
		byte[] addressBytes = new byte[32];

		// Skip AT creator address
		dataByteBuffer.position(dataByteBuffer.position() + 32);

		// Bitcoin/foreign public key hash
		tradeData.foreignPublicKeyHash = new byte[20];
		dataByteBuffer.get(tradeData.foreignPublicKeyHash);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - 20); // skip to 32 bytes

		// Hash of secret
		tradeData.secretHash = new byte[20];
		dataByteBuffer.get(tradeData.secretHash);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - 20); // skip to 32 bytes

		// Trade timeout
		tradeData.tradeRefundTimeout = dataByteBuffer.getLong();

		// Initial payout
		tradeData.initialPayout = dataByteBuffer.getLong();

		// Redeem payout
		tradeData.redeemPayout = dataByteBuffer.getLong();

		// Expected BTC amount
		tradeData.expectedBitcoin = dataByteBuffer.getLong();

		// Skip MESSAGE transaction type
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to secretHash
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to Qortal recipient
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to message sender
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to message data
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip message data length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Qortal recipient (if any)
		dataByteBuffer.get(addressBytes);

		// Trade offer timeout (AT 'timestamp' converted to Qortal block height)
		long tradeRefundTimestamp = dataByteBuffer.getLong();

		if (tradeRefundTimestamp != 0) {
			tradeData.mode = CrossChainTradeData.Mode.TRADE;
			tradeData.tradeRefundHeight = new Timestamp(tradeRefundTimestamp).blockHeight;

			if (addressBytes[0] != 0)
				tradeData.qortalRecipient = Base58.encode(Arrays.copyOf(addressBytes, Account.ADDRESS_LENGTH));

			// We'll suggest half of trade timeout
			CiyamAtSettings ciyamAtSettings = BlockChain.getInstance().getCiyamAtSettings();

			int tradeModeSwitchHeight = (int) (tradeData.tradeRefundHeight - tradeData.tradeRefundTimeout / ciyamAtSettings.minutesPerBlock);

			BlockData blockData = repository.getBlockRepository().fromHeight(tradeModeSwitchHeight);
			if (blockData != null) {
				tradeData.tradeModeTimestamp = blockData.getTimestamp(); // NOTE: milliseconds from epoch
				tradeData.lockTime = (int) (tradeData.tradeModeTimestamp / 1000L + tradeData.tradeRefundTimeout / 2 * 60);
			}
		} else {
			tradeData.mode = CrossChainTradeData.Mode.OFFER;
		}

		return tradeData;
	}

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
				if (secret.length != BTCACCT.SECRET_LENGTH)
					continue;

				return secret;
			}
		}

		return null;
	}

}
