package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;

import org.qortal.data.PaymentData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.*;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ArbitraryTransactionTests extends Common {

	private static final int version = 4;
	private static final String recipient = Common.getTestAccount(null, "bob").getAddress();


	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testDifficultyCalculation() throws DataException {

		try (final Repository repository = RepositoryManager.getRepository()) {

			TestAccount alice = Common.getTestAccount(repository, "alice");
			ArbitraryTransactionData.DataType dataType = ArbitraryTransactionData.DataType.DATA_HASH;
			ArbitraryTransactionData.Service service = ArbitraryTransactionData.Service.ARBITRARY_DATA;
			ArbitraryTransactionData.Method method = ArbitraryTransactionData.Method.PUT;
			ArbitraryTransactionData.Compression compression = ArbitraryTransactionData.Compression.NONE;
			List<PaymentData> payments = new ArrayList<>();

			ArbitraryTransactionData transactionData = new ArbitraryTransactionData(TestTransaction.generateBase(alice),
					5, service, 0, 0, null, null, method,
                    null, compression, null, dataType, null, payments);

			ArbitraryTransaction transaction = (ArbitraryTransaction) Transaction.fromData(repository, transactionData);
			assertEquals(12, transaction.difficultyForFileSize(1));
			assertEquals(12, transaction.difficultyForFileSize(5123456));
			assertEquals(12, transaction.difficultyForFileSize(74 * 1024 * 1024));
			assertEquals(13, transaction.difficultyForFileSize(75 * 1024 * 1024));
			assertEquals(13, transaction.difficultyForFileSize(144 * 1024 * 1024));
			assertEquals(14, transaction.difficultyForFileSize(145 * 1024 * 1024));
			assertEquals(14, transaction.difficultyForFileSize(214 * 1024 * 1024));
			assertEquals(15, transaction.difficultyForFileSize(215 * 1024 * 1024));
			assertEquals(15, transaction.difficultyForFileSize(289 * 1024 * 1024));
			assertEquals(16, transaction.difficultyForFileSize(290 * 1024 * 1024));
			assertEquals(16, transaction.difficultyForFileSize(359 * 1024 * 1024));
			assertEquals(17, transaction.difficultyForFileSize(360 * 1024 * 1024));
			assertEquals(17, transaction.difficultyForFileSize(429 * 1024 * 1024));
			assertEquals(18, transaction.difficultyForFileSize(430 * 1024 * 1024));
			assertEquals(18, transaction.difficultyForFileSize(499 * 1024 * 1024));
			assertEquals(19, transaction.difficultyForFileSize(500 * 1024 * 1024));

		}
	}

}
