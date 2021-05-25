package org.qortal.crosschain;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class SimpleTransaction {
    private String txHash;
    private Integer timestamp;
    private long totalAmount;

    public SimpleTransaction() {
    }

    public SimpleTransaction(String txHash, Integer timestamp, long totalAmount) {
        this.txHash = txHash;
        this.timestamp = timestamp;
        this.totalAmount = totalAmount;
    }

    public String getTxHash() {
        return txHash;
    }

    public Integer getTimestamp() {
        return timestamp;
    }

    public long getTotalAmount() {
        return totalAmount;
    }
}