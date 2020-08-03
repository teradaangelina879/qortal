package org.qortal.crosschain;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;
import static org.ciyam.at.OpCode.calcOffset;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Base58;
import org.qortal.utils.BitTwiddling;

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
 * 			<li>private key could be used to create 'secret' (e.g. double-SHA256)</li>
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
 * 			<li>Alice funds Bitcoin P2SH-A</li>
 * 			<li>Alice sends 'offer' MESSAGE to Bob from her Qortal trade address, containing:
 * 				<ul>
 * 					<li>hash-of-secret-A</li>
 * 					<li>her 'trade' Bitcoin PKH</li>
 * 				</ul>
 * 			</li>
 * 		</ul>
 * </li>
 * <li>Bob receives "offer" MESSAGE
 * 		<ul>
 * 			<li>Checks Alice's P2SH-A</li>
 * 			<li>Sends 'trade' MESSAGE to Qortal AT from his trade address, containing:
 * 				<ul>
 * 					<li>Alice's trade Qortal address</li>
 * 					<li>Alice's trade Bitcoin PKH</li>
 * 					<li>hash-of-secret-A</li>
 * 				</ul>
 * 			</li>
 * 		</ul>
 * </li>
 * <li>Alice checks Qortal AT to confirm it's locked to her
 * 		<ul>
 * 			<li>Alice creates/funds Bitcoin P2SH-B</li>
 * 		</ul>
 * </li>
 * <li>Bob checks P2SH-B is funded
 * 		<ul>
 * 			<li>Bob redeems P2SH-B using his Bitcoin trade key and secret-B</li>
 * 		</ul>
 * </li>
 * <li>Alice scans P2SH-B redeem transaction to extract secret-B
 * 		<ul>
 * 			<li>Alice sends 'redeem' MESSAGE to Qortal AT from her trade address, containing:
 * 				<ul>
 * 					<li>secret-A</li>
 * 					<li>secret-B</li>
 * 					<li>Qortal receive address of her chosing</li>
 * 				</ul>
 * 			</li>
 * 			<li>AT's QORT funds are sent to Qortal receive address</li>
 * 		</ul>
 * </li>
 * <li>Bob checks AT, extracts secret-A
 * 		<ul>
 * 			<li>Bob redeems P2SH-A using his Bitcoin trade key and secret-A</li>
 * 			<li>P2SH-A BTC funds end up at Bitcoin address determined by redeem transaction output(s)</li>
 * 		</ul>
 * </li>
 * </ul>
 */
public class BTCACCT {

	public static final int SECRET_LENGTH = 32;
	public static final int MIN_LOCKTIME = 1500000000;
	public static final byte[] CODE_BYTES_HASH = HashCode.fromString("fad14381b77ae1a2bfe7e16a1a8b571839c5f405fca0490ead08499ac170f65b").asBytes(); // SHA256 of AT code bytes

	/** <b>Value</b> offset into AT segment where 'mode' variable (long) is stored. (Multiply by MachineState.VALUE_SIZE for byte offset). */
	private static final int MODE_VALUE_OFFSET = 63;
	/** <b>Byte</b> offset into AT state data where 'mode' variable (long) is stored. */
	public static final int MODE_BYTE_OFFSET = MachineState.HEADER_LENGTH + (MODE_VALUE_OFFSET * MachineState.VALUE_SIZE);

	public static class OfferMessageData {
		public byte[] partnerBitcoinPKH;
		public byte[] hashOfSecretA;
		public long lockTimeA;
	}
	public static final int OFFER_MESSAGE_LENGTH = 20 /*partnerBitcoinPKH*/ + 20 /*hashOfSecretA*/ + 8 /*lockTimeA*/;
	public static final int TRADE_MESSAGE_LENGTH = 32 /*partner's Qortal trade address (padded from 25 to 32)*/
			+ 24 /*partner's Bitcoin PKH (padded from 20 to 24)*/
			+ 8 /*lockTimeB*/
			+ 24 /*hash of secret-A (padded from 20 to 24)*/
			+ 8 /*lockTimeA*/;
	public static final int REDEEM_MESSAGE_LENGTH = 32 /*secret*/ + 32 /*secret*/ + 32 /*partner's Qortal receive address padded from 25 to 32*/;
	public static final int CANCEL_MESSAGE_LENGTH = 32 /*AT creator's Qortal address*/;

	public enum Mode {
		OFFERING(0), TRADING(1), CANCELLED(2), REFUNDED(3), REDEEMED(4);

		public final int value;
		private static final Map<Integer, Mode> map = stream(Mode.values()).collect(toMap(mode -> mode.value, mode -> mode));

		Mode(int value) {
			this.value = value;
		}

		public static Mode valueOf(int value) {
			return map.get(value);
		}
	}

	private BTCACCT() {
	}

	/**
	 * Returns Qortal AT creation bytes for cross-chain trading AT.
	 * <p>
	 * <tt>tradeTimeout</tt> (minutes) is the time window for the trade partner to send the
	 * 32-byte secret to the AT, before the AT automatically refunds the AT's creator.
	 * 
	 * @param creatorTradeAddress AT creator's trade Qortal address, also used for refunds
	 * @param bitcoinPublicKeyHash 20-byte HASH160 of creator's trade Bitcoin public key
	 * @param hashOfSecretB 20-byte HASH160 of 32-byte secret-B
	 * @param qortAmount how much QORT to pay trade partner if they send correct 32-byte secrets to AT
	 * @param bitcoinAmount how much BTC the AT creator is expecting to trade
	 * @param tradeTimeout suggested timeout for entire trade
	 * @return
	 */
	public static byte[] buildQortalAT(String creatorTradeAddress, byte[] bitcoinPublicKeyHash, byte[] hashOfSecretB, long qortAmount, long bitcoinAmount, int tradeTimeout) {
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

		final int addrQortAmount = addrCounter++;
		final int addrBitcoinAmount = addrCounter++;
		final int addrTradeTimeout = addrCounter++;

		final int addrMessageTxnType = addrCounter++;
		final int addrExpectedTradeMessageLength = addrCounter++;
		final int addrExpectedRedeemMessageLength = addrCounter++;

		final int addrCreatorAddressPointer = addrCounter++;
		final int addrHashOfSecretBPointer = addrCounter++;
		final int addrQortalPartnerAddressPointer = addrCounter++;
		final int addrMessageSenderPointer = addrCounter++;

		final int addrTradeMessagePartnerBitcoinPKHOffset = addrCounter++;
		final int addrPartnerBitcoinPKHPointer = addrCounter++;
		final int addrTradeMessageHashOfSecretAOffset = addrCounter++;
		final int addrHashOfSecretAPointer = addrCounter++;

		final int addrRedeemMessageSecretBOffset = addrCounter++;
		final int addrRedeemMessageReceiveAddressOffset = addrCounter++;

		final int addrMessageDataPointer = addrCounter++;
		final int addrMessageDataLength = addrCounter++;

		final int addrEndOfConstants = addrCounter;

		// Variables

		final int addrCreatorAddress1 = addrCounter++;
		final int addrCreatorAddress2 = addrCounter++;
		final int addrCreatorAddress3 = addrCounter++;
		final int addrCreatorAddress4 = addrCounter++;

		final int addrQortalPartnerAddress1 = addrCounter++;
		final int addrQortalPartnerAddress2 = addrCounter++;
		final int addrQortalPartnerAddress3 = addrCounter++;
		final int addrQortalPartnerAddress4 = addrCounter++;

		final int addrLockTimeA = addrCounter++;
		final int addrLockTimeB = addrCounter++;
		final int addrRefundTimeout = addrCounter++;
		final int addrRefundTimestamp = addrCounter++;
		final int addrLastTxnTimestamp = addrCounter++;
		final int addrBlockTimestamp = addrCounter++;
		final int addrTxnType = addrCounter++;
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

		final int addrPartnerBitcoinPKH = addrCounter;
		addrCounter += 4;

		final int addrMode = addrCounter++;
		assert addrMode == MODE_VALUE_OFFSET : "MODE_VALUE_OFFSET does not match addrMode";

		// Data segment
		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);

		// AT creator's trade Qortal address, decoded from Base58
		assert dataByteBuffer.position() == addrCreatorTradeAddress1 * MachineState.VALUE_SIZE : "addrCreatorTradeAddress1 incorrect";
		byte[] creatorTradeAddressBytes = Base58.decode(creatorTradeAddress);
		dataByteBuffer.put(Bytes.ensureCapacity(creatorTradeAddressBytes, 32, 0));

		// Bitcoin public key hash
		assert dataByteBuffer.position() == addrBitcoinPublicKeyHash * MachineState.VALUE_SIZE : "addrBitcoinPublicKeyHash incorrect";
		dataByteBuffer.put(Bytes.ensureCapacity(bitcoinPublicKeyHash, 32, 0));

		// Hash of secret-B
		assert dataByteBuffer.position() == addrHashOfSecretB * MachineState.VALUE_SIZE : "addrHashOfSecretB incorrect";
		dataByteBuffer.put(Bytes.ensureCapacity(hashOfSecretB, 32, 0));

		// Redeem Qort amount
		assert dataByteBuffer.position() == addrQortAmount * MachineState.VALUE_SIZE : "addrQortAmount incorrect";
		dataByteBuffer.putLong(qortAmount);

		// Expected Bitcoin amount
		assert dataByteBuffer.position() == addrBitcoinAmount * MachineState.VALUE_SIZE : "addrBitcoinAmount incorrect";
		dataByteBuffer.putLong(bitcoinAmount);

		// Suggested trade timeout (minutes)
		assert dataByteBuffer.position() == addrTradeTimeout * MachineState.VALUE_SIZE : "addrTradeTimeout incorrect";
		dataByteBuffer.putLong(tradeTimeout);

		// We're only interested in MESSAGE transactions
		assert dataByteBuffer.position() == addrMessageTxnType * MachineState.VALUE_SIZE : "addrMessageTxnType incorrect";
		dataByteBuffer.putLong(API.ATTransactionType.MESSAGE.value);

		// Expected length of 'trade' MESSAGE data from AT creator
		assert dataByteBuffer.position() == addrExpectedTradeMessageLength * MachineState.VALUE_SIZE : "addrExpectedTradeMessageLength incorrect";
		dataByteBuffer.putLong(TRADE_MESSAGE_LENGTH);

		// Expected length of 'redeem' MESSAGE data from trade partner
		assert dataByteBuffer.position() == addrExpectedRedeemMessageLength * MachineState.VALUE_SIZE : "addrExpectedRedeemMessageLength incorrect";
		dataByteBuffer.putLong(REDEEM_MESSAGE_LENGTH);

		// Index into data segment of AT creator's address, used by GET_B_IND
		assert dataByteBuffer.position() == addrCreatorAddressPointer * MachineState.VALUE_SIZE : "addrCreatorAddressPointer incorrect";
		dataByteBuffer.putLong(addrCreatorAddress1);

		// Index into data segment of hash of secret B, used by GET_B_IND
		assert dataByteBuffer.position() == addrHashOfSecretBPointer * MachineState.VALUE_SIZE : "addrHashOfSecretBPointer incorrect";
		dataByteBuffer.putLong(addrHashOfSecretB);

		// Index into data segment of recipient address, used by SET_B_IND
		assert dataByteBuffer.position() == addrQortalPartnerAddressPointer * MachineState.VALUE_SIZE : "addrQortalPartnerAddressPointer incorrect";
		dataByteBuffer.putLong(addrQortalPartnerAddress1);

		// Index into data segment of (temporary) transaction's sender's address, used by GET_B_IND
		assert dataByteBuffer.position() == addrMessageSenderPointer * MachineState.VALUE_SIZE : "addrMessageSenderPointer incorrect";
		dataByteBuffer.putLong(addrMessageSender1);

		// Offset into 'trade' MESSAGE data payload for extracting partner's Bitcoin PKH
		assert dataByteBuffer.position() == addrTradeMessagePartnerBitcoinPKHOffset * MachineState.VALUE_SIZE : "addrTradeMessagePartnerBitcoinPKHOffset incorrect";
		dataByteBuffer.putLong(32L);

		// Index into data segment of partner's Bitcoin PKH, used by GET_B_IND
		assert dataByteBuffer.position() == addrPartnerBitcoinPKHPointer * MachineState.VALUE_SIZE : "addrPartnerBitcoinPKHPointer incorrect";
		dataByteBuffer.putLong(addrPartnerBitcoinPKH);

		// Offset into 'trade' MESSAGE data payload for extracting hash-of-secret-A
		assert dataByteBuffer.position() == addrTradeMessageHashOfSecretAOffset * MachineState.VALUE_SIZE : "addrTradeMessageHashOfSecretAOffset incorrect";
		dataByteBuffer.putLong(64L);

		// Index into data segment to hash of secret A, used by GET_B_IND
		assert dataByteBuffer.position() == addrHashOfSecretAPointer * MachineState.VALUE_SIZE : "addrHashOfSecretAPointer incorrect";
		dataByteBuffer.putLong(addrHashOfSecretA);

		// Offset into 'redeem' MESSAGE data payload for extracting secret-B
		assert dataByteBuffer.position() == addrRedeemMessageSecretBOffset * MachineState.VALUE_SIZE : "addrRedeemMessageSecretBOffset incorrect";
		dataByteBuffer.putLong(32L);

		// Offset into 'redeem' MESSAGE data payload for extracting Qortal receive address
		assert dataByteBuffer.position() == addrRedeemMessageReceiveAddressOffset * MachineState.VALUE_SIZE : "addrRedeemMessageReceiveAddressOffset incorrect";
		dataByteBuffer.putLong(64L);

		// Source location and length for hashing any passed secret
		assert dataByteBuffer.position() == addrMessageDataPointer * MachineState.VALUE_SIZE : "addrMessageDataPointer incorrect";
		dataByteBuffer.putLong(addrMessageData);
		assert dataByteBuffer.position() == addrMessageDataLength * MachineState.VALUE_SIZE : "addrMessageDataLength incorrect";
		dataByteBuffer.putLong(32L);

		assert dataByteBuffer.position() == addrEndOfConstants * MachineState.VALUE_SIZE : "dataByteBuffer position not at end of constants";

		// Code labels
		Integer labelRefund = null;

		Integer labelTradeTxnLoop = null;
		Integer labelCheckTradeTxn = null;

		Integer labelCheckNonRefundTradeTxn = null;
		Integer labelTradeTxnExtract = null;
		Integer labelRedeemTxnLoop = null;
		Integer labelCheckRedeemTxn = null;
		Integer labelCheckRedeemTxnSender = null;
		Integer labelCheckSecretB = null;
		Integer labelPayout = null;

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(768);

		// Two-pass version
		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				/* Initialization */

				// Use AT creation 'timestamp' as starting point for finding transactions sent to AT
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxnTimestamp));

				// Load B register with AT creator's address so we can save it into addrCreatorAddress1-4
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_CREATOR_INTO_B));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrCreatorAddressPointer));

				// Set restart position to after this opcode
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				/* Loop, waiting for message from AT creator's trade address containing trade partner details, or AT owner's address to cancel offer */

				/* Transaction processing loop */
				labelTradeTxnLoop = codeByteBuffer.position();

				// Find next transaction (if any) to this AT since the last one (referenced by addrLastTxnTimestamp)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxnTimestamp));
				// If no transaction found, A will be zero. If A is zero, set addrResult to 1, otherwise 0.
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrResult));
				// If addrResult is zero (i.e. A is non-zero, transaction was found) then go check transaction
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelCheckTradeTxn)));
				// Stop and wait for next block
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				/* Check transaction */
				labelCheckTradeTxn = codeByteBuffer.position();

				// Update our 'last found transaction's timestamp' using 'timestamp' from transaction
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, addrLastTxnTimestamp));
				// Extract transaction type (message/payment) from transaction and save type in addrTxnType
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, addrTxnType));
				// If transaction type is not MESSAGE type then go look for another transaction
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrTxnType, addrMessageTxnType, calcOffset(codeByteBuffer, labelTradeTxnLoop)));

				/* Check transaction's sender. We're expecting AT creator's trade address. */

				// Extract sender address from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrMessageSender1 (as pointed to by addrMessageSenderPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageSenderPointer));
				// Compare each part of transaction's sender's address with expected address. If they don't match, look for another transaction.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender1, addrCreatorTradeAddress1, calcOffset(codeByteBuffer, labelTradeTxnLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender2, addrCreatorTradeAddress2, calcOffset(codeByteBuffer, labelTradeTxnLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender3, addrCreatorTradeAddress3, calcOffset(codeByteBuffer, labelTradeTxnLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender4, addrCreatorTradeAddress4, calcOffset(codeByteBuffer, labelTradeTxnLoop)));

				/* Extract trade partner info from message */

				// Extract message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrQortalPartnerAddress1 (as pointed to by addrQortalPartnerAddressPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrQortalPartnerAddressPointer));
				// Compare each of partner address with creator's address (for offer-cancel scenario). If they don't match, assume address is trade partner.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalPartnerAddress1, addrCreatorAddress1, calcOffset(codeByteBuffer, labelCheckNonRefundTradeTxn)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalPartnerAddress2, addrCreatorAddress2, calcOffset(codeByteBuffer, labelCheckNonRefundTradeTxn)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalPartnerAddress3, addrCreatorAddress3, calcOffset(codeByteBuffer, labelCheckNonRefundTradeTxn)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrQortalPartnerAddress4, addrCreatorAddress4, calcOffset(codeByteBuffer, labelCheckNonRefundTradeTxn)));
				// Partner address is AT creator's address, so cancel offer and finish.
				codeByteBuffer.put(OpCode.SET_VAL.compile(addrMode, Mode.CANCELLED.value));
				// We're finished forever (finishing auto-refunds remaining balance to AT creator)
				codeByteBuffer.put(OpCode.FIN_IMD.compile());

				/* Possible switch-to-trade-mode message */
				labelCheckNonRefundTradeTxn = codeByteBuffer.position();

				// Not offer-cancel scenario so check we received expected number of message bytes
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(QortalFunctionCode.GET_MESSAGE_LENGTH_FROM_TX_IN_A.value, addrMessageLength));
				// If message length matches, branch to info extraction code
				codeByteBuffer.put(OpCode.BEQ_DAT.compile(addrMessageLength, addrExpectedTradeMessageLength, calcOffset(codeByteBuffer, labelTradeTxnExtract)));
				// Message length didn't match - go back to finding another 'trade' MESSAGE transaction
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelTradeTxnLoop == null ? 0 : labelTradeTxnLoop));

				/* Extracting info from 'trade' MESSAGE transaction */
				labelTradeTxnExtract = codeByteBuffer.position();

				// Message is expected length, grab next 32 bytes
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, addrTradeMessagePartnerBitcoinPKHOffset));

				// Extract partner's Bitcoin PKH (we only really use values from B1-B3)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrPartnerBitcoinPKHPointer));
				// Also extract lockTimeB
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B4, addrLockTimeB));

				// Grab next 32 bytes
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, addrTradeMessageHashOfSecretAOffset));

				// Extract hash-of-secret-a (we only really use values from B1-B3)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrHashOfSecretAPointer));
				// Extract lockTimeA (from B4)
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B4, addrLockTimeA));

				// Calculate trade refund timeout: (lockTimeA - lockTimeB) / 2 / 60
				codeByteBuffer.put(OpCode.SET_DAT.compile(addrRefundTimeout, addrLockTimeA)); // refundTimeout = lockTimeA
				codeByteBuffer.put(OpCode.SUB_DAT.compile(addrRefundTimeout, addrLockTimeB)); // refundTimeout -= lockTimeB
				codeByteBuffer.put(OpCode.DIV_VAL.compile(addrRefundTimeout, 2L * 60L)); // refundTimeout /= 2 * 60
				// Calculate trade timeout refund 'timestamp' by adding addrRefundTimeout minutes to this transaction's 'timestamp', then save into addrRefundTimestamp
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.ADD_MINUTES_TO_TIMESTAMP, addrRefundTimestamp, addrLastTxnTimestamp, addrRefundTimeout));

				/* We are in 'trade mode' */
				codeByteBuffer.put(OpCode.SET_VAL.compile(addrMode, Mode.TRADING.value));

				// Set restart position to after this opcode
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				/* Loop, waiting for trade timeout or 'redeem' MESSAGE from Qortal trade partner */

				// Fetch current block 'timestamp'
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_BLOCK_TIMESTAMP, addrBlockTimestamp));
				// If we're not past refund 'timestamp' then look for next transaction
				codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBlockTimestamp, addrRefundTimestamp, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));
				// We're past refund 'timestamp' so go refund everything back to AT creator
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRefund == null ? 0 : labelRefund));

				/* Transaction processing loop */
				labelRedeemTxnLoop = codeByteBuffer.position();

				// Find next transaction to this AT since the last one (if any)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxnTimestamp));
				// If no transaction found, A will be zero. If A is zero, set addrComparator to 1, otherwise 0.
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrResult));
				// If addrResult is zero (i.e. A is non-zero, transaction was found) then go check transaction
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelCheckRedeemTxn)));
				// Stop and wait for next block
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				/* Check transaction */
				labelCheckRedeemTxn = codeByteBuffer.position();

				// Update our 'last found transaction's timestamp' using 'timestamp' from transaction
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, addrLastTxnTimestamp));
				// Extract transaction type (message/payment) from transaction and save type in addrTxnType
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, addrTxnType));
				// If transaction type is not MESSAGE type then go look for another transaction
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrTxnType, addrMessageTxnType, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));

				/* Check message payload length */
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(QortalFunctionCode.GET_MESSAGE_LENGTH_FROM_TX_IN_A.value, addrMessageLength));
				// If message length matches, branch to sender checking code
				codeByteBuffer.put(OpCode.BEQ_DAT.compile(addrMessageLength, addrExpectedRedeemMessageLength, calcOffset(codeByteBuffer, labelCheckRedeemTxnSender)));
				// Message length didn't match - go back to finding another 'redeem' MESSAGE transaction
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRedeemTxnLoop == null ? 0 : labelRedeemTxnLoop));

				/* Check transaction's sender */
				labelCheckRedeemTxnSender = codeByteBuffer.position();

				// Extract sender address from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrMessageSender1 (as pointed to by addrMessageSenderPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageSenderPointer));
				// Compare each part of transaction's sender's address with expected address. If they don't match, look for another transaction.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender1, addrQortalPartnerAddress1, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender2, addrQortalPartnerAddress2, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender3, addrQortalPartnerAddress3, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender4, addrQortalPartnerAddress4, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));

				/* Check 'secret-A' in transaction's message */

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
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRedeemTxnLoop == null ? 0 : labelRedeemTxnLoop));

				/* Check 'secret-B' in transaction's message */

				labelCheckSecretB = codeByteBuffer.position();

				// Extract secret-B from next 32 bytes of message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, addrRedeemMessageSecretBOffset));
				// Save B register into data segment starting at addrMessageData (as pointed to by addrMessageDataPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageDataPointer));
				// Load B register with expected hash result (as pointed to by addrHashOfSecretBPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrHashOfSecretBPointer));
				// Perform HASH160 using source data at addrMessageData. (Location and length specified via addrMessageDataPointer and addrMessageDataLength).
				// Save the equality result (1 if they match, 0 otherwise) into addrResult.
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.CHECK_HASH160_WITH_B, addrResult, addrMessageDataPointer, addrMessageDataLength));
				// If hashes don't match, addrResult will be zero so go find another transaction
				codeByteBuffer.put(OpCode.BNZ_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelPayout)));
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRedeemTxnLoop == null ? 0 : labelRedeemTxnLoop));

				/* Success! Pay arranged amount to receive address */
				labelPayout = codeByteBuffer.position();

				// Extract Qortal receive address from next 32 bytes of message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, addrRedeemMessageReceiveAddressOffset));
				// Pay AT's balance to recipient
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrQortAmount));
				// Set redeemed mode
				codeByteBuffer.put(OpCode.SET_VAL.compile(addrMode, Mode.REDEEMED.value));
				// We're finished forever (finishing auto-refunds remaining balance to AT creator)
				codeByteBuffer.put(OpCode.FIN_IMD.compile());

				// Fall-through to refunding any remaining balance back to AT creator

				/* Refund balance back to AT creator */
				labelRefund = codeByteBuffer.position();

				// Set refunded mode
				codeByteBuffer.put(OpCode.SET_VAL.compile(addrMode, Mode.REFUNDED.value));
				// We're finished forever (finishing auto-refunds remaining balance to AT creator)
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
		ATStateData atStateData = repository.getATRepository().getLatestATState(atData.getATAddress());
		return populateTradeData(repository, atData.getCreatorPublicKey(), atStateData);
	}

	/**
	 * Returns CrossChainTradeData with useful info extracted from AT.
	 * 
	 * @param repository
	 * @param atAddress
	 * @throws DataException
	 */
	public static CrossChainTradeData populateTradeData(Repository repository, ATStateData atStateData) throws DataException {
		byte[] creatorPublicKey = repository.getATRepository().getCreatorPublicKey(atStateData.getATAddress());
		return populateTradeData(repository, creatorPublicKey, atStateData);
	}

	/**
	 * Returns CrossChainTradeData with useful info extracted from AT.
	 * 
	 * @param repository
	 * @param atAddress
	 * @throws DataException
	 */
	public static CrossChainTradeData populateTradeData(Repository repository, byte[] creatorPublicKey, ATStateData atStateData) throws DataException {
		String atAddress = atStateData.getATAddress();

		QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();
		byte[] stateData = atStateData.getStateData();
		byte[] dataBytes = MachineState.extractDataBytes(loggerFactory, stateData);

		CrossChainTradeData tradeData = new CrossChainTradeData();
		tradeData.qortalAtAddress = atAddress;
		tradeData.qortalCreator = Crypto.toAddress(creatorPublicKey);
		tradeData.creationTimestamp = atStateData.getCreation();

		Account atAccount = new Account(repository, atAddress);
		tradeData.qortBalance = atAccount.getConfirmedBalance(Asset.QORT);

		ByteBuffer dataByteBuffer = ByteBuffer.wrap(dataBytes);
		byte[] addressBytes = new byte[25];

		/* Constants */

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

		// Redeem payout
		tradeData.qortAmount = dataByteBuffer.getLong();

		// Expected BTC amount
		tradeData.expectedBitcoin = dataByteBuffer.getLong();

		// Trade timeout
		tradeData.tradeTimeout = (int) dataByteBuffer.getLong();

		// Skip MESSAGE transaction type
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip expected 'trade' message length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip expected 'redeem' message length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to creator's address
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to hash-of-secret-B
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to partner's Qortal trade address
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to message sender
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip 'trade' message data offset for recipient's bitcoin PKH
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to partner's bitcoin PKH
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip 'trade' message data offset for hash-of-secret-A
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to hash-of-secret-A
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip 'redeem' message data offset for secret-B
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip 'redeem' message data offset for partner's Qortal receive address
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to message data
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip message data length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		/* End of constants / begin variables */

		// Skip AT creator's address
		dataByteBuffer.position(dataByteBuffer.position() + 8 * 4);

		// Partner's trade address (if present)
		dataByteBuffer.get(addressBytes);
		String qortalRecipient = Base58.encode(addressBytes);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - addressBytes.length);

		// Potential lockTimeA (if in trade mode)
		int lockTimeA = (int) dataByteBuffer.getLong();

		// Potential lockTimeB (if in trade mode)
		int lockTimeB = (int) dataByteBuffer.getLong();

		// AT refund timeout (probably only useful for debugging)
		int refundTimeout = (int) dataByteBuffer.getLong();

		// Trade-mode refund timestamp (AT 'timestamp' converted to Qortal block height)
		long tradeRefundTimestamp = dataByteBuffer.getLong();

		// Skip last transaction timestamp
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

		// Potential hash160 of secret A
		byte[] hashOfSecretA = new byte[20];
		dataByteBuffer.get(hashOfSecretA);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - hashOfSecretA.length); // skip to 32 bytes

		// Potential partner's Bitcoin PKH
		byte[] recipientBitcoinPKH = new byte[20];
		dataByteBuffer.get(recipientBitcoinPKH);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - recipientBitcoinPKH.length); // skip to 32 bytes

		long modeValue = dataByteBuffer.getLong();
		Mode mode = Mode.valueOf((int) (modeValue & 0xffL));

		if (mode != null && mode != Mode.OFFERING) {
			tradeData.mode = mode;
			tradeData.refundTimeout = refundTimeout;
			tradeData.tradeRefundHeight = new Timestamp(tradeRefundTimestamp).blockHeight;
			tradeData.qortalPartnerAddress = qortalRecipient;
			tradeData.hashOfSecretA = hashOfSecretA;
			tradeData.partnerBitcoinPKH = recipientBitcoinPKH;
			tradeData.lockTimeA = lockTimeA;
			tradeData.lockTimeB = lockTimeB;
		} else {
			tradeData.mode = Mode.OFFERING;
		}

		return tradeData;
	}

	/** Returns 'offer' MESSAGE payload for trade partner to send to AT creator's trade address. */
	public static byte[] buildOfferMessage(byte[] recipientBitcoinPKH, byte[] hashOfSecretA, int lockTimeA) {
		byte[] lockTimeABytes = BitTwiddling.toBEByteArray((long) lockTimeA);
		return Bytes.concat(recipientBitcoinPKH, hashOfSecretA, lockTimeABytes);
	}

	/** Returns info extracted from 'offer' MESSAGE payload sent by trade partner to AT creator's trade address, or null if not valid. */
	public static OfferMessageData extractOfferMessageData(byte[] messageData) {
		if (messageData == null || messageData.length != OFFER_MESSAGE_LENGTH)
			return null;

		OfferMessageData offerMessageData = new OfferMessageData();
		offerMessageData.partnerBitcoinPKH = Arrays.copyOfRange(messageData, 0, 20);
		offerMessageData.hashOfSecretA = Arrays.copyOfRange(messageData, 20, 40);
		offerMessageData.lockTimeA = BitTwiddling.longFromBEBytes(messageData, 40);

		return offerMessageData;
	}

	/** Returns 'trade' MESSAGE payload for AT creator to send to AT. */
	public static byte[] buildTradeMessage(String partnerQortalTradeAddress, byte[] partnerBitcoinPKH, byte[] hashOfSecretA, int lockTimeA, int lockTimeB) {
		byte[] data = new byte[TRADE_MESSAGE_LENGTH];
		byte[] recipientQortalAddressBytes = Base58.decode(partnerQortalTradeAddress);
		byte[] lockTimeABytes = BitTwiddling.toBEByteArray((long) lockTimeA);
		byte[] lockTimeBBytes = BitTwiddling.toBEByteArray((long) lockTimeB);

		System.arraycopy(recipientQortalAddressBytes, 0, data, 0, recipientQortalAddressBytes.length);
		System.arraycopy(partnerBitcoinPKH, 0, data, 32, partnerBitcoinPKH.length);
		System.arraycopy(lockTimeBBytes, 0, data, 56, lockTimeBBytes.length);
		System.arraycopy(hashOfSecretA, 0, data, 64, hashOfSecretA.length);
		System.arraycopy(lockTimeABytes, 0, data, 88, lockTimeABytes.length);

		return data;
	}

	/** Returns 'cancel' MESSAGE payload for AT creator to cancel trade AT. */
	public static byte[] buildCancelMessage(String creatorQortalAddress) {
		byte[] data = new byte[CANCEL_MESSAGE_LENGTH];
		byte[] creatorQortalAddressBytes = Base58.decode(creatorQortalAddress);

		System.arraycopy(creatorQortalAddressBytes, 0, data, 0, creatorQortalAddressBytes.length);

		return data;
	}

	/** Returns 'redeem' MESSAGE payload for trade partner/ to send to AT. */
	public static byte[] buildRedeemMessage(byte[] secretA, byte[] secretB, String qortalReceiveAddress) {
		byte[] data = new byte[REDEEM_MESSAGE_LENGTH];
		byte[] qortalReceiveAddressBytes = Base58.decode(qortalReceiveAddress);

		System.arraycopy(secretA, 0, data, 0, secretA.length);
		System.arraycopy(secretB, 0, data, 32, secretB.length);
		System.arraycopy(qortalReceiveAddressBytes, 0, data, 64, qortalReceiveAddressBytes.length);

		return data;
	}

	/** Returns P2SH-B lockTime (epoch seconds) based on trade partner's 'offer' MESSAGE timestamp and P2SH-A locktime. */
	public static int calcLockTimeB(long offerMessageTimestamp, int lockTimeA) {
		// lockTimeB is halfway between offerMessageTimesamp and lockTimeA
		return (int) ((lockTimeA + (offerMessageTimestamp / 1000L)) / 2L);
	}

	public static byte[] findSecretA(Repository repository, CrossChainTradeData crossChainTradeData) throws DataException {
		String atAddress = crossChainTradeData.qortalAtAddress;
		String redeemerAddress = crossChainTradeData.qortalPartnerAddress;

		List<MessageTransactionData> messageTransactionsData = repository.getTransactionRepository().getMessagesByRecipient(atAddress, null, null, null);
		if (messageTransactionsData == null)
			return null;

		// Find 'redeem' message
		for (MessageTransactionData messageTransactionData : messageTransactionsData) {
			// Check message payload type/encryption
			if (messageTransactionData.isText() || messageTransactionData.isEncrypted())
				continue;

			// Check message payload size
			byte[] messageData = messageTransactionData.getData();
			if (messageData.length != REDEEM_MESSAGE_LENGTH)
				// Wrong payload length
				continue;

			// Check sender
			if (!Crypto.toAddress(messageTransactionData.getSenderPublicKey()).equals(redeemerAddress))
				// Wrong sender;
				continue;

			// Extract both secretA & secretB
			byte[] secretA = new byte[32];
			System.arraycopy(messageData, 0, secretA, 0, secretA.length);
			byte[] secretB = new byte[32];
			System.arraycopy(messageData, 32, secretB, 0, secretB.length);

			byte[] hashOfSecretA = Crypto.hash160(secretA);
			if (!Arrays.equals(hashOfSecretA, crossChainTradeData.hashOfSecretA))
				continue;

			byte[] hashOfSecretB = Crypto.hash160(secretB);
			if (!Arrays.equals(hashOfSecretB, crossChainTradeData.hashOfSecretB))
				continue;

			return secretA;
		}

		return null;
	}

}
