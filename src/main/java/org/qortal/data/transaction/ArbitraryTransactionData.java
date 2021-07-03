package org.qortal.data.transaction;

import java.util.List;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortal.data.PaymentData;
import org.qortal.transaction.Transaction.TransactionType;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
//JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("ARBITRARY")
public class ArbitraryTransactionData extends TransactionData {

	// "data" field types
	public enum DataType {
		RAW_DATA,
		DATA_HASH;
	}

	// Properties
	private int version;

	@Schema(example = "sender_public_key")
	private byte[] senderPublicKey;

	private int service;
	private int nonce;
	private int size;

	@Schema(example = "raw_data_in_base58")
	private byte[] data;
	private DataType dataType;
	@Schema(example = "chunk_hashes_in_base58")
	private byte[] chunkHashes;
	private List<PaymentData> payments;

	// Constructors

	// For JAXB
	protected ArbitraryTransactionData() {
		super(TransactionType.ARBITRARY);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.senderPublicKey;
	}

	public ArbitraryTransactionData(BaseTransactionData baseTransactionData,
			int version, int service, int nonce, int size, byte[] data,
			DataType dataType, byte[] chunkHashes, List<PaymentData> payments) {
		super(TransactionType.ARBITRARY, baseTransactionData);

		this.senderPublicKey = baseTransactionData.creatorPublicKey;
		this.version = version;
		this.service = service;
		this.nonce = nonce;
		this.size = size;
		this.data = data;
		this.dataType = dataType;
		this.chunkHashes = chunkHashes;
		this.payments = payments;
	}

	// Getters/Setters

	public byte[] getSenderPublicKey() {
		return this.senderPublicKey;
	}

	public int getVersion() {
		return this.version;
	}

	public int getService() {
		return this.service;
	}

	public int getNonce() {
		return this.nonce;
	}

	public void setNonce(int nonce) {
		this.nonce = nonce;
	}

	public int getSize() {
		return this.size;
	}

	public byte[] getData() {
		return this.data;
	}

	public void setData(byte[] data) {
		this.data = data;
	}

	public DataType getDataType() {
		return this.dataType;
	}

	public void setDataType(DataType dataType) {
		this.dataType = dataType;
	}

	public byte[] getChunkHashes() {
		return this.chunkHashes;
	}

	public void setChunkHashes(byte[] chunkHashes) {
		this.chunkHashes = chunkHashes;
	}

	public List<PaymentData> getPayments() {
		return this.payments;
	}

}
