package org.qortal.crosschain;

import java.util.List;
import java.util.stream.Collectors;

public class BitcoinyTransaction {

	public final String txHash;
	public final int size;
	public final int locktime;
	// Not present if transaction is unconfirmed
	public final Integer timestamp;

	public static class Input {
		public final String scriptSig;
		public final int sequence;
		public final String outputTxHash;
		public final int outputVout;

		public Input(String scriptSig, int sequence, String outputTxHash, int outputVout) {
			this.scriptSig = scriptSig;
			this.sequence = sequence;
			this.outputTxHash = outputTxHash;
			this.outputVout = outputVout;
		}

		public String toString() {
			return String.format("{output %s:%d, sequence %d, scriptSig %s}",
					this.outputTxHash, this.outputVout, this.sequence, this.scriptSig);
		}
	}
	public final List<Input> inputs;

	public static class Output {
		public final String scriptPubKey;
		public final long value;

		public Output(String scriptPubKey, long value) {
			this.scriptPubKey = scriptPubKey;
			this.value = value;
		}

		public String toString() {
			return String.format("{value %d, scriptPubKey %s}", this.value, this.scriptPubKey);
		}
	}
	public final List<Output> outputs;

	public BitcoinyTransaction(String txHash, int size, int locktime, Integer timestamp,
			List<Input> inputs, List<Output> outputs) {
		this.txHash = txHash;
		this.size = size;
		this.locktime = locktime;
		this.timestamp = timestamp;
		this.inputs = inputs;
		this.outputs = outputs;
	}

	public String toString() {
		return String.format("txHash %s, size %d, locktime %d, timestamp %d\n"
				+ "\tinputs: [%s]\n"
				+ "\toutputs: [%s]\n",
				this.txHash,
				this.size,
				this.locktime,
				this.timestamp,
				this.inputs.stream().map(Input::toString).collect(Collectors.joining(",\n\t\t")),
				this.outputs.stream().map(Output::toString).collect(Collectors.joining(",\n\t\t")));
	}
}