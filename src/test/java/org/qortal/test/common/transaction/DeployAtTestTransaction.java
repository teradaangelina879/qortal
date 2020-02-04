package org.qortal.test.common.transaction;

import java.math.BigDecimal;
import java.util.Random;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class DeployAtTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		Random random = new Random();

		String name = "test AT " + random.nextInt(1_000_000);
		String description = "random test AT";
		String atType = "random AT type";
		String tags = "random AT tags";
		byte[] creationBytes = new byte[1024];
		random.nextBytes(creationBytes);
		BigDecimal amount = BigDecimal.valueOf(123);
		final long assetId = Asset.QORT;

		return new DeployAtTransactionData(generateBase(account), name, description, atType, tags, creationBytes, amount, assetId);
	}

}
