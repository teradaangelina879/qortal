package org.qortal.crosschain;

@SuppressWarnings("serial")
public class BitcoinException extends Exception {

	public BitcoinException() {
		super();
	}

	public BitcoinException(String message) {
		super(message);
	}

	public static class NetworkException extends BitcoinException {
		private final Integer daemonErrorCode;

		public NetworkException() {
			super();
			this.daemonErrorCode = null;
		}

		public NetworkException(String message) {
			super(message);
			this.daemonErrorCode = null;
		}

		public NetworkException(int errorCode, String message) {
			super(message);
			this.daemonErrorCode = errorCode;
		}

		public Integer getDaemonErrorCode() {
			return this.daemonErrorCode;
		}
	}

	public static class NotFoundException extends BitcoinException {
		public NotFoundException() {
			super();
		}

		public NotFoundException(String message) {
			super(message);
		}
	}

	public static class InsufficientFundsException extends BitcoinException {
		public InsufficientFundsException() {
			super();
		}

		public InsufficientFundsException(String message) {
			super(message);
		}
	}

}
