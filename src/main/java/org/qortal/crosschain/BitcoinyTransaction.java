package org.qortal.crosschain;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
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

		// For JAXB
		protected Input() {
			this.scriptSig = null;
			this.sequence = 0;
			this.outputTxHash = null;
			this.outputVout = 0;
		}

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
		public final Set<String> addresses;

		// For JAXB
		protected Output() {
			this.scriptPubKey = null;
			this.value = 0;
			this.addresses = null;
		}

		public Output(String scriptPubKey, long value) {
			this.scriptPubKey = scriptPubKey;
			this.value = value;
			this.addresses = null;
		}

		public Output(String scriptPubKey, long value, Set<String> addresses) {
			this.scriptPubKey = scriptPubKey;
			this.value = value;
			this.addresses = addresses;
		}

		public String toString() {
			return String.format("{value %d, scriptPubKey %s}", this.value, this.scriptPubKey);
		}
	}
	public final List<Output> outputs;

	// For JAXB
	protected BitcoinyTransaction() {
		this.txHash = null;
		this.size = 0;
		this.locktime = 0;
		this.timestamp = 0;
		this.inputs = null;
		this.outputs = null;
	}

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

	@Override
	public boolean equals(Object other) {
		if (other == this)
			return true;

		if (!(other instanceof BitcoinyTransaction))
			return false;

		BitcoinyTransaction otherTransaction = (BitcoinyTransaction) other;

		return this.txHash.equals(otherTransaction.txHash);
	}

	@Override
	public int hashCode() {
		return this.txHash.hashCode();
	}

}