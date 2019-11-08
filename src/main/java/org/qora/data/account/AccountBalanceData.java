package org.qora.data.account;

import java.math.BigDecimal;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class AccountBalanceData {

	// Properties
	private String address;
	private long assetId;
	private BigDecimal balance;

	// Not always present:
	private Integer height;
	private String assetName;

	// Constructors

	// necessary for JAXB
	protected AccountBalanceData() {
	}

	public AccountBalanceData(String address, long assetId, BigDecimal balance) {
		this.address = address;
		this.assetId = assetId;
		this.balance = balance;
	}

	public AccountBalanceData(String address, long assetId, BigDecimal balance, int height) {
		this(address, assetId, balance);

		this.height = height;
	}

	public AccountBalanceData(String address, long assetId, BigDecimal balance, String assetName) {
		this(address, assetId, balance);

		this.assetName = assetName;
	}

	// Getters/Setters

	public String getAddress() {
		return this.address;
	}

	public long getAssetId() {
		return this.assetId;
	}

	public BigDecimal getBalance() {
		return this.balance;
	}

	public void setBalance(BigDecimal balance) {
		this.balance = balance;
	}

	public Integer getHeight() {
		return this.height;
	}

	public String getAssetName() {
		return this.assetName;
	}

}
