package org.qortal.crosschain;

@SuppressWarnings("serial")
public class ForeignBlockchainException extends Exception {

	public ForeignBlockchainException() {
		super();
	}

	public ForeignBlockchainException(String message) {
		super(message);
	}

	public static class NetworkException extends ForeignBlockchainException {
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

	public static class NotFoundException extends ForeignBlockchainException {
		public NotFoundException() {
			super();
		}

		public NotFoundException(String message) {
			super(message);
		}
	}

	public static class InsufficientFundsException extends ForeignBlockchainException {
		public InsufficientFundsException() {
			super();
		}

		public InsufficientFundsException(String message) {
			super(message);
		}
	}

}
