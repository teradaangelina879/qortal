package org.qortal.transform.transaction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;

import org.qortal.account.GenesisAccount;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.GenesisTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Serialization;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

public class GenesisTransactionTransformer extends TransactionTransformer {

	// Note that Genesis transactions don't require reference, fee or signature

	// Property lengths
	private static final int RECIPIENT_LENGTH = ADDRESS_LENGTH;
	private static final int AMOUNT_LENGTH = LONG_LENGTH;
	private static final int ASSET_ID_LENGTH = LONG_LENGTH;

	private static final int TOTAL_LENGTH = TYPE_LENGTH + TIMESTAMP_LENGTH + RECIPIENT_LENGTH + AMOUNT_LENGTH + ASSET_ID_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.GENESIS.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("recipient", TransformationType.ADDRESS);
		layout.add("amount", TransformationType.AMOUNT);
		layout.add("asset ID", TransformationType.LONG);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		String recipient = Serialization.deserializeAddress(byteBuffer);

		BigDecimal amount = Serialization.deserializeBigDecimal(byteBuffer);

		long assetId = byteBuffer.getLong();

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, null, GenesisAccount.PUBLIC_KEY, BigDecimal.ZERO, null);

		return new GenesisTransactionData(baseTransactionData, recipient, amount, assetId);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		return TOTAL_LENGTH;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			GenesisTransactionData genesisTransactionData = (GenesisTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(genesisTransactionData.getType().value));

			bytes.write(Longs.toByteArray(genesisTransactionData.getTimestamp()));

			Serialization.serializeAddress(bytes, genesisTransactionData.getRecipient());

			Serialization.serializeBigDecimal(bytes, genesisTransactionData.getAmount());

			bytes.write(Longs.toByteArray(genesisTransactionData.getAssetId()));

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
