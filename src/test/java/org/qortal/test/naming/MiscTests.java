package org.qortal.test.naming;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.UpdateNameTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;

public class MiscTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testGetRecentNames() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";

			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			TransactionUtils.signAndMint(repository, transactionData, alice);

			List<String> recentNames = repository.getNameRepository().getRecentNames(0L);

			assertNotNull(recentNames);
			assertFalse(recentNames.isEmpty());
		}
	}

	@Test
	public void testUpdateName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";

			TransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			TransactionUtils.signAndMint(repository, transactionData, alice);

			String newName = "new-name";
			String newData = "";
			transactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, newName, newData);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// Check old name no longer exists
			assertFalse(repository.getNameRepository().nameExists(name));

			// Check new name exists
			assertTrue(repository.getNameRepository().nameExists(newName));

			// orphan and recheck
			BlockUtils.orphanLastBlock(repository);

			// Check new name no longer exists
			assertFalse(repository.getNameRepository().nameExists(newName));

			// Check old name exists again
			assertTrue(repository.getNameRepository().nameExists(name));
		}
	}

	// Test that reverting using previous UPDATE_NAME works as expected
	@Test
	public void testDoubleUpdateName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";

			TransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			TransactionUtils.signAndMint(repository, transactionData, alice);

			String newName = "new-name";
			String newData = "";
			transactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, newName, newData);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// Check old name no longer exists
			assertFalse(repository.getNameRepository().nameExists(name));

			// Check new name exists
			assertTrue(repository.getNameRepository().nameExists(newName));

			String newestName = "newest-name";
			String newestData = "abc";
			transactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), newName, newestName, newestData);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// Check previous name no longer exists
			assertFalse(repository.getNameRepository().nameExists(newName));

			// Check newest name exists
			assertTrue(repository.getNameRepository().nameExists(newestName));

			// orphan and recheck
			BlockUtils.orphanLastBlock(repository);

			// Check newest name no longer exists
			assertFalse(repository.getNameRepository().nameExists(newestName));

			// Check previous name exists again
			assertTrue(repository.getNameRepository().nameExists(newName));

			// orphan and recheck
			BlockUtils.orphanLastBlock(repository);

			// Check new name no longer exists
			assertFalse(repository.getNameRepository().nameExists(newName));

			// Check original name exists again
			assertTrue(repository.getNameRepository().nameExists(name));
		}
	}

	@Test
	public void testUpdateData() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";
			String data = "{}";

			TransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			String newName = "";
			String newData = "new-data";
			transactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, newName, newData);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// Check name still exists
			assertTrue(repository.getNameRepository().nameExists(name));

			// Check data is correct
			assertEquals(newData, repository.getNameRepository().fromName(name).getData());

			// orphan and recheck
			BlockUtils.orphanLastBlock(repository);

			// Check name still exists
			assertTrue(repository.getNameRepository().nameExists(name));

			// Check old data restored
			assertEquals(data, repository.getNameRepository().fromName(name).getData());
		}
	}

	// test trying to register same name twice
	@Test
	public void testDuplicateRegisterName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";

			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// duplicate
			transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);

			ValidationResult result = transaction.importAsUnconfirmed();
			assertTrue("Transaction should be invalid", ValidationResult.OK != result);
		}
	}

	// test register then trying to update another name to existing name
	@Test
	public void testUpdateToExistingName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";

			TransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			TransactionUtils.signAndMint(repository, transactionData, alice);

			String newName = "new-name";
			String newData = "";
			transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), newName, newData);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// we shouldn't be able to update name to existing name
			transactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), newName, name, newData);
			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);

			ValidationResult result = transaction.importAsUnconfirmed();
			assertTrue("Transaction should be invalid", ValidationResult.OK != result);
		}
	}

}
