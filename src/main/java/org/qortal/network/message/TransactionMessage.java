package org.qortal.network.message;

import java.nio.ByteBuffer;

import org.qortal.data.transaction.TransactionData;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;

public class TransactionMessage extends Message {

	private TransactionData transactionData;

	public TransactionMessage(TransactionData transactionData) throws TransformationException {
		super(MessageType.TRANSACTION);

		this.dataBytes = TransactionTransformer.toBytes(transactionData);
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private TransactionMessage(int id, TransactionData transactionData) {
		super(id, MessageType.TRANSACTION);

		this.transactionData = transactionData;
	}

	public TransactionData getTransactionData() {
		return this.transactionData;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		TransactionData transactionData;

		try {
			transactionData = TransactionTransformer.fromByteBuffer(byteBuffer);
		} catch (TransformationException e) {
			throw new MessageException(e.getMessage(), e);
		}

		return new TransactionMessage(id, transactionData);
	}

}
