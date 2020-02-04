package org.qortal.test.common.transaction;

import java.math.BigDecimal;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.GenesisTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class GenesisTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		String recipient = account.getAddress();
		BigDecimal amount = BigDecimal.valueOf(123);

		return new GenesisTransactionData(generateBase(account), recipient, amount);
	}

}
