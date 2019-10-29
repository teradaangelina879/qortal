package org.qora.test.common.transaction;

import java.math.BigDecimal;

import org.qora.account.PrivateKeyAccount;
import org.qora.data.transaction.RewardShareTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;

public class RewardShareTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		String recipient = account.getAddress();
		byte[] rewardSharePublicKey = account.getRewardSharePrivateKey(account.getPublicKey());
		BigDecimal sharePercent = BigDecimal.valueOf(50);

		return new RewardShareTransactionData(generateBase(account), recipient, rewardSharePublicKey, sharePercent);
	}

}
