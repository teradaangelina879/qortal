package org.qortal.test.common.transaction;

import java.math.BigDecimal;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferAssetTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class TransferAssetTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		String recipient = account.getAddress();
		final long assetId = Asset.QORT;
		BigDecimal amount = BigDecimal.valueOf(123);

		return new TransferAssetTransactionData(generateBase(account), recipient, amount, assetId);
	}

}
