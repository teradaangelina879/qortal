package org.qortal.at;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.ciyam.at.ExecutionException;
import org.ciyam.at.FunctionData;
import org.ciyam.at.IllegalFunctionCodeException;
import org.ciyam.at.MachineState;
import org.qortal.crosschain.BTC;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;

/**
 * Qortal-specific CIYAM-AT Functions.
 * <p>
 * Function codes need to be between 0x0500 and 0x06ff.
 *
 */
public enum QortalFunctionCode {
	/**
	 * <tt>0x0510</tt><br>
	 * Convert address in B to 20-byte value in LSB of B1, and all of B2 & B3.
	 */
	CONVERT_B_TO_PKH(0x0510, 0, false) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			// Needs to be 'B' sized
			byte[] pkh = new byte[32];

			// Copy PKH part of B to last 20 bytes
			System.arraycopy(state.getB(), 32 - 20 - 4, pkh, 32 - 20, 20);

			state.getAPI().setB(state, pkh);
		}
	},
	/**
	 * <tt>0x0511</tt><br>
	 * Convert 20-byte value in LSB of B1, and all of B2 & B3 to P2SH.<br>
	 * P2SH stored in lower 25 bytes of B.
	 */
	CONVERT_B_TO_P2SH(0x0511, 0, false) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			byte addressPrefix = Settings.getInstance().getBitcoinNet() == BTC.BitcoinNet.MAIN ? 0x05 : (byte) 0xc4;

			convertAddressInB(addressPrefix, state);
		}
	},
	/**
	 * <tt>0x0512</tt><br>
	 * Convert 20-byte value in LSB of B1, and all of B2 & B3 to Qortal address.<br>
	 * Qortal address stored in lower 25 bytes of B.
	 */
	CONVERT_B_TO_QORTAL(0x0512, 0, false) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			convertAddressInB(Crypto.ADDRESS_VERSION, state);
		}
	};

	public final short value;
	public final int paramCount;
	public final boolean returnsValue;

	private static final Map<Short, QortalFunctionCode> map = Arrays.stream(QortalFunctionCode.values())
			.collect(Collectors.toMap(functionCode -> functionCode.value, functionCode -> functionCode));

	private QortalFunctionCode(int value, int paramCount, boolean returnsValue) {
		this.value = (short) value;
		this.paramCount = paramCount;
		this.returnsValue = returnsValue;
	}

	public static QortalFunctionCode valueOf(int value) {
		return map.get((short) value);
	}

	public void preExecuteCheck(int paramCount, boolean returnValueExpected, MachineState state, short rawFunctionCode) throws IllegalFunctionCodeException {
		if (paramCount != this.paramCount)
			throw new IllegalFunctionCodeException(
					"Passed paramCount (" + paramCount + ") does not match function's required paramCount (" + this.paramCount + ")");

		if (returnValueExpected != this.returnsValue)
			throw new IllegalFunctionCodeException(
					"Passed returnValueExpected (" + returnValueExpected + ") does not match function's return signature (" + this.returnsValue + ")");
	}

	/**
	 * Execute Function
	 * <p>
	 * Can modify various fields of <tt>state</tt>, including <tt>programCounter</tt>.
	 * <p>
	 * Throws a subclass of <tt>ExecutionException</tt> on error, e.g. <tt>InvalidAddressException</tt>.
	 *
	 * @param functionData
	 * @param state
	 * @throws ExecutionException
	 */
	public void execute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
		// Check passed functionData against requirements of this function
		preExecuteCheck(functionData.paramCount, functionData.returnValueExpected, state, rawFunctionCode);

		if (functionData.paramCount >= 1 && functionData.value1 == null)
			throw new IllegalFunctionCodeException("Passed value1 is null but function has paramCount of (" + this.paramCount + ")");

		if (functionData.paramCount == 2 && functionData.value2 == null)
			throw new IllegalFunctionCodeException("Passed value2 is null but function has paramCount of (" + this.paramCount + ")");

		state.getLogger().debug("Function \"" + this.name() + "\"");

		postCheckExecute(functionData, state, rawFunctionCode);
	}

	/** Actually execute function */
	protected abstract void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException;

	private static void convertAddressInB(byte addressPrefix, MachineState state) {
		byte[] addressNoChecksum = new byte[1 + 20];
		addressNoChecksum[0] = addressPrefix;
		System.arraycopy(state.getB(), 0, addressNoChecksum, 1, 20);

		byte[] checksum = Crypto.doubleDigest(addressNoChecksum);

		// Needs to be 'B' sized
		byte[] address = new byte[32];
		System.arraycopy(addressNoChecksum, 0, address, 32 - 1 - 20 - 4, addressNoChecksum.length);
		System.arraycopy(checksum, 0, address, 32 - 4, 4);

		state.getAPI().setB(state, address);
	}

}
