package org.qortal.data.crosschain;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

import java.util.Map;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotData {

	private byte[] tradePrivateKey;

	public enum State {
		BOB_WAITING_FOR_AT_CONFIRM(10), BOB_WAITING_FOR_MESSAGE(15), BOB_WAITING_FOR_P2SH_B(20), BOB_WAITING_FOR_AT_REDEEM(25), BOB_DONE(30), BOB_REFUNDED(35),
		ALICE_WAITING_FOR_P2SH_A(80), ALICE_WAITING_FOR_AT_LOCK(85), ALICE_WATCH_P2SH_B(90), ALICE_DONE(95), ALICE_REFUNDING_B(100), ALICE_REFUNDING_A(105), ALICE_REFUNDED(110);

		public final int value;
		private static final Map<Integer, State> map = stream(State.values()).collect(toMap(state -> state.value, state -> state));

		State(int value) {
			this.value = value;
		}

		public static State valueOf(int value) {
			return map.get(value);
		}
	}
	private State tradeState;

	private String creatorAddress;
	private String atAddress;

	private long timestamp;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long qortAmount;

	private byte[] tradeNativePublicKey;
	private byte[] tradeNativePublicKeyHash;
	String tradeNativeAddress;

	private byte[] secret;
	private byte[] hashOfSecret;

	private byte[] tradeForeignPublicKey;
	private byte[] tradeForeignPublicKeyHash;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long bitcoinAmount;

	// Never expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private String xprv58;

	private byte[] lastTransactionSignature;

	private Integer lockTimeA;

	// Could be Bitcoin or Qortal...
	private byte[] receivingAccountInfo;

	protected TradeBotData() {
		/* JAXB */
	}

	public TradeBotData(byte[] tradePrivateKey, State tradeState, String creatorAddress, String atAddress,
			long timestamp, long qortAmount,
			byte[] tradeNativePublicKey, byte[] tradeNativePublicKeyHash, String tradeNativeAddress,
			byte[] secret, byte[] hashOfSecret,
			byte[] tradeForeignPublicKey, byte[] tradeForeignPublicKeyHash,
			long bitcoinAmount, String xprv58, byte[] lastTransactionSignature, Integer lockTimeA, byte[] receivingAccountInfo) {
		this.tradePrivateKey = tradePrivateKey;
		this.tradeState = tradeState;
		this.creatorAddress = creatorAddress;
		this.atAddress = atAddress;
		this.timestamp = timestamp;
		this.qortAmount = qortAmount;
		this.tradeNativePublicKey = tradeNativePublicKey;
		this.tradeNativePublicKeyHash = tradeNativePublicKeyHash;
		this.tradeNativeAddress = tradeNativeAddress;
		this.secret = secret;
		this.hashOfSecret = hashOfSecret;
		this.tradeForeignPublicKey = tradeForeignPublicKey;
		this.tradeForeignPublicKeyHash = tradeForeignPublicKeyHash;
		this.bitcoinAmount = bitcoinAmount;
		this.xprv58 = xprv58;
		this.lastTransactionSignature = lastTransactionSignature;
		this.lockTimeA = lockTimeA;
		this.receivingAccountInfo = receivingAccountInfo;
	}

	public byte[] getTradePrivateKey() {
		return this.tradePrivateKey;
	}

	public State getState() {
		return this.tradeState;
	}

	public void setState(State state) {
		this.tradeState = state;
	}

	public String getCreatorAddress() {
		return this.creatorAddress;
	}

	public String getAtAddress() {
		return this.atAddress;
	}

	public void setAtAddress(String atAddress) {
		this.atAddress = atAddress;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

	public long getQortAmount() {
		return this.qortAmount;
	}

	public byte[] getTradeNativePublicKey() {
		return this.tradeNativePublicKey;
	}

	public byte[] getTradeNativePublicKeyHash() {
		return this.tradeNativePublicKeyHash;
	}

	public String getTradeNativeAddress() {
		return this.tradeNativeAddress;
	}

	public byte[] getSecret() {
		return this.secret;
	}

	public byte[] getHashOfSecret() {
		return this.hashOfSecret;
	}

	public byte[] getTradeForeignPublicKey() {
		return this.tradeForeignPublicKey;
	}

	public byte[] getTradeForeignPublicKeyHash() {
		return this.tradeForeignPublicKeyHash;
	}

	public long getBitcoinAmount() {
		return this.bitcoinAmount;
	}

	public String getXprv58() {
		return this.xprv58;
	}

	public byte[] getLastTransactionSignature() {
		return this.lastTransactionSignature;
	}

	public void setLastTransactionSignature(byte[] lastTransactionSignature) {
		this.lastTransactionSignature = lastTransactionSignature;
	}

	public Integer getLockTimeA() {
		return this.lockTimeA;
	}

	public void setLockTimeA(Integer lockTimeA) {
		this.lockTimeA = lockTimeA;
	}

	public byte[] getReceivingAccountInfo() {
		return this.receivingAccountInfo;
	}

	// Mostly for debugging
	public String toString() {
		return String.format("%s: %s", this.atAddress, this.tradeState.name());
	}

}
