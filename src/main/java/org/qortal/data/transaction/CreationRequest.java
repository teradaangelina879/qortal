package org.qortal.data.transaction;

import java.util.Base64;

public class CreationRequest {

    private short ciyamAtVersion;
    private String codeBytesBase64;
    private String dataBytesBase64;
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
        return Base64.getDecoder().decode(this.codeBytesBase64);
    }

    public void setCodeBytesBase64(String codeBytesBase64) {
        this.codeBytesBase64 = codeBytesBase64;
    }

    public byte[] getDataBytes() {
        return Base64.getDecoder().decode(this.dataBytesBase64);
    }

    public void setDataBytesBase64(String dataBytesBase64) {
        this.dataBytesBase64 = dataBytesBase64;
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
