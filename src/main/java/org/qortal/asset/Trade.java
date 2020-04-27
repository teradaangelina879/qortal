package org.qortal.asset;

import java.math.BigDecimal;

import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.data.asset.OrderData;
import org.qortal.data.asset.TradeData;
import org.qortal.repository.AssetRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class Trade {

	// Properties
	private Repository repository;
	private TradeData tradeData;

	private AssetRepository assetRepository;

	private OrderData initiatingOrder;
	private OrderData targetOrder;
	private BigDecimal fulfilled;

	// Constructors

	public Trade(Repository repository, TradeData tradeData) {
		this.repository = repository;
		this.tradeData = tradeData;

		this.assetRepository = this.repository.getAssetRepository();
	}

	// Processing

	private void commonPrep() throws DataException {
		this.initiatingOrder = assetRepository.fromOrderId(this.tradeData.getInitiator());
		this.targetOrder = assetRepository.fromOrderId(this.tradeData.getTarget());

		// Note: targetAmount is amount traded FROM target order
		// Note: initiatorAmount is amount traded FROM initiating order

		// "amount" and "fulfilled" are the same asset for both orders
		// which is the matchedAmount in asset with highest assetID
		this.fulfilled = (initiatingOrder.getHaveAssetId() < initiatingOrder.getWantAssetId()) ? this.tradeData.getTargetAmount() : this.tradeData.getInitiatorAmount();
	}

	public void process() throws DataException {
		// Save trade into repository
		assetRepository.save(tradeData);

		// Note: targetAmount is amount traded FROM target order
		// Note: initiatorAmount is amount traded FROM initiating order

		commonPrep();

		// Update corresponding Orders on both sides of trade
		initiatingOrder.setFulfilled(initiatingOrder.getFulfilled().add(fulfilled));
		initiatingOrder.setIsFulfilled(Order.isFulfilled(initiatingOrder));
		// Set isClosed to true if isFulfilled now true
		initiatingOrder.setIsClosed(initiatingOrder.getIsFulfilled());
		assetRepository.save(initiatingOrder);

		targetOrder.setFulfilled(targetOrder.getFulfilled().add(fulfilled));
		targetOrder.setIsFulfilled(Order.isFulfilled(targetOrder));
		// Set isClosed to true if isFulfilled now true
		targetOrder.setIsClosed(targetOrder.getIsFulfilled());
		assetRepository.save(targetOrder);

		// Actually transfer asset balances
		Account initiatingCreator = new PublicKeyAccount(this.repository, initiatingOrder.getCreatorPublicKey());
		initiatingCreator.setConfirmedBalance(initiatingOrder.getWantAssetId(), initiatingCreator.getConfirmedBalance(initiatingOrder.getWantAssetId()).add(tradeData.getTargetAmount()));

		Account targetCreator = new PublicKeyAccount(this.repository, targetOrder.getCreatorPublicKey());
		targetCreator.setConfirmedBalance(targetOrder.getWantAssetId(), targetCreator.getConfirmedBalance(targetOrder.getWantAssetId()).add(tradeData.getInitiatorAmount()));

		// Possible partial saving to refund to initiator
		BigDecimal initiatorSaving = this.tradeData.getInitiatorSaving();
		if (initiatorSaving.compareTo(BigDecimal.ZERO) > 0)
			initiatingCreator.setConfirmedBalance(initiatingOrder.getHaveAssetId(), initiatingCreator.getConfirmedBalance(initiatingOrder.getHaveAssetId()).add(initiatorSaving));
	}

	public void orphan() throws DataException {
		AssetRepository assetRepository = this.repository.getAssetRepository();

		// Note: targetAmount is amount traded FROM target order
		// Note: initiatorAmount is amount traded FROM initiating order

		commonPrep();

		// Revert corresponding Orders on both sides of trade
		initiatingOrder.setFulfilled(initiatingOrder.getFulfilled().subtract(fulfilled));
		initiatingOrder.setIsFulfilled(Order.isFulfilled(initiatingOrder));
		// Set isClosed to false if isFulfilled now false
		initiatingOrder.setIsClosed(initiatingOrder.getIsFulfilled());
		assetRepository.save(initiatingOrder);

		targetOrder.setFulfilled(targetOrder.getFulfilled().subtract(fulfilled));
		targetOrder.setIsFulfilled(Order.isFulfilled(targetOrder));
		// Set isClosed to false if isFulfilled now false
		targetOrder.setIsClosed(targetOrder.getIsFulfilled());
		assetRepository.save(targetOrder);

		// Reverse asset transfers
		Account initiatingCreator = new PublicKeyAccount(this.repository, initiatingOrder.getCreatorPublicKey());
		initiatingCreator.setConfirmedBalance(initiatingOrder.getWantAssetId(), initiatingCreator.getConfirmedBalance(initiatingOrder.getWantAssetId()).subtract(tradeData.getTargetAmount()));

		Account targetCreator = new PublicKeyAccount(this.repository, targetOrder.getCreatorPublicKey());
		targetCreator.setConfirmedBalance(targetOrder.getWantAssetId(), targetCreator.getConfirmedBalance(targetOrder.getWantAssetId()).subtract(tradeData.getInitiatorAmount()));

		// Possible partial saving to claw back from  initiator
		BigDecimal initiatorSaving = this.tradeData.getInitiatorSaving();
		if (initiatorSaving.compareTo(BigDecimal.ZERO) > 0)
			initiatingCreator.setConfirmedBalance(initiatingOrder.getHaveAssetId(), initiatingCreator.getConfirmedBalance(initiatingOrder.getHaveAssetId()).subtract(initiatorSaving));

		// Remove trade from repository
		assetRepository.delete(tradeData);
	}

}
