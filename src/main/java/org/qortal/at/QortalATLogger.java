package org.qortal.at;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class QortalATLogger implements org.ciyam.at.LoggerInterface {

	// NOTE: We're logging on behalf of org.qortal.at.AT, not ourselves!
	private static final Logger LOGGER = LogManager.getLogger(AT.class);

	@Override
	public void error(String message) {
		LOGGER.error(message);
	}

	@Override
	public void debug(String message) {
		LOGGER.debug(message);
	}

	@Override
	public void echo(String message) {
		LOGGER.info(message);
	}

}
