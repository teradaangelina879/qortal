package org.qora.test;

import org.junit.Test;
import org.qora.account.PrivateKeyAccount;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryManager;
import org.qora.test.common.Common;
import org.qora.test.common.TransactionUtils;
import org.qora.transaction.Transaction;
import org.qora.transform.TransformationException;
import org.qora.transform.transaction.TransactionTransformer;
import org.qora.utils.Base58;

import com.google.common.hash.HashCode;

import io.druid.extendedset.intset.ConciseSet;

import static org.junit.Assert.*;

import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.junit.Before;

public class SerializationTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testTransactions() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount signingAccount = Common.getTestAccount(repository, "alice");

			// Check serialization/deserialization of transactions of every type (except GENESIS, ACCOUNT_FLAGS or AT)
			for (Transaction.TransactionType txType : Transaction.TransactionType.values()) {
				switch (txType) {
					case GENESIS:
					case ACCOUNT_FLAGS:
					case AT:
					case DELEGATION:
					case SUPERNODE:
					case AIRDROP:
						continue;

					default:
						// fall-through
				}

				TransactionData transactionData = TransactionUtils.randomTransaction(repository, signingAccount, txType, true);
				Transaction transaction = Transaction.fromData(repository, transactionData);
				transaction.sign(signingAccount);

				final int claimedLength = TransactionTransformer.getDataLength(transactionData);
				byte[] serializedTransaction = TransactionTransformer.toBytes(transactionData);
				assertEquals(String.format("Serialized %s transaction length differs from declared length", txType.name()), claimedLength, serializedTransaction.length);

				TransactionData deserializedTransactionData = TransactionTransformer.fromBytes(serializedTransaction);
				// Re-sign
				Transaction deserializedTransaction = Transaction.fromData(repository, deserializedTransactionData);
				deserializedTransaction.sign(signingAccount);
				assertEquals(String.format("Deserialized %s transaction signature differs", txType.name()), Base58.encode(transactionData.getSignature()), Base58.encode(deserializedTransactionData.getSignature()));

				// Re-serialize to check new length and bytes
				final int reclaimedLength = TransactionTransformer.getDataLength(deserializedTransactionData);
				assertEquals(String.format("Reserialized %s transaction declared length differs", txType.name()), claimedLength, reclaimedLength);

				byte[] reserializedTransaction = TransactionTransformer.toBytes(deserializedTransactionData);
				assertEquals(String.format("Reserialized %s transaction bytes differ", txType.name()), HashCode.fromBytes(serializedTransaction).toString(), HashCode.fromBytes(reserializedTransaction).toString());
			}
		}
	}

	@Test
	public void testAccountBitMap() {
		Random random = new Random();

		final int numberOfKnownAccounts = random.nextInt(1 << 17) + 1;
		System.out.println(String.format("Number of known accounts: %d", numberOfKnownAccounts));

		// 5% to 15%
		final int numberOfAccountsToEncode = random.nextInt((numberOfKnownAccounts / 10) + numberOfKnownAccounts / 5);
		System.out.println(String.format("Number of accounts to encode: %d", numberOfAccountsToEncode));

		final int bitsLength = numberOfKnownAccounts;
		System.out.println(String.format("Bits to fit all accounts: %d", bitsLength));

		// Enough bytes to fit at least bitsLength bits
		final int byteLength = ((bitsLength - 1) >> 3) + 1;
		System.out.println(String.format("Uncompressed bytes to fit all accounts: %d", byteLength));

		List<Integer> accountIndexes = new LinkedList<>();
		for (int i = 0; i < numberOfAccountsToEncode; ++i) {
			final int accountIndex = random.nextInt(numberOfKnownAccounts);
			accountIndexes.add(accountIndex);
			// System.out.println(String.format("Account [%d]: %d / 0x%08x", i, accountIndex, accountIndex));
		}

		ConciseSet compressedSet = new ConciseSet();

		for (Integer accountIndex : accountIndexes)
			compressedSet.add(accountIndex);

		int compressedSize = compressedSet.toByteBuffer().remaining();

		System.out.println(String.format("Out of %d known accounts, encoding %d accounts needs %d uncompressed bytes but only %d compressed bytes",
				numberOfKnownAccounts, numberOfAccountsToEncode, byteLength, compressedSize));
	}

	@Test
	public void benchmarkBitSetCompression() {
		Random random = new Random();

		System.out.println(String.format("Known Online UncompressedBitSet UncompressedIntList Compressed"));

		for (int run = 0; run < 1000; ++run) {
			final int numberOfKnownAccounts = random.nextInt(1 << 17) + 1;

			// 5% to 25%
			final int numberOfAccountsToEncode = random.nextInt((numberOfKnownAccounts / 20) + numberOfKnownAccounts / 5);

			// Enough uncompressed bytes to fit one bit per known account
			final int uncompressedBitSetSize = ((numberOfKnownAccounts - 1) >> 3) + 1;

			// Size of a simple list of ints
			final int uncompressedIntListSize = numberOfAccountsToEncode * 4;

			ConciseSet compressedSet = new ConciseSet();

			for (int i = 0; i < numberOfAccountsToEncode; ++i)
				compressedSet.add(random.nextInt(numberOfKnownAccounts));

			int compressedSize = compressedSet.toByteBuffer().remaining();

			System.out.println(String.format("%d %d %d %d %d", numberOfKnownAccounts, numberOfAccountsToEncode, uncompressedBitSetSize, uncompressedIntListSize, compressedSize));
		}
	}

}