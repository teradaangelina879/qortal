package org.qortal.controller.repository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.PublicKeyAccount;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.BuyNameTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.UpdateNameTransactionData;
import org.qortal.naming.Name;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.utils.Base58;

import java.util.*;

public class NamesDatabaseIntegrityCheck {

    private static final Logger LOGGER = LogManager.getLogger(NamesDatabaseIntegrityCheck.class);

    private static final List<TransactionType> REGISTER_NAME_TX_TYPE = Collections.singletonList(TransactionType.REGISTER_NAME);
    private static final List<TransactionType> UPDATE_NAME_TX_TYPE = Collections.singletonList(TransactionType.UPDATE_NAME);
    private static final List<TransactionType> BUY_NAME_TX_TYPE = Collections.singletonList(TransactionType.BUY_NAME);

    private List<RegisterNameTransactionData> registerNameTransactions;
    private List<UpdateNameTransactionData> updateNameTransactions;
    private List<BuyNameTransactionData> buyNameTransactions;

    public void runIntegrityCheck() {
        boolean integrityCheckFailed = false;
        boolean corrected = false;
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Fetch all the (confirmed) name-related transactions
            this.fetchRegisterNameTransactions(repository);
            this.fetchUpdateNameTransactions(repository);
            this.fetchBuyNameTransactions(repository);

            // Loop through each REGISTER_NAME txn signature and request the full transaction data
            for (RegisterNameTransactionData registerNameTransactionData : this.registerNameTransactions) {
                String registeredName = registerNameTransactionData.getName();
                NameData nameData = repository.getNameRepository().fromName(registeredName);

                // Check to see if this name has been updated or bought at any point
                TransactionData latestUpdate = this.fetchLatestModificationTransactionInvolvingName(registeredName);
                if (latestUpdate == null) {
                    // Name was never updated once registered
                    // We expect this name to still be registered to this transaction's creator

                    if (nameData == null) {
                        LOGGER.info("Error: registered name {} doesn't exist in Names table. Adding...", registeredName);
                        integrityCheckFailed = true;

                        // Register the name
                        Name name = new Name(repository, registerNameTransactionData);
                        name.register();
                        repository.saveChanges();
                        corrected = true;
                        continue;
                    }
                    else {
                        //LOGGER.info("Registered name {} is correctly registered", registeredName);
                    }

                    // Check the owner is correct
                    PublicKeyAccount creator = new PublicKeyAccount(repository, registerNameTransactionData.getCreatorPublicKey());
                    if (!Objects.equals(creator.getAddress(), nameData.getOwner())) {
                        LOGGER.info("Error: registered name {} is owned by {}, but it should be {}",
                                registeredName, nameData.getOwner(), creator.getAddress());
                        integrityCheckFailed = true;

                        // FUTURE: Fix the name's owner if we ever see the above log entry
                    }
                    else {
                        //LOGGER.info("Registered name {} has the correct owner", registeredName);
                    }
                }
                else {
                    // Check if owner is correct after update

                    // Check for name updates
                    if (latestUpdate instanceof UpdateNameTransactionData) {
                        UpdateNameTransactionData updateNameTransactionData = (UpdateNameTransactionData) latestUpdate;
                        PublicKeyAccount creator = new PublicKeyAccount(repository, updateNameTransactionData.getCreatorPublicKey());

                        // When this name is the "new name", we expect the current owner to match the txn creator
                        if (Objects.equals(updateNameTransactionData.getNewName(), registeredName)) {
                            if (!Objects.equals(creator.getAddress(), nameData.getOwner())) {
                                LOGGER.info("Error: registered name {} is owned by {}, but it should be {}",
                                        registeredName, nameData.getOwner(), creator.getAddress());
                                integrityCheckFailed = true;

                                // FUTURE: Fix the name's owner if we ever see the above log entry
                            } else {
                                //LOGGER.info("Registered name {} has the correct owner after being updated", registeredName);
                            }
                        }

                        // When this name is the old name, we expect the "new name"'s owner to match the txn creator
                        // The old name will then be unregistered, or re-registered.
                        // FUTURE: check database integrity for names that have been updated and then the original name re-registered
                        else if (Objects.equals(updateNameTransactionData.getName(), registeredName)) {
                            NameData newNameData = repository.getNameRepository().fromName(updateNameTransactionData.getNewName());
                            if (!Objects.equals(creator.getAddress(), newNameData.getOwner())) {
                                LOGGER.info("Error: registered name {} is owned by {}, but it should be {}",
                                        updateNameTransactionData.getNewName(), newNameData.getOwner(), creator.getAddress());
                                integrityCheckFailed = true;

                                // FUTURE: Fix the name's owner if we ever see the above log entry
                            } else {
                                //LOGGER.info("Registered name {} has the correct owner after being updated", updateNameTransactionData.getNewName());
                            }
                        }

                        else {
                            LOGGER.info("Unhandled update case for name {}", registeredName);
                        }
                    }

                    // Check for name sales
                    else if (latestUpdate instanceof BuyNameTransactionData) {
                        BuyNameTransactionData buyNameTransactionData = (BuyNameTransactionData) latestUpdate;
                        PublicKeyAccount creator = new PublicKeyAccount(repository, buyNameTransactionData.getCreatorPublicKey());
                        if (!Objects.equals(creator.getAddress(), nameData.getOwner())) {
                            LOGGER.info("Error: registered name {} is owned by {}, but it should be {}",
                                    registeredName, nameData.getOwner(), creator.getAddress());
                            integrityCheckFailed = true;

                            // FUTURE: Fix the name's owner if we ever see the above log entry
                        } else {
                            //LOGGER.info("Registered name {} has the correct owner after being bought", registeredName);
                        }
                    }

                    else {
                        LOGGER.info("Unhandled case for name {}", registeredName);
                    }

                }

            }

        } catch (DataException e) {
            LOGGER.warn(String.format("Repository issue trying to trim online accounts signatures: %s", e.getMessage()));
            integrityCheckFailed = true;
        }

        if (integrityCheckFailed) {
            if (corrected) {
                LOGGER.info("Registered names database integrity check failed, but corrections were made. If this " +
                        "problem persists after restarting the node, you may need to switch to a recent bootstrap.");
            }
            else {
                LOGGER.info("Registered names database integrity check failed. Bootstrapping is recommended.");
            }
        } else {
            LOGGER.info("Registered names database integrity check passed.");
        }
    }

    private void fetchRegisterNameTransactions(Repository repository) throws DataException {
        List<RegisterNameTransactionData> registerNameTransactions = new ArrayList<>();

        // Fetch all the confirmed REGISTER_NAME transaction signatures
        List<byte[]> registerNameSigs = repository.getTransactionRepository().getSignaturesMatchingCriteria(
                null, null, null, REGISTER_NAME_TX_TYPE, null, null,
                ConfirmationStatus.CONFIRMED, null, null, false);

        for (byte[] signature : registerNameSigs) {
            // LOGGER.info("Fetching REGISTER_NAME transaction from signature {}...", Base58.encode(signature));

            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            if (!(transactionData instanceof RegisterNameTransactionData)) {
                LOGGER.info("REGISTER_NAME transaction signature {} not found", Base58.encode(signature));
                continue;
            }
            RegisterNameTransactionData registerNameTransactionData = (RegisterNameTransactionData) transactionData;
            registerNameTransactions.add(registerNameTransactionData);
        }
        this.registerNameTransactions = registerNameTransactions;
    }

    private void fetchUpdateNameTransactions(Repository repository) throws DataException {
        List<UpdateNameTransactionData> updateNameTransactions = new ArrayList<>();

        // Fetch all the confirmed REGISTER_NAME transaction signatures
        List<byte[]> updateNameSigs = repository.getTransactionRepository().getSignaturesMatchingCriteria(
                null, null, null, UPDATE_NAME_TX_TYPE, null, null,
                ConfirmationStatus.CONFIRMED, null, null, false);

        for (byte[] signature : updateNameSigs) {
            // LOGGER.info("Fetching UPDATE_NAME transaction from signature {}...", Base58.encode(signature));

            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            if (!(transactionData instanceof UpdateNameTransactionData)) {
                LOGGER.info("UPDATE_NAME transaction signature {} not found", Base58.encode(signature));
                continue;
            }
            UpdateNameTransactionData updateNameTransactionData = (UpdateNameTransactionData) transactionData;
            updateNameTransactions.add(updateNameTransactionData);
        }
        this.updateNameTransactions = updateNameTransactions;
    }

    private void fetchBuyNameTransactions(Repository repository) throws DataException {
        List<BuyNameTransactionData> buyNameTransactions = new ArrayList<>();

        // Fetch all the confirmed REGISTER_NAME transaction signatures
        List<byte[]> buyNameSigs = repository.getTransactionRepository().getSignaturesMatchingCriteria(
                null, null, null, BUY_NAME_TX_TYPE, null, null,
                ConfirmationStatus.CONFIRMED, null, null, false);

        for (byte[] signature : buyNameSigs) {
            // LOGGER.info("Fetching BUY_NAME transaction from signature {}...", Base58.encode(signature));

            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            if (!(transactionData instanceof BuyNameTransactionData)) {
                LOGGER.info("BUY_NAME transaction signature {} not found", Base58.encode(signature));
                continue;
            }
            BuyNameTransactionData buyNameTransactionData = (BuyNameTransactionData) transactionData;
            buyNameTransactions.add(buyNameTransactionData);
        }
        this.buyNameTransactions = buyNameTransactions;
    }

    private List<UpdateNameTransactionData> fetchUpdateTransactionsInvolvingName(String registeredName) {
        List<UpdateNameTransactionData> matchedTransactions = new ArrayList<>();

        for (UpdateNameTransactionData updateNameTransactionData : this.updateNameTransactions) {
            if (Objects.equals(updateNameTransactionData.getName(), registeredName) ||
                    Objects.equals(updateNameTransactionData.getNewName(), registeredName)) {

                matchedTransactions.add(updateNameTransactionData);
            }
        }
        return matchedTransactions;
    }

    private List<BuyNameTransactionData> fetchBuyTransactionsInvolvingName(String registeredName) {
        List<BuyNameTransactionData> matchedTransactions = new ArrayList<>();

        for (BuyNameTransactionData buyNameTransactionData : this.buyNameTransactions) {
            if (Objects.equals(buyNameTransactionData.getName(), registeredName)) {

                matchedTransactions.add(buyNameTransactionData);
            }
        }
        return matchedTransactions;
    }

    private TransactionData fetchLatestModificationTransactionInvolvingName(String registeredName) {
        List<TransactionData> latestTransactions = new ArrayList<>();

        List<UpdateNameTransactionData> updates = this.fetchUpdateTransactionsInvolvingName(registeredName);
        List<BuyNameTransactionData> buys = this.fetchBuyTransactionsInvolvingName(registeredName);

        // Get the latest updates for this name
        UpdateNameTransactionData latestUpdateToName = updates.stream()
                .filter(update -> update.getNewName().equals(registeredName))
                .max(Comparator.comparing(UpdateNameTransactionData::getTimestamp))
                .orElse(null);
        if (latestUpdateToName != null) {
            latestTransactions.add(latestUpdateToName);
        }

        UpdateNameTransactionData latestUpdateFromName = updates.stream()
                .filter(update -> update.getName().equals(registeredName))
                .max(Comparator.comparing(UpdateNameTransactionData::getTimestamp))
                .orElse(null);
        if (latestUpdateFromName != null) {
            latestTransactions.add(latestUpdateFromName);
        }

        // Get the latest buy for this name
        BuyNameTransactionData latestBuyForName = buys.stream()
                .filter(update -> update.getName().equals(registeredName))
                .max(Comparator.comparing(BuyNameTransactionData::getTimestamp))
                .orElse(null);
        if (latestBuyForName != null) {
            latestTransactions.add(latestBuyForName);
        }

        // Get the latest name-related transaction of any type
        TransactionData latestUpdate = latestTransactions.stream()
                .max(Comparator.comparing(TransactionData::getTimestamp))
                .orElse(null);

        return latestUpdate;
    }

}
