package org.qortal.transform.transaction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.qortal.account.NullAccount;
import org.qortal.data.transaction.ATTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Serialization;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

public class AtTransactionTransformer extends TransactionTransformer {

	protected static final TransactionLayout layout = null;

	// Property lengths
	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		byte[] reference = new byte[REFERENCE_LENGTH];
		byteBuffer.get(reference);

		String atAddress = Serialization.deserializeAddress(byteBuffer);

		String recipient = Serialization.deserializeAddress(byteBuffer);

		// Assume PAYMENT-type, as these are the only ones used in ACCTs
		// TODO: add support for MESSAGE-type
		long assetId = byteBuffer.getLong();

		long amount = byteBuffer.getLong();

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, reference, NullAccount.PUBLIC_KEY, fee, signature);

		return new ATTransactionData(baseTransactionData, atAddress, recipient, amount, assetId);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		return TYPE_LENGTH + TIMESTAMP_LENGTH + REFERENCE_LENGTH + ADDRESS_LENGTH + ADDRESS_LENGTH +
				ASSET_ID_LENGTH + AMOUNT_LENGTH + FEE_LENGTH + SIGNATURE_LENGTH;
	}

	// Used for generating fake transaction signatures
	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			ATTransactionData atTransactionData = (ATTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(atTransactionData.getType().value));
			bytes.write(Longs.toByteArray(atTransactionData.getTimestamp()));
			bytes.write(atTransactionData.getReference());

			Serialization.serializeAddress(bytes, atTransactionData.getATAddress());

			Serialization.serializeAddress(bytes, atTransactionData.getRecipient());

			byte[] message = atTransactionData.getMessage();

			if (message != null) {
				// MESSAGE-type
				bytes.write(Ints.toByteArray(message.length));
				bytes.write(message);
			} else {
				// PAYMENT-type
				bytes.write(Longs.toByteArray(atTransactionData.getAssetId()));
				bytes.write(Longs.toByteArray(atTransactionData.getAmount()));
			}

			bytes.write(Longs.toByteArray(atTransactionData.getFee()));

			if (atTransactionData.getSignature() != null)
				bytes.write(atTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
