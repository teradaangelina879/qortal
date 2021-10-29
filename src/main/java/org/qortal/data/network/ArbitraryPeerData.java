package org.qortal.data.network;

import org.qortal.crypto.Crypto;
import org.qortal.network.Peer;
import org.qortal.utils.NTP;

public class ArbitraryPeerData {

    private final byte[] hash;
    private final String peerAddress;
    private Integer successes;
    private Integer failures;
    private Long lastAttempted;
    private Long lastRetrieved;

    public ArbitraryPeerData(byte[] hash, String peerAddress, Integer successes,
                             Integer failures, Long lastAttempted, Long lastRetrieved) {
        this.hash = hash;
        this.peerAddress = peerAddress;
        this.successes = successes;
        this.failures = failures;
        this.lastAttempted = lastAttempted;
        this.lastRetrieved = lastRetrieved;
    }

    public ArbitraryPeerData(byte[] signature, Peer peer) {
        this(Crypto.digest(signature), peer.getPeerData().getAddress().toString(),
                0, 0, 0L, 0L);
    }

    public void incrementSuccesses() {
        this.successes++;
    }

    public void incrementFailures() {
        this.failures++;
    }

    public void markAsAttempted() {
        this.lastAttempted = NTP.getTime();
    }

    public void markAsRetrieved() {
        this.lastRetrieved = NTP.getTime();
    }

    public byte[] getHash() {
        return this.hash;
    }

    public String getPeerAddress() {
        return this.peerAddress;
    }

    public Integer getSuccesses() {
        return this.successes;
    }

    public Integer getFailures() {
        return this.failures;
    }

    public Long getLastAttempted() {
        return this.lastAttempted;
    }

    public Long getLastRetrieved() {
        return this.lastRetrieved;
    }

}
