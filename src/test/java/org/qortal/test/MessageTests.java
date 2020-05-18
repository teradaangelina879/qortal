package org.qortal.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.asset.Asset;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.MessageTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;

import static org.junit.Assert.*;

public class MessageTests extends Common {

	private static final int version = 3;
	private static final String recipient = Common.getTestAccount(null, "bob").getAddress();


	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void validityTests() throws DataException {
		// with recipient, with amount
		assertTrue(isValid(Group.NO_GROUP, recipient, 123L, Asset.QORT));

		// with recipient, no amount
		assertTrue(isValid(Group.NO_GROUP, recipient, 0L, null));

		// no recipient (message to group), no amount
		assertTrue(isValid(Group.NO_GROUP, null, 0L, null));

		// can't have amount if no recipient!
		assertFalse(isValid(Group.NO_GROUP, null, 123L, Asset.QORT));

		// Alice is part of group 1
		assertTrue(isValid(1, null, 0L, null));

		int newGroupId;
		try (final Repository repository = RepositoryManager.getRepository()) {
			newGroupId = GroupUtils.createGroup(repository, "chloe", "non-alice-group", false, ApprovalThreshold.ONE, 10, 1440);
		}

		// Alice is not part of new group
		assertFalse(isValid(newGroupId, null, 0L, null));
	}

	@Test
	public void withRecipentNoAmount() throws DataException {
		testMessage(Group.NO_GROUP, recipient, 0L, null);
	}

	@Test
	public void withRecipentWithAmount() throws DataException {
		testMessage(Group.NO_GROUP, recipient, 123L, Asset.QORT);
	}

	@Test
	public void noRecipentNoAmount() throws DataException {
		testMessage(Group.NO_GROUP, null, 0L, null);
	}

	@Test
	public void noRecipentNoAmountWithGroup() throws DataException {
		testMessage(1, null, 0L, null);
	}

	@Test
	public void serializationTests() throws DataException, TransformationException {
		// with recipient, with amount
		testSerialization(recipient, 123L, Asset.QORT);

		// with recipient, no amount
		testSerialization(recipient, 0L, null);

		// no recipient (message to group), no amount
		testSerialization(null, 0L, null);
	}

	private boolean isValid(int txGroupId, String recipient, long amount, Long assetId) throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			MessageTransactionData transactionData = new MessageTransactionData(TestTransaction.generateBase(alice, txGroupId),
					version, recipient, amount, assetId, new byte[1], false, false);

			Transaction transaction = new MessageTransaction(repository, transactionData);

			return transaction.isValidUnconfirmed() == ValidationResult.OK;
		}
	}

	private void testMessage(int txGroupId, String recipient, long amount, Long assetId) throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			MessageTransactionData transactionData = new MessageTransactionData(TestTransaction.generateBase(alice, txGroupId),
					version, recipient, amount, assetId, new byte[1], false, false);

			TransactionUtils.signAndMint(repository, transactionData, alice);

			BlockUtils.orphanLastBlock(repository);
		}
	}

	private void testSerialization(String recipient, long amount, Long assetId) throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			MessageTransactionData expectedTransactionData = new MessageTransactionData(TestTransaction.generateBase(alice),
					version, recipient, amount, assetId, new byte[1], false, false);

			Transaction transaction = new MessageTransaction(repository, expectedTransactionData);
			transaction.sign(alice);

			MessageTransactionTransformer.getDataLength(expectedTransactionData);
			byte[] transactionBytes = MessageTransactionTransformer.toBytes(expectedTransactionData);

			TransactionData transactionData = TransactionTransformer.fromBytes(transactionBytes);
			assertEquals(TransactionType.MESSAGE, transactionData.getType());

			MessageTransactionData actualTransactionData = (MessageTransactionData) transactionData;

			assertEquals(expectedTransactionData.getRecipient(), actualTransactionData.getRecipient());
			assertEquals(expectedTransactionData.getAmount(), actualTransactionData.getAmount());
			assertEquals(expectedTransactionData.getAssetId(), actualTransactionData.getAssetId());
		}
	}

}
