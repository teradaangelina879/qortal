package org.qortal.test.common.transaction;

import java.math.BigDecimal;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class PaymentTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		String recipient = account.getAddress();
		BigDecimal amount = BigDecimal.valueOf(123L);

		return new PaymentTransactionData(generateBase(account), recipient, amount);
	}

}
