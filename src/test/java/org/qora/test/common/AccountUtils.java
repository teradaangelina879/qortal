package org.qora.test.common;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.qora.account.PrivateKeyAccount;
import org.qora.data.transaction.BaseTransactionData;
import org.qora.data.transaction.PaymentTransactionData;
import org.qora.data.transaction.RewardShareTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.group.Group;
import org.qora.repository.DataException;
import org.qora.repository.Repository;

public class AccountUtils {

	public static final int txGroupId = Group.NO_GROUP;
	public static final BigDecimal fee = BigDecimal.ONE.setScale(8);

	public static void pay(Repository repository, String sender, String recipient, BigDecimal amount) throws DataException {
		PrivateKeyAccount sendingAccount = Common.getTestAccount(repository, sender);
		PrivateKeyAccount recipientAccount = Common.getTestAccount(repository, recipient);

		byte[] reference = sendingAccount.getLastReference();
		long timestamp = repository.getTransactionRepository().fromSignature(reference).getTimestamp() + 1;

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, sendingAccount.getPublicKey(), fee, null);
		TransactionData transactionData = new PaymentTransactionData(baseTransactionData, recipientAccount.getAddress(), amount);

		TransactionUtils.signAndMint(repository, transactionData, sendingAccount);
	}

	public static TransactionData createRewardShare(Repository repository, String minter, String recipient, BigDecimal sharePercent) throws DataException {
		PrivateKeyAccount mintingAccount = Common.getTestAccount(repository, minter);
		PrivateKeyAccount recipientAccount = Common.getTestAccount(repository, recipient);

		byte[] reference = mintingAccount.getLastReference();
		long timestamp = repository.getTransactionRepository().fromSignature(reference).getTimestamp() + 1;

		byte[] rewardSharePrivateKey = mintingAccount.getSharedSecret(recipientAccount.getPublicKey());
		PrivateKeyAccount rewardShareAccount = new PrivateKeyAccount(null, rewardSharePrivateKey);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, mintingAccount.getPublicKey(), fee, null);
		TransactionData transactionData = new RewardShareTransactionData(baseTransactionData, recipientAccount.getAddress(), rewardShareAccount.getPublicKey(), sharePercent);

		return transactionData;
	}

	public static byte[] rewardShare(Repository repository, String minter, String recipient, BigDecimal sharePercent) throws DataException {
		TransactionData transactionData = createRewardShare(repository, minter, recipient, sharePercent);

		PrivateKeyAccount rewardShareAccount = Common.getTestAccount(repository, minter);
		TransactionUtils.signAndMint(repository, transactionData, rewardShareAccount);

		PrivateKeyAccount recipientAccount = Common.getTestAccount(repository, recipient);
		byte[] rewardSharePrivateKey = rewardShareAccount.getSharedSecret(recipientAccount.getPublicKey());

		return rewardSharePrivateKey;
	}

	public static Map<String, Map<Long, BigDecimal>> getBalances(Repository repository, long... assetIds) throws DataException {
		Map<String, Map<Long, BigDecimal>> balances = new HashMap<>();

		for (TestAccount account : Common.getTestAccounts(repository))
			for (Long assetId : assetIds) {
				BigDecimal balance = account.getConfirmedBalance(assetId);

				balances.compute(account.accountName, (key, value) -> {
					if (value == null)
						value = new HashMap<Long, BigDecimal>();

					value.put(assetId, balance);

					return value;
				});
			}

		return balances;
	}

	public static BigDecimal getBalance(Repository repository, String accountName, long assetId) throws DataException {
		return Common.getTestAccount(repository, accountName).getConfirmedBalance(assetId);
	}

	public static void assertBalance(Repository repository, String accountName, long assetId, BigDecimal expectedBalance) throws DataException {
		BigDecimal actualBalance = getBalance(repository, accountName, assetId);
		String assetName = repository.getAssetRepository().fromAssetId(assetId).getName();

		Common.assertEqualBigDecimals(String.format("%s's %s [%d] balance incorrect", accountName, assetName, assetId), expectedBalance, actualBalance);
	}

}
