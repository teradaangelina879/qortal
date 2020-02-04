package org.qortal.test.common.transaction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.MultiPaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class MultiPaymentTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		String recipient = account.getAddress();
		final long assetId = Asset.QORT;
		BigDecimal amount = BigDecimal.valueOf(123L);

		List<PaymentData> payments = new ArrayList<>();
		payments.add(new PaymentData(recipient, assetId, amount));

		return new MultiPaymentTransactionData(generateBase(account), payments);
	}

}
