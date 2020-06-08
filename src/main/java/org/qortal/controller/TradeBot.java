package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TradeBot {

	private static final Logger LOGGER = LogManager.getLogger(TradeBot.class);

	private static TradeBot instance;

	private TradeBot() {
		
	}

	public static synchronized TradeBot getInstance() {
		if (instance == null)
			instance = new TradeBot();

		return instance;
	}

	public void onChainTipChange() {
		// Get repo for trade situations
	}

}
