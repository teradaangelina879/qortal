package org.qortal.data.at;

import java.math.BigDecimal;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

// All properties to be converted to JSON via JAX-RS
@XmlAccessorType(XmlAccessType.FIELD)
public class ATData {

	// Properties
	private String ATAddress;
	private byte[] creatorPublicKey;
	private long creation;
	private int version;
	private long assetId;
	private byte[] codeBytes;
	private byte[] codeHash;
	private boolean isSleeping;
	private Integer sleepUntilHeight;
	private boolean isFinished;
	private boolean hadFatalError;
	private boolean isFrozen;
	private BigDecimal frozenBalance;

	// Constructors

	// necessary for JAX-RS serialization
	protected ATData() {
	}

	public ATData(String ATAddress, byte[] creatorPublicKey, long creation, int version, long assetId, byte[] codeBytes, byte[] codeHash,
			boolean isSleeping, Integer sleepUntilHeight, boolean isFinished, boolean hadFatalError, boolean isFrozen, BigDecimal frozenBalance) {
		this.ATAddress = ATAddress;
		this.creatorPublicKey = creatorPublicKey;
		this.creation = creation;
		this.version = version;
		this.assetId = assetId;
		this.codeBytes = codeBytes;
		this.codeHash = codeHash;
		this.isSleeping = isSleeping;
		this.sleepUntilHeight = sleepUntilHeight;
		this.isFinished = isFinished;
		this.hadFatalError = hadFatalError;
		this.isFrozen = isFrozen;
		this.frozenBalance = frozenBalance;
	}

	public ATData(String ATAddress, byte[] creatorPublicKey, long creation, int version, long assetId, byte[] codeBytes, byte[] codeHash,
			boolean isSleeping, Integer sleepUntilHeight, boolean isFinished, boolean hadFatalError, boolean isFrozen, Long frozenBalance) {
		this(ATAddress, creatorPublicKey, creation, version, assetId, codeBytes, codeHash,
				isSleeping, sleepUntilHeight, isFinished, hadFatalError, isFrozen, (BigDecimal) null);

		// Convert Long frozenBalance to BigDecimal
		if (frozenBalance != null)
			this.frozenBalance = BigDecimal.valueOf(frozenBalance, 8);
	}

	/** For constructing skeleton ATData with bare minimum info. */
	public ATData(String ATAddress, byte[] creatorPublicKey, long creation, long assetId) {
		this.ATAddress = ATAddress;
		this.creatorPublicKey = creatorPublicKey;
		this.creation = creation;
		this.assetId = assetId;
	}

	// Getters / setters

	public String getATAddress() {
		return this.ATAddress;
	}

	public byte[] getCreatorPublicKey() {
		return this.creatorPublicKey;
	}

	public long getCreation() {
		return this.creation;
	}

	public int getVersion() {
		return this.version;
	}

	public long getAssetId() {
		return this.assetId;
	}

	public byte[] getCodeBytes() {
		return this.codeBytes;
	}

	public byte[] getCodeHash() {
		return this.codeHash;
	}

	public boolean getIsSleeping() {
		return this.isSleeping;
	}

	public void setIsSleeping(boolean isSleeping) {
		this.isSleeping = isSleeping;
	}

	public Integer getSleepUntilHeight() {
		return this.sleepUntilHeight;
	}

	public void setSleepUntilHeight(Integer sleepUntilHeight) {
		this.sleepUntilHeight = sleepUntilHeight;
	}

	public boolean getIsFinished() {
		return this.isFinished;
	}

	public void setIsFinished(boolean isFinished) {
		this.isFinished = isFinished;
	}

	public boolean getHadFatalError() {
		return this.hadFatalError;
	}

	public void setHadFatalError(boolean hadFatalError) {
		this.hadFatalError = hadFatalError;
	}

	public boolean getIsFrozen() {
		return this.isFrozen;
	}

	public void setIsFrozen(boolean isFrozen) {
		this.isFrozen = isFrozen;
	}

	public BigDecimal getFrozenBalance() {
		return this.frozenBalance;
	}

	public void setFrozenBalance(BigDecimal frozenBalance) {
		this.frozenBalance = frozenBalance;
	}

}
