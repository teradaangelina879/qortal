package org.qortal.transform.transaction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;

import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Serialization;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

public class MessageTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int RECIPIENT_LENGTH = ADDRESS_LENGTH;
	private static final int AMOUNT_LENGTH = BIG_DECIMAL_LENGTH;
	private static final int ASSET_ID_LENGTH = LONG_LENGTH;
	private static final int DATA_SIZE_LENGTH = INT_LENGTH;
	private static final int IS_TEXT_LENGTH = BOOLEAN_LENGTH;
	private static final int IS_ENCRYPTED_LENGTH = BOOLEAN_LENGTH;

	private static final int EXTRAS_LENGTH = RECIPIENT_LENGTH + ASSET_ID_LENGTH + AMOUNT_LENGTH + DATA_SIZE_LENGTH + IS_ENCRYPTED_LENGTH + IS_TEXT_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.MESSAGE.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("reference", TransformationType.SIGNATURE);
		layout.add("sender's public key", TransformationType.PUBLIC_KEY);
		layout.add("recipient", TransformationType.ADDRESS);
		layout.add("asset ID of payment", TransformationType.LONG);
		layout.add("payment (can be zero)", TransformationType.AMOUNT);
		layout.add("message length", TransformationType.INT);
		layout.add("message", TransformationType.DATA);
		layout.add("is message encrypted?", TransformationType.BOOLEAN);
		layout.add("is message text?", TransformationType.BOOLEAN);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int version = Transaction.getVersionByTimestamp(timestamp);

		int txGroupId = byteBuffer.getInt();

		byte[] reference = new byte[REFERENCE_LENGTH];
		byteBuffer.get(reference);

		byte[] senderPublicKey = Serialization.deserializePublicKey(byteBuffer);

		String recipient = Serialization.deserializeAddress(byteBuffer);

		long assetId = byteBuffer.getLong();

		BigDecimal amount = Serialization.deserializeBigDecimal(byteBuffer);

		int dataSize = byteBuffer.getInt();
		// Don't allow invalid dataSize here to avoid run-time issues
		if (dataSize > MessageTransaction.MAX_DATA_SIZE)
			throw new TransformationException("MessageTransaction data size too large");

		byte[] data = new byte[dataSize];
		byteBuffer.get(data);

		boolean isEncrypted = byteBuffer.get() != 0;

		boolean isText = byteBuffer.get() != 0;

		BigDecimal fee = Serialization.deserializeBigDecimal(byteBuffer);

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, senderPublicKey, fee, signature);

		return new MessageTransactionData(baseTransactionData, version, recipient, assetId, amount, data, isText, isEncrypted);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		MessageTransactionData messageTransactionData = (MessageTransactionData) transactionData;

		return getBaseLength(transactionData) + EXTRAS_LENGTH + messageTransactionData.getData().length;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			MessageTransactionData messageTransactionData = (MessageTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			Serialization.serializeAddress(bytes, messageTransactionData.getRecipient());

			bytes.write(Longs.toByteArray(messageTransactionData.getAssetId()));

			Serialization.serializeBigDecimal(bytes, messageTransactionData.getAmount());

			bytes.write(Ints.toByteArray(messageTransactionData.getData().length));

			bytes.write(messageTransactionData.getData());

			bytes.write((byte) (messageTransactionData.getIsEncrypted() ? 1 : 0));

			bytes.write((byte) (messageTransactionData.getIsText() ? 1 : 0));

			Serialization.serializeBigDecimal(bytes, messageTransactionData.getFee());

			if (messageTransactionData.getSignature() != null)
				bytes.write(messageTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
