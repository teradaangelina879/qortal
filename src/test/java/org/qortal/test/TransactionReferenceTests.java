package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;

import java.util.Random;

import static org.junit.Assert.assertEquals;

public class TransactionReferenceTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testInvalidRandomReferenceBeforeFeatureTrigger() throws DataException {
        Random random = new Random();

        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

            byte[] randomPrivateKey = new byte[32];
            random.nextBytes(randomPrivateKey);
            PrivateKeyAccount recipient = new PrivateKeyAccount(repository, randomPrivateKey);

            // Create payment transaction data
            TransactionData paymentTransactionData = new PaymentTransactionData(TestTransaction.generateBase(alice), recipient.getAddress(), 100000L);

            // Set random reference
            byte[] randomReference = new byte[64];
            random.nextBytes(randomReference);
            paymentTransactionData.setReference(randomReference);

            Transaction paymentTransaction = Transaction.fromData(repository, paymentTransactionData);

            // Transaction should be invalid due to random reference
            Transaction.ValidationResult validationResult = paymentTransaction.isValidUnconfirmed();
            assertEquals(Transaction.ValidationResult.INVALID_REFERENCE, validationResult);
        }
    }

    @Test
    public void testValidRandomReferenceAfterFeatureTrigger() throws DataException {
        Common.useSettings("test-settings-v2-disable-reference.json");
        Random random = new Random();

        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

            byte[] randomPrivateKey = new byte[32];
            random.nextBytes(randomPrivateKey);
            PrivateKeyAccount recipient = new PrivateKeyAccount(repository, randomPrivateKey);

            // Create payment transaction data
            TransactionData paymentTransactionData = new PaymentTransactionData(TestTransaction.generateBase(alice), recipient.getAddress(), 100000L);

            // Set random reference
            byte[] randomReference = new byte[64];
            random.nextBytes(randomReference);
            paymentTransactionData.setReference(randomReference);

            Transaction paymentTransaction = Transaction.fromData(repository, paymentTransactionData);

            // Transaction should be valid, even with random reference, because reference checking is now disabled
            Transaction.ValidationResult validationResult = paymentTransaction.isValidUnconfirmed();
            assertEquals(Transaction.ValidationResult.OK, validationResult);
            TransactionUtils.signAndImportValid(repository, paymentTransactionData, alice);
        }
    }

}
