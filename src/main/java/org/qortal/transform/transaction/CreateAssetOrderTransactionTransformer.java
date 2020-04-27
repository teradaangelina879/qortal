package org.qortal.transform.transaction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;

import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.CreateAssetOrderTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Serialization;

import com.google.common.primitives.Longs;

public class CreateAssetOrderTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int ASSET_ID_LENGTH = LONG_LENGTH;
	private static final int AMOUNT_LENGTH = 12; // Not standard BIG_DECIMAL_LENGTH

	private static final int EXTRAS_LENGTH = (ASSET_ID_LENGTH + AMOUNT_LENGTH) * 2;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.CREATE_ASSET_ORDER.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("reference", TransformationType.SIGNATURE);
		layout.add("order creator's public key", TransformationType.PUBLIC_KEY);
		layout.add("ID of asset of offer", TransformationType.LONG);
		layout.add("ID of asset wanted", TransformationType.LONG);
		layout.add("amount of asset on offer", TransformationType.ASSET_QUANTITY);
		layout.add("trade price in (lowest-assetID asset)/(highest-assetID asset)", TransformationType.ASSET_QUANTITY);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();

		byte[] reference = new byte[REFERENCE_LENGTH];
		byteBuffer.get(reference);

		byte[] creatorPublicKey = Serialization.deserializePublicKey(byteBuffer);

		long haveAssetId = byteBuffer.getLong();

		long wantAssetId = byteBuffer.getLong();

		BigDecimal amount = Serialization.deserializeBigDecimal(byteBuffer, AMOUNT_LENGTH);

		BigDecimal price = Serialization.deserializeBigDecimal(byteBuffer, AMOUNT_LENGTH);

		BigDecimal fee = Serialization.deserializeBigDecimal(byteBuffer);

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, creatorPublicKey, fee, signature);

		return new CreateAssetOrderTransactionData(baseTransactionData, haveAssetId, wantAssetId, amount, price);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		return getBaseLength(transactionData) + EXTRAS_LENGTH;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			CreateAssetOrderTransactionData createOrderTransactionData = (CreateAssetOrderTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Longs.toByteArray(createOrderTransactionData.getHaveAssetId()));

			bytes.write(Longs.toByteArray(createOrderTransactionData.getWantAssetId()));

			Serialization.serializeBigDecimal(bytes, createOrderTransactionData.getAmount(), AMOUNT_LENGTH);

			Serialization.serializeBigDecimal(bytes, createOrderTransactionData.getPrice(), AMOUNT_LENGTH);

			Serialization.serializeBigDecimal(bytes, createOrderTransactionData.getFee());

			if (createOrderTransactionData.getSignature() != null)
				bytes.write(createOrderTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
