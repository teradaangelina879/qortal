package org.qortal.data.arbitrary;

import java.util.List;

public class ArbitraryDirectConnectionInfo {

    private final byte[] signature;
    private final String peerAddress;
    private final List<byte[]> hashes;
    private final long timestamp;

    public ArbitraryDirectConnectionInfo(byte[] signature, String peerAddress, List<byte[]> hashes, long timestamp) {
        this.signature = signature;
        this.peerAddress = peerAddress;
        this.hashes = hashes;
        this.timestamp = timestamp;
    }

    public byte[] getSignature() {
        return this.signature;
    }

    public String getPeerAddress() {
        return this.peerAddress;
    }

    public List<byte[]> getHashes() {
        return this.hashes;
    }
    
    public long getTimestamp() {
        return this.timestamp;
    }
}
