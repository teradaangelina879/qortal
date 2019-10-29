package org.qora.transform.transaction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;

import org.qora.block.BlockChain;
import org.qora.data.transaction.BaseTransactionData;
import org.qora.data.transaction.RewardShareTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.transaction.Transaction.TransactionType;
import org.qora.transform.TransformationException;
import org.qora.utils.Serialization;

public class RewardShareTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int TARGET_LENGTH = ADDRESS_LENGTH;
	private static final int REWARD_SHARE_LENGTH = BIG_DECIMAL_LENGTH;

	private static final int EXTRAS_LENGTH = TARGET_LENGTH + PUBLIC_KEY_LENGTH + REWARD_SHARE_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.REWARD_SHARE.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("reference", TransformationType.SIGNATURE);
		layout.add("minter's public key", TransformationType.PUBLIC_KEY);
		layout.add("recipient account's address", TransformationType.ADDRESS);
		layout.add("reward-share public key", TransformationType.PUBLIC_KEY);
		layout.add("recipient's percentage share of block rewards", TransformationType.AMOUNT);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = 0;
		if (timestamp >= BlockChain.getInstance().getQoraV2Timestamp())
			txGroupId = byteBuffer.getInt();

		byte[] reference = new byte[REFERENCE_LENGTH];
		byteBuffer.get(reference);

		byte[] minterPublicKey = Serialization.deserializePublicKey(byteBuffer);

		String recipient = Serialization.deserializeAddress(byteBuffer);

		byte[] rewardSharePublicKey = Serialization.deserializePublicKey(byteBuffer);

		BigDecimal sharePercent = Serialization.deserializeBigDecimal(byteBuffer);

		BigDecimal fee = Serialization.deserializeBigDecimal(byteBuffer);

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, minterPublicKey, fee, signature);

		return new RewardShareTransactionData(baseTransactionData, recipient, rewardSharePublicKey, sharePercent);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		return getBaseLength(transactionData) + EXTRAS_LENGTH;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			RewardShareTransactionData rewardShareTransactionData = (RewardShareTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			Serialization.serializeAddress(bytes, rewardShareTransactionData.getRecipient());

			bytes.write(rewardShareTransactionData.getRewardSharePublicKey());

			Serialization.serializeBigDecimal(bytes, rewardShareTransactionData.getSharePercent());

			Serialization.serializeBigDecimal(bytes, rewardShareTransactionData.getFee());

			if (rewardShareTransactionData.getSignature() != null)
				bytes.write(rewardShareTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
