package org.qortal.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.PresenceTransactionData;
import org.qortal.data.transaction.PresenceTransactionData.PresenceType;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.transaction.PresenceTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;

import com.google.common.primitives.Longs;

import static org.junit.Assert.*;

public class PresenceTests extends Common {

	private PrivateKeyAccount signer;
	private Repository repository;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();

		this.repository = RepositoryManager.getRepository();
		this.signer = Common.getTestAccount(this.repository, "bob");
	}

	@After
	public void afterTest() throws DataException {
		if (this.repository != null)
			this.repository.close();

		this.repository = null;
	}

	@Test
	public void validityTests() throws DataException {
		long timestamp = System.currentTimeMillis();
		byte[] timestampBytes = Longs.toByteArray(timestamp);

		byte[] timestampSignature = this.signer.sign(timestampBytes);

		assertTrue(isValid(Group.NO_GROUP, this.signer, timestamp, timestampSignature));
	}

	private boolean isValid(int txGroupId, PrivateKeyAccount signer, long timestamp, byte[] timestampSignature) throws DataException {
		int nonce = 0;

		byte[] reference = signer.getLastReference();
		byte[] creatorPublicKey = signer.getPublicKey();
		long fee = 0L;

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, creatorPublicKey, fee, null);
		PresenceTransactionData transactionData = new PresenceTransactionData(baseTransactionData, nonce, PresenceType.REWARD_SHARE, timestampSignature);

		Transaction transaction = new PresenceTransaction(this.repository, transactionData);

		return transaction.isValidUnconfirmed() == ValidationResult.OK;
	}

}
