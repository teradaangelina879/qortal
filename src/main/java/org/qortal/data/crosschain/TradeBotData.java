package org.qortal.data.crosschain;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

import java.util.Map;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotData {

	// Never expose this
	@XmlTransient
	@Schema(hidden = true)
	private byte[] tradePrivateKey;

	public enum State {
		BOB_WAITING_FOR_AT_CONFIRM(10), BOB_WAITING_FOR_MESSAGE(20), BOB_SENDING_MESSAGE_TO_AT(30), BOB_WAITING_FOR_P2SH_B(40), BOB_WAITING_FOR_AT_REDEEM(50),
		ALICE_WAITING_FOR_P2SH_A(110), ALICE_WAITING_FOR_AT_LOCK(120), ALICE_WATCH_P2SH_B(130);

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

	private String atAddress;
	private int tradeTimeout;

	private byte[] tradeNativePublicKey;
	private byte[] tradeNativePublicKeyHash;

	private byte[] secret;
	private byte[] secretHash;

	private byte[] tradeForeignPublicKey;
	private byte[] tradeForeignPublicKeyHash;

	private long bitcoinAmount;

	private byte[] lastTransactionSignature;

	public TradeBotData(byte[] tradePrivateKey, State tradeState, String atAddress, int tradeTimeout,
			byte[] tradeNativePublicKey, byte[] tradeNativePublicKeyHash, byte[] secret, byte[] secretHash,
			byte[] tradeForeignPublicKey, byte[] tradeForeignPublicKeyHash,
			long bitcoinAmount, byte[] lastTransactionSignature) {
		this.tradePrivateKey = tradePrivateKey;
		this.tradeState = tradeState;
		this.atAddress = atAddress;
		this.tradeTimeout = tradeTimeout;
		this.tradeNativePublicKey = tradeNativePublicKey;
		this.tradeNativePublicKeyHash = tradeNativePublicKeyHash;
		this.secret = secret;
		this.secretHash = secretHash;
		this.tradeForeignPublicKey = tradeForeignPublicKey;
		this.tradeForeignPublicKeyHash = tradeForeignPublicKeyHash;
		this.bitcoinAmount = bitcoinAmount;
		this.lastTransactionSignature = lastTransactionSignature;
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

	public String getAtAddress() {
		return this.atAddress;
	}

	public void setAtAddress(String atAddress) {
		this.atAddress = atAddress;
	}

	public int getTradeTimeout() {
		return this.tradeTimeout;
	}

	public byte[] getTradeNativePublicKey() {
		return this.tradeNativePublicKey;
	}

	public byte[] getTradeNativePublicKeyHash() {
		return this.tradeNativePublicKeyHash;
	}

	public byte[] getSecret() {
		return this.secret;
	}

	public byte[] getSecretHash() {
		return this.secretHash;
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

	public byte[] getLastTransactionSignature() {
		return this.lastTransactionSignature;
	}

	public void setLastTransactionSignature(byte[] lastTransactionSignature) {
		this.lastTransactionSignature = lastTransactionSignature;
	}

}
