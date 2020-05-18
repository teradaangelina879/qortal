package org.qortal.test.group;

import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.CreateGroupTransactionData;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
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

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testCreateGroupWithExistingName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String groupName = "test-group";
			String description = "test group";

			final boolean isOpen = false;
			ApprovalThreshold approvalThreshold = ApprovalThreshold.PCT40;
			int minimumBlockDelay = 10;
			int maximumBlockDelay = 1440;

			CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(alice), groupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// duplicate
			String duplicateGroupName = "TEST-gr0up";
			transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(alice), duplicateGroupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);
			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);

			ValidationResult result = transaction.importAsUnconfirmed();
			assertTrue("Transaction should be invalid", ValidationResult.OK != result);
		}
	}

}
