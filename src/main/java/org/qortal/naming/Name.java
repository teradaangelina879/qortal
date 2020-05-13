package org.qortal.naming;

import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.BuyNameTransactionData;
import org.qortal.data.transaction.CancelSellNameTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.data.transaction.SellNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.UpdateNameTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class Name {

	// Properties
	private Repository repository;
	private NameData nameData;

	// Useful constants
	public static final int MIN_NAME_SIZE = 3;
	public static final int MAX_NAME_SIZE = 400;
	public static final int MAX_DATA_SIZE = 4000;

	// Constructors

	/**
	 * Construct Name business object using info from register name transaction.
	 * 
	 * @param repository
	 * @param registerNameTransactionData
	 */
	public Name(Repository repository, RegisterNameTransactionData registerNameTransactionData) {
		this.repository = repository;

		String owner = Crypto.toAddress(registerNameTransactionData.getRegistrantPublicKey());

		this.nameData = new NameData(owner,
				registerNameTransactionData.getName(), registerNameTransactionData.getData(), registerNameTransactionData.getTimestamp(),
				registerNameTransactionData.getSignature(), registerNameTransactionData.getTxGroupId());
	}

	/**
	 * Construct Name business object using existing name in repository.
	 * 
	 * @param repository
	 * @param name
	 * @throws DataException
	 */
	public Name(Repository repository, String name) throws DataException {
		this.repository = repository;
		this.nameData = this.repository.getNameRepository().fromName(name);
	}

	// Processing

	public void register() throws DataException {
		this.repository.getNameRepository().save(this.nameData);
	}

	public void unregister() throws DataException {
		this.repository.getNameRepository().delete(this.nameData.getName());
	}

	private void revert() throws DataException {
		TransactionData previousTransactionData = this.repository.getTransactionRepository().fromSignature(this.nameData.getReference());
		if (previousTransactionData == null)
			throw new DataException("Unable to revert name transaction as referenced transaction not found in repository");

		switch (previousTransactionData.getType()) {
			case REGISTER_NAME: {
				RegisterNameTransactionData previousRegisterNameTransactionData = (RegisterNameTransactionData) previousTransactionData;
				this.nameData.setName(previousRegisterNameTransactionData.getName());
				this.nameData.setData(previousRegisterNameTransactionData.getData());
				break;
			}

			case UPDATE_NAME: {
				UpdateNameTransactionData previousUpdateNameTransactionData = (UpdateNameTransactionData) previousTransactionData;

				if (!previousUpdateNameTransactionData.getNewName().isBlank())
					this.nameData.setName(previousUpdateNameTransactionData.getNewName());

				if (!previousUpdateNameTransactionData.getNewData().isEmpty())
					this.nameData.setData(previousUpdateNameTransactionData.getNewData());

				break;
			}

			case BUY_NAME: {
				BuyNameTransactionData previousBuyNameTransactionData = (BuyNameTransactionData) previousTransactionData;
				Account buyer = new PublicKeyAccount(this.repository, previousBuyNameTransactionData.getBuyerPublicKey());
				this.nameData.setOwner(buyer.getAddress());
				break;
			}

			default:
				throw new IllegalStateException("Unable to revert name transaction due to unsupported referenced transaction");
		}
	}

	public void update(UpdateNameTransactionData updateNameTransactionData) throws DataException {
		// Update reference in transaction data
		updateNameTransactionData.setNameReference(this.nameData.getReference());

		// New name reference is this transaction's signature
		this.nameData.setReference(updateNameTransactionData.getSignature());

		// Update name and data where appropriate
		if (!updateNameTransactionData.getNewName().isEmpty())
			this.nameData.setOwner(updateNameTransactionData.getNewName());

		if (!updateNameTransactionData.getNewData().isEmpty())
			this.nameData.setData(updateNameTransactionData.getNewData());

		// Save updated name data
		this.repository.getNameRepository().save(this.nameData);
	}

	public void revert(UpdateNameTransactionData updateNameTransactionData) throws DataException {
		// Previous name reference is taken from this transaction's cached copy
		this.nameData.setReference(updateNameTransactionData.getNameReference());

		// Previous Name's owner and/or data taken from referenced transaction
		this.revert();

		// Save reverted name data
		this.repository.getNameRepository().save(this.nameData);

		// Remove reference to previous name-changing transaction
		updateNameTransactionData.setNameReference(null);
	}

	public void sell(SellNameTransactionData sellNameTransactionData) throws DataException {
		// Mark as for-sale and set price
		this.nameData.setIsForSale(true);
		this.nameData.setSalePrice(sellNameTransactionData.getAmount());

		// Save sale info into repository
		this.repository.getNameRepository().save(this.nameData);
	}

	public void unsell(SellNameTransactionData sellNameTransactionData) throws DataException {
		// Mark not for-sale and unset price
		this.nameData.setIsForSale(false);
		this.nameData.setSalePrice(null);

		// Save no-sale info into repository
		this.repository.getNameRepository().save(this.nameData);
	}

	public void cancelSell(CancelSellNameTransactionData cancelSellNameTransactionData) throws DataException {
		// Mark not for-sale but leave price in case we want to orphan
		this.nameData.setIsForSale(false);

		// Save sale info into repository
		this.repository.getNameRepository().save(this.nameData);
	}

	public void uncancelSell(CancelSellNameTransactionData cancelSellNameTransactionData) throws DataException {
		// Mark as for-sale using existing price
		this.nameData.setIsForSale(true);

		// Save no-sale info into repository
		this.repository.getNameRepository().save(this.nameData);
	}

	public void buy(BuyNameTransactionData buyNameTransactionData) throws DataException {
		// Mark not for-sale but leave price in case we want to orphan
		this.nameData.setIsForSale(false);

		// Update seller's balance
		Account seller = new Account(this.repository, this.nameData.getOwner());
		seller.modifyAssetBalance(Asset.QORT, buyNameTransactionData.getAmount());

		// Set new owner
		Account buyer = new PublicKeyAccount(this.repository, buyNameTransactionData.getBuyerPublicKey());
		this.nameData.setOwner(buyer.getAddress());
		// Update buyer's balance
		buyer.modifyAssetBalance(Asset.QORT, - buyNameTransactionData.getAmount());

		// Update reference in transaction data
		buyNameTransactionData.setNameReference(this.nameData.getReference());

		// New name reference is this transaction's signature
		this.nameData.setReference(buyNameTransactionData.getSignature());

		// Save updated name data
		this.repository.getNameRepository().save(this.nameData);
	}

	public void unbuy(BuyNameTransactionData buyNameTransactionData) throws DataException {
		// Mark as for-sale using existing price
		this.nameData.setIsForSale(true);
		this.nameData.setSalePrice(buyNameTransactionData.getAmount());

		// Previous name reference is taken from this transaction's cached copy
		this.nameData.setReference(buyNameTransactionData.getNameReference());

		// Remove reference in transaction data
		buyNameTransactionData.setNameReference(null);

		// Revert buyer's balance
		Account buyer = new PublicKeyAccount(this.repository, buyNameTransactionData.getBuyerPublicKey());
		buyer.modifyAssetBalance(Asset.QORT, buyNameTransactionData.getAmount());

		// Previous Name's owner and/or data taken from referenced transaction
		this.revert();

		// Revert seller's balance
		Account seller = new Account(this.repository, this.nameData.getOwner());
		seller.modifyAssetBalance(Asset.QORT, - buyNameTransactionData.getAmount());

		// Save reverted name data
		this.repository.getNameRepository().save(this.nameData);
	}

}
