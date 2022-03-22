package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.data.transaction.TransactionData;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class TransactionsMessage extends Message {

	private List<TransactionData> transactions;

	public TransactionsMessage(List<TransactionData> transactions) {
		this(-1, transactions);
	}

	private TransactionsMessage(int id, List<TransactionData> transactions) {
		super(id, MessageType.TRANSACTIONS);

		this.transactions = transactions;
	}

	public List<TransactionData> getTransactions() {
		return this.transactions;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws UnsupportedEncodingException {
		try {
			final int transactionCount = byteBuffer.getInt();

			List<TransactionData> transactions = new ArrayList<>();

			for (int i = 0; i < transactionCount; ++i) {
				TransactionData transactionData = TransactionTransformer.fromByteBuffer(byteBuffer);
				transactions.add(transactionData);
			}

			if (byteBuffer.hasRemaining()) {
				return null;
			}

			return new TransactionsMessage(id, transactions);
		} catch (TransformationException e) {
			return null;
		}
	}

	@Override
	protected byte[] toData() {
		if (this.transactions == null)
			return null;

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(this.transactions.size()));

			for (int i = 0; i < this.transactions.size(); ++i) {
				TransactionData transactionData = this.transactions.get(i);

				byte[] serializedTransactionData = TransactionTransformer.toBytes(transactionData);
				bytes.write(serializedTransactionData);
			}

			return bytes.toByteArray();
		} catch (TransformationException | IOException e) {
			return null;
		}
	}

}
