package org.qortal.crosschain;

import static org.ciyam.at.OpCode.calcOffset;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.ciyam.at.API;
import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.ciyam.at.Timestamp;
import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.at.QortalAtLoggerFactory;
import org.qortal.at.QortalFunctionCode;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Base58;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;

/**
 * Cross-chain trade AT
 * 
 * <p>
 * <ul>
 * <li>Bob generates Bitcoin & Qortal 'trade' keys, and secret-b
 * 		<ul>
 * 			<li>private key required to sign P2SH redeem tx</li>
 * 			<li>private key can be used to create 'secret' (e.g. double-SHA256)</li>
 * 			<li>encrypted private key could be stored in Qortal AT for access by Bob from any node</li>
 * 		</ul>
 * </li>
 * <li>Bob deploys Qortal AT
 * 		<ul>
 * 		</ul>
 * </li>
 * <li>Alice finds Qortal AT and wants to trade
 * 		<ul>
 * 			<li>Alice generates Bitcoin & Qortal 'trade' keys</li>
 * 			<li>Alice funds Bitcoin P2SH-a</li>
 * 			<li>Alice MESSAGEs Bob from her Qortal trade address, sending secret-hash-a and Bitcoin PKH</li>
 * 		</ul>
 * </li>
 * <li>Bob receives MESSAGE
 * 		<ul>
 * 			<li>Checks Alice's P2SH-a</li>
 * 			<li>Sends MESSAGE to Qortal AT from his trade address, containing:
 * 				<ul>
 * 					<li>Alice's trade Qortal address</li>
 * 					<li>Alice's trade Bitcoin PKH</li>
 * 					<li>secret-hash-a</li>
 * 				</ul>
 * 			</li>
 * 		</ul>
 * </li>
 * <li>Alice checks Qortal AT to confirm it's locked to her
 * 		<ul>
 * 			<li>Alice creates/funds Bitcoin P2SH-b</li>
 * 		</ul>
 * </li>
 * <li>Bob checks P2SH-b is funded
 * 		<ul>
 * 			<li>Bob redeems P2SH-b using his Bitcoin trade key and secret-b</li>
 * 		</ul>
 * </li>
 * <li>Alice scans P2SH-b redeem tx to extract secret-b
 * 		<ul>
 * 			<li>Alice MESSAGEs Qortal AT from her trade address, sending secret-a & secret-b</li>
 * 		</ul>
 * </li>
 * <li>Bob checks AT, extracts secret-a
 * 		<ul>
 * 			<li>Bob redeems P2SH-a using his Bitcoin trade key and secret-a</li>
 * 		</ul>
 * </li>
 * </ul>
 */
public class BTCACCT {

	public static final int SECRET_LENGTH = 32;
	public static final int MIN_LOCKTIME = 1500000000;
	public static final byte[] CODE_BYTES_HASH = HashCode.fromString("ae1c6749b08465a5dec0224ab25e7551947f900df404bfed434a02fdad102b03").asBytes(); // SHA256 of AT code bytes

	private BTCACCT() {
	}

	/**
	 * Returns Qortal AT creation bytes for cross-chain trading AT.
	 * <p>
	 * <tt>tradeTimeout</tt> (minutes) is the time window for the recipient to send the
	 * 32-byte secret to the AT, before the AT automatically refunds the AT's creator.
	 * 
	 * @param qortalCreator Qortal address for AT creator, also used for refunds
	 * @param bitcoinPublicKeyHash 20-byte HASH160 of creator's bitcoin public key
	 * @param hashOfSecretB 20-byte HASH160 of 32-byte secret
	 * @param tradeTimeout how many minutes, from AT creation, until AT auto-refunds AT creator
	 * @param qortAmount how much QORT to pay trade partner if they send correct 32-byte secret to AT
	 * @param bitcoinAmount how much BTC the AT creator is expecting to trade
	 * @return
	 */
	public static byte[] buildQortalAT(String creatorTradeAddress, byte[] bitcoinPublicKeyHash, byte[] hashOfSecretB, int tradeTimeout, long qortAmount, long bitcoinAmount) {
		// Labels for data segment addresses
		int addrCounter = 0;

		// Constants (with corresponding dataByteBuffer.put*() calls below)

		final int addrCreatorTradeAddress1 = addrCounter++;
		final int addrCreatorTradeAddress2 = addrCounter++;
		final int addrCreatorTradeAddress3 = addrCounter++;
		final int addrCreatorTradeAddress4 = addrCounter++;

		final int addrBitcoinPublicKeyHash = addrCounter;
		addrCounter += 4;

		final int addrHashOfSecretB = addrCounter;
		addrCounter += 4;

		final int addrTradeTimeout = addrCounter++;
		final int addrRefundTimeout = addrCounter++;
		final int addrQortAmount = addrCounter++;
		final int addrBitcoinAmount = addrCounter++;

		final int addrMessageTxType = addrCounter++;
		final int addrExpectedOfferMessageLength = addrCounter++;
		final int addrExpectedTradeMessageLength = addrCounter++;

		final int addrCreatorAddressPointer = addrCounter++;
		final int addrHashOfSecretBPointer = addrCounter++;
		final int addrQortalRecipientPointer = addrCounter++;
		final int addrMessageSenderPointer = addrCounter++;

		final int addrOfferMessageRecipientBitcoinPKHOffset = addrCounter++;
		final int addrRecipientBitcoinPKHPointer = addrCounter++;
		final int addrOfferMessageHashOfSecretAOffset = addrCounter++;
		final int addrHashOfSecretAPointer = addrCounter++;

		final int addrTradeMessageSecretBOffset = addrCounter++;

		final int addrMessageDataPointer = addrCounter++;
		final int addrMessageDataLength = addrCounter++;

		final int addrEndOfConstants = addrCounter;

		// Variables

		final int addrCreatorAddress1 = addrCounter++;
		final int addrCreatorAddress2 = addrCounter++;
		final int addrCreatorAddress3 = addrCounter++;
		final int addrCreatorAddress4 = addrCounter++;

		final int addrQortalRecipient1 = addrCounter++;
		final int addrQortalRecipient2 = addrCounter++;
		final int addrQortalRecipient3 = addrCounter++;
		final int addrQortalRecipient4 = addrCounter++;

		final int addrRefundTimestamp = addrCounter++;
		final int addrLastTxTimestamp = addrCounter++;
		final int addrBlockTimestamp = addrCounter++;
		final int addrTxType = addrCounter++;
		final int addrResult = addrCounter++;

		final int addrMessageSender1 = addrCounter++;
		final int addrMessageSender2 = addrCounter++;
		final int addrMessageSender3 = addrCounter++;
		final int addrMessageSender4 = addrCounter++;

		final int addrMessageLength = addrCounter++;

		final int addrMessageData = addrCounter;
		addrCounter += 4;

		final int addrHashOfSecretA = addrCounter;
		addrCounter += 4;

		final int addrRecipientBitcoinPKH = addrCounter;
		addrCounter += 4;

		final int addrMode = addrCounter++;

		// Data segment
		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);

		// AT creator's Qortal address, decoded from Base58
		assert dataByteBuffer.position() == addrCreatorTradeAddress1 * MachineState.VALUE_SIZE : "addrCreatorTradeAddress1 incorrect";
		byte[] creatorTradeAddressBytes = Base58.decode(creatorTradeAddress);
		dataByteBuffer.put(Bytes.ensureCapacity(creatorTradeAddressBytes, 32, 0));

		// Bitcoin public key hash
		assert dataByteBuffer.position() == addrBitcoinPublicKeyHash * MachineState.VALUE_SIZE : "addrBitcoinPublicKeyHash incorrect";
		dataByteBuffer.put(Bytes.ensureCapacity(bitcoinPublicKeyHash, 32, 0));

		// Hash of secret
		assert dataByteBuffer.position() == addrHashOfSecretB * MachineState.VALUE_SIZE : "addrHashOfSecretB incorrect";
		dataByteBuffer.put(Bytes.ensureCapacity(hashOfSecretB, 32, 0));

		// Trade timeout in minutes
		assert dataByteBuffer.position() == addrTradeTimeout * MachineState.VALUE_SIZE : "addrTradeTimeout incorrect";
		dataByteBuffer.putLong(tradeTimeout);

		// Refund timeout in minutes (¾ of trade-timeout)
		assert dataByteBuffer.position() == addrRefundTimeout * MachineState.VALUE_SIZE : "addrRefundTimeout incorrect";
		dataByteBuffer.putLong(tradeTimeout * 3 / 4);

		// Redeem Qort amount
		assert dataByteBuffer.position() == addrQortAmount * MachineState.VALUE_SIZE : "addrQortAmount incorrect";
		dataByteBuffer.putLong(qortAmount);

		// Expected Bitcoin amount
		assert dataByteBuffer.position() == addrBitcoinAmount * MachineState.VALUE_SIZE : "addrBitcoinAmount incorrect";
		dataByteBuffer.putLong(bitcoinAmount);

		// We're only interested in MESSAGE transactions
		assert dataByteBuffer.position() == addrMessageTxType * MachineState.VALUE_SIZE : "addrMessageTxType incorrect";
		dataByteBuffer.putLong(API.ATTransactionType.MESSAGE.value);

		// Expected length of OFFER MESSAGE data from AT creator
		assert dataByteBuffer.position() == addrExpectedOfferMessageLength * MachineState.VALUE_SIZE : "addrExpectedOfferMessageLength incorrect";
		dataByteBuffer.putLong(32L + 32L + 32L);

		// Expected length of TRADE MESSAGE data from trade partner / "recipient"
		assert dataByteBuffer.position() == addrExpectedTradeMessageLength * MachineState.VALUE_SIZE : "addrExpectedTradeMessageLength incorrect";
		dataByteBuffer.putLong(32L + 32L);

		// Index into data segment of AT creator's address, used by GET_B_IND
		assert dataByteBuffer.position() == addrCreatorAddressPointer * MachineState.VALUE_SIZE : "addrCreatorAddressPointer incorrect";
		dataByteBuffer.putLong(addrCreatorAddress1);

		// Index into data segment of hash, used by GET_B_IND
		assert dataByteBuffer.position() == addrHashOfSecretBPointer * MachineState.VALUE_SIZE : "addrHashOfSecretBPointer incorrect";
		dataByteBuffer.putLong(addrHashOfSecretB);

		// Index into data segment of recipient address, used by SET_B_IND
		assert dataByteBuffer.position() == addrQortalRecipientPointer * MachineState.VALUE_SIZE : "addrQortalRecipientPointer incorrect";
		dataByteBuffer.putLong(addrQortalRecipient1);

		// Index into data segment of (temporary) transaction's sender's address, used by GET_B_IND
		assert dataByteBuffer.position() == addrMessageSenderPointer * MachineState.VALUE_SIZE : "addrMessageSenderPointer incorrect";
		dataByteBuffer.putLong(addrMessageSender1);

		// Offset into OFFER MESSAGE data payload for extracting recipient's Bitcoin PKH
		assert dataByteBuffer.position() == addrOfferMessageRecipientBitcoinPKHOffset * MachineState.VALUE_SIZE : "addrOfferMessageRecipientBitcoinPKHOffset incorrect";
		dataByteBuffer.putLong(32L);

		// Index into data segment of hash, used by SET_B_IND
		assert dataByteBuffer.position() == addrRecipientBitcoinPKHPointer * MachineState.VALUE_SIZE : "addrRecipientBitcoinPKHPointer incorrect";
		dataByteBuffer.putLong(addrRecipientBitcoinPKH);

		// Offset into OFFER MESSAGE data payload for extracting hash-of-secret-A
		assert dataByteBuffer.position() == addrOfferMessageHashOfSecretAOffset * MachineState.VALUE_SIZE : "addrOfferMessageHashOfSecretAOffset incorrect";
		dataByteBuffer.putLong(64L);

		// Index into data segment of hash, used by GET_B_IND
		assert dataByteBuffer.position() == addrHashOfSecretAPointer * MachineState.VALUE_SIZE : "addrHashOfSecretAPointer incorrect";
		dataByteBuffer.putLong(addrHashOfSecretA);

		// Offset into TRADE MESSAGE data payload for extracting secret-B
		assert dataByteBuffer.position() == addrTradeMessageSecretBOffset * MachineState.VALUE_SIZE : "addrTradeMessageSecretBOffset incorrect";
		dataByteBuffer.putLong(64L);

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

		Integer labelCheckNonRefundOfferTx = null;
		Integer labelOfferTxExtract = null;
		Integer labelTradeTxLoop = null;
		Integer labelCheckTradeTx = null;
		Integer labelCheckTradeSender = null;
		Integer labelCheckSecretB = null;
		Integer labelPayout = null;

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);

		// Two-pass version
		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				/* Initialization */

				// Use AT creation 'timestamp' as starting point for finding transactions sent to AT
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));

				// Calculate trade timeout refund 'timestamp' by adding addrRefundTimeout minutes to AT creation 'timestamp', then save into addrRefundTimestamp
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.ADD_MINUTES_TO_TIMESTAMP, addrRefundTimestamp, addrLastTxTimestamp, addrRefundTimeout));

				// Load B register with AT creator's address so we can save it into addrCreatorAddress1-4
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_CREATOR_INTO_B));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrCreatorAddressPointer));

				// Set restart position to after this opcode
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				/* Loop, waiting for message from AT creator's trade address containing trade partner details, or AT owner's address to cancel offer */

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

				/* Check transaction's sender. We're expecting AT creator's trade address. */

				// Extract sender address from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrMessageSender1 (as pointed to by addrMessageSenderPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageSenderPointer));
				// Compare each part of transaction's sender's address with expected address. If they don't match, look for another transaction.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender1, addrCreatorTradeAddress1, calcOffset(codeByteBuffer, labelOfferTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender2, addrCreatorTradeAddress2, calcOffset(codeByteBuffer, labelOfferTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender3, addrCreatorTradeAddress3, calcOffset(codeByteBuffer, labelOfferTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender4, addrCreatorTradeAddress4, calcOffset(codeByteBuffer, labelOfferTxLoop)));

				/* Extract trade partner info from message */

				// Extract message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrQortalRecipient1 (as pointed to by addrQortalRecipientPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrQortalRecipientPointer));
				// Compare each of recipient address with creator's address (for offer-cancel scenario). If they don't match, assume recipient is trade partner.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalRecipient1, addrCreatorAddress1, calcOffset(codeByteBuffer, labelCheckNonRefundOfferTx)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalRecipient2, addrCreatorAddress2, calcOffset(codeByteBuffer, labelCheckNonRefundOfferTx)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalRecipient3, addrCreatorAddress3, calcOffset(codeByteBuffer, labelCheckNonRefundOfferTx)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalRecipient4, addrCreatorAddress4, calcOffset(codeByteBuffer, labelCheckNonRefundOfferTx)));
				// Recipient address is AT creator's address, so cancel offer and finish.
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRefund == null ? 0 : labelRefund));

				/* Possible switch-to-trade-mode message */
				labelCheckNonRefundOfferTx = codeByteBuffer.position();

				// Not off-cancel scenario so check we received expected number of message bytes
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(QortalFunctionCode.GET_MESSAGE_LENGTH_FROM_TX_IN_A.value, addrMessageLength));
				codeByteBuffer.put(OpCode.BEQ_DAT.compile(addrMessageLength, addrExpectedOfferMessageLength, calcOffset(codeByteBuffer, labelOfferTxExtract)));
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelOfferTxLoop == null ? 0 : labelOfferTxLoop));

				labelOfferTxExtract = codeByteBuffer.position();

				// Message is expected length, extract recipient's Bitcoin PKH
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, addrOfferMessageRecipientBitcoinPKHOffset));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrRecipientBitcoinPKHPointer));

				// Extract hash-of-secret-a
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, addrOfferMessageHashOfSecretAOffset));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrHashOfSecretAPointer));

				/* We are in 'trade mode' */
				codeByteBuffer.put(OpCode.SET_VAL.compile(addrMode, 1));

				// Set restart position to after this opcode
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				/* Loop, waiting for trade timeout or message from Qortal trade recipient containing secret-a and secret-b */

				// Fetch current block 'timestamp'
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_BLOCK_TIMESTAMP, addrBlockTimestamp));
				// If we're not past refund 'timestamp' then look for next transaction
				codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBlockTimestamp, addrRefundTimestamp, calcOffset(codeByteBuffer, labelTradeTxLoop)));
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

				/* Check message payload length */
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(QortalFunctionCode.GET_MESSAGE_LENGTH_FROM_TX_IN_A.value, addrMessageLength));
				codeByteBuffer.put(OpCode.BEQ_DAT.compile(addrMessageLength, addrExpectedTradeMessageLength, calcOffset(codeByteBuffer, labelCheckTradeSender)));
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelOfferTxLoop == null ? 0 : labelOfferTxLoop));

				/* Check transaction's sender */

				labelCheckTradeSender = codeByteBuffer.position();

				// Extract sender address from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrMessageSender1 (as pointed to by addrMessageSenderPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageSenderPointer));
				// Compare each part of transaction's sender's address with expected address. If they don't match, look for another transaction.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender1, addrQortalRecipient1, calcOffset(codeByteBuffer, labelTradeTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender2, addrQortalRecipient2, calcOffset(codeByteBuffer, labelTradeTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender3, addrQortalRecipient3, calcOffset(codeByteBuffer, labelTradeTxLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender4, addrQortalRecipient4, calcOffset(codeByteBuffer, labelTradeTxLoop)));

				/* Check 'secret-a' in transaction's message */

				// Extract secret-A from first 32 bytes of message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrMessageData (as pointed to by addrMessageDataPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageDataPointer));
				// Load B register with expected hash result (as pointed to by addrHashOfSecretAPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrHashOfSecretAPointer));
				// Perform HASH160 using source data at addrMessageData. (Location and length specified via addrMessageDataPointer and addrMessageDataLength).
				// Save the equality result (1 if they match, 0 otherwise) into addrResult.
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.CHECK_HASH160_WITH_B, addrResult, addrMessageDataPointer, addrMessageDataLength));
				// If hashes don't match, addrResult will be zero so go find another transaction
				codeByteBuffer.put(OpCode.BNZ_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelCheckSecretB)));
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelTradeTxLoop == null ? 0 : labelTradeTxLoop));

				/* Check 'secret-b' in transaction's message */

				labelCheckSecretB = codeByteBuffer.position();

				// Extract secret-B from next 32 bytes of message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, addrTradeMessageSecretBOffset));
				// Save B register into data segment starting at addrMessageData (as pointed to by addrMessageDataPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageDataPointer));
				// Load B register with expected hash result (as pointed to by addrHashOfSecretBPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrHashOfSecretBPointer));
				// Perform HASH160 using source data at addrMessageData. (Location and length specified via addrMessageDataPointer and addrMessageDataLength).
				// Save the equality result (1 if they match, 0 otherwise) into addrResult.
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.CHECK_HASH160_WITH_B, addrResult, addrMessageDataPointer, addrMessageDataLength));
				// If hashes don't match, addrResult will be zero so go find another transaction
				codeByteBuffer.put(OpCode.BNZ_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelPayout)));
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelTradeTxLoop == null ? 0 : labelTradeTxLoop));

				/* Success! Pay arranged amount to intended recipient */
				labelPayout = codeByteBuffer.position();

				// Load B register with intended recipient address (as pointed to by addrQortalRecipientPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrQortalRecipientPointer));
				// Pay AT's balance to recipient
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrQortAmount));
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
		byte[] addressBytes = new byte[25];

		// Skip creator's trade address
		dataByteBuffer.get(addressBytes);
		tradeData.qortalCreatorTradeAddress = Base58.encode(addressBytes);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - addressBytes.length);

		// Creator's Bitcoin/foreign public key hash
		tradeData.creatorBitcoinPKH = new byte[20];
		dataByteBuffer.get(tradeData.creatorBitcoinPKH);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - tradeData.creatorBitcoinPKH.length); // skip to 32 bytes

		// Hash of secret B
		tradeData.hashOfSecretB = new byte[20];
		dataByteBuffer.get(tradeData.hashOfSecretB);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - tradeData.hashOfSecretB.length); // skip to 32 bytes

		// Trade timeout
		tradeData.tradeTimeout = (int) dataByteBuffer.getLong();

		// AT refund timeout (probably only useful for debugging)
		tradeData.refundTimeout = (int) dataByteBuffer.getLong();

		// Redeem payout
		tradeData.qortAmount = dataByteBuffer.getLong();

		// Expected BTC amount
		tradeData.expectedBitcoin = dataByteBuffer.getLong();

		// Skip MESSAGE transaction type
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip expected OFFER message length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip expected TRADE message length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to creator's address
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to hash-of-secret-B
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to Qortal recipient
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to message sender
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip OFFER message data offset for recipient's bitcoin PKH
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to recipient's bitcoin PKH
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip OFFER message data offset for hash-of-secret-A
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to hash-of-secret-A
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip TRADE message data offset for secret-B
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to message data
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip message data length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		/* End of constants */

		// Skip AT creator's address
		dataByteBuffer.position(dataByteBuffer.position() + 8 * 4);

		// Recipient's trade address (if present)
		dataByteBuffer.get(addressBytes);
		String qortalRecipient = Base58.encode(addressBytes);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - addressBytes.length);

		// Trade offer timeout (AT 'timestamp' converted to Qortal block height)
		long tradeRefundTimestamp = dataByteBuffer.getLong();

		// Last transaction timestamp
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip block timestamp
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip transaction type
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip temporary result
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip temporary message sender
		dataByteBuffer.position(dataByteBuffer.position() + 8 * 4);

		// Skip message length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip temporary message data
		dataByteBuffer.position(dataByteBuffer.position() + 8 * 4);

		// Potential hash of secret A
		byte[] hashOfSecretA = new byte[32];
		dataByteBuffer.get(hashOfSecretA);

		// Potential recipient's Bitcoin PKH
		byte[] recipientBitcoinPKH = new byte[20];
		dataByteBuffer.get(recipientBitcoinPKH);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - recipientBitcoinPKH.length); // skip to 32 bytes

		long mode = dataByteBuffer.getLong();

		if (mode != 0) {
			tradeData.mode = CrossChainTradeData.Mode.TRADE;
			tradeData.tradeRefundHeight = new Timestamp(tradeRefundTimestamp).blockHeight;
			tradeData.qortalRecipient = qortalRecipient;
			tradeData.hashOfSecretA = hashOfSecretA;
			tradeData.recipientBitcoinPKH = recipientBitcoinPKH;
		} else {
			tradeData.mode = CrossChainTradeData.Mode.OFFER;
		}

		return tradeData;
	}

}
