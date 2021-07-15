package org.qortal.test.common.transaction;

import java.util.ArrayList;
import java.util.List;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.DataType;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Amounts;

public class ArbitraryTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final int version = 4;
		final ArbitraryTransactionData.Service service = ArbitraryTransactionData.Service.ARBITRARY_DATA;
		final int nonce = 0; // Version 4 doesn't need a nonce
		final int size = 0; // Version 4 doesn't need a size
		final String name = null; // Version 4 doesn't need a name
		final ArbitraryTransactionData.Method method = ArbitraryTransactionData.Method.PUT; // Version 4 doesn't need a method
		final byte[] secret = null; // Version 4 doesn't need a secret
        final ArbitraryTransactionData.Compression compression = ArbitraryTransactionData.Compression.NONE; // Version 4 doesn't use compression
		final byte[] chunkHashes = null; // Version 4 doesn't use chunk hashes

		byte[] data = new byte[1024];
		random.nextBytes(data);

		DataType dataType = DataType.RAW_DATA;

		String recipient = account.getAddress();
		final long assetId = Asset.QORT;
		long amount = 123L * Amounts.MULTIPLIER;

		List<PaymentData> payments = new ArrayList<>();
		payments.add(new PaymentData(recipient, assetId, amount));

		return new ArbitraryTransactionData(generateBase(account), version, service, nonce, size, name, method,
				secret, compression, data, dataType, chunkHashes, payments);
	}

}
