package org.qortal.data.transaction;

public class CreationRequest  {

    private short ciyamAtVersion;
    private byte[] codeBytes;
    private byte[] dataBytes;
    private short numCallStackPages;
    private short numUserStackPages;
    private long minActivationAmount;

    // Default constructor for JSON deserialization
    public CreationRequest() {}

    // Getters and setters
    public short getCiyamAtVersion() {
        return ciyamAtVersion;
    }

    public void setCiyamAtVersion(short ciyamAtVersion) {
        this.ciyamAtVersion = ciyamAtVersion;
    }

    public byte[] getCodeBytes() {
        return codeBytes;
    }

    public void setCodeBytes(byte[] codeBytes) {
        this.codeBytes = codeBytes;
    }

    public byte[] getDataBytes() {
        return dataBytes;
    }

    public void setDataBytes(byte[] dataBytes) {
        this.dataBytes = dataBytes;
    }

    public short getNumCallStackPages() {
        return numCallStackPages;
    }

    public void setNumCallStackPages(short numCallStackPages) {
        this.numCallStackPages = numCallStackPages;
    }

    public short getNumUserStackPages() {
        return numUserStackPages;
    }

    public void setNumUserStackPages(short numUserStackPages) {
        this.numUserStackPages = numUserStackPages;
    }

    public long getMinActivationAmount() {
        return minActivationAmount;
    }

    public void setMinActivationAmount(long minActivationAmount) {
        this.minActivationAmount = minActivationAmount;
    }
}