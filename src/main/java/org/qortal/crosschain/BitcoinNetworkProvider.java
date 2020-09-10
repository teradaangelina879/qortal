package org.qortal.crosschain;

import java.util.List;

interface BitcoinNetworkProvider {

	/** Returns current blockchain height. */
	int getCurrentHeight() throws BitcoinException;

	/** Returns a list of raw block headers, starting at <tt>startHeight</tt> (inclusive), up to <tt>count</tt> max. */
	List<byte[]> getRawBlockHeaders(int startHeight, int count) throws BitcoinException;

	/** Returns balance of address represented by <tt>scriptPubKey</tt>. */
	long getConfirmedBalance(byte[] scriptPubKey) throws BitcoinException;

	/** Returns raw, serialized, transaction bytes given <tt>txHash</tt>. */
	byte[] getRawTransaction(String txHash) throws BitcoinException;

	/** Returns unpacked transaction given <tt>txHash</tt>. */
	BitcoinTransaction getTransaction(String txHash) throws BitcoinException;

	/** Returns list of transaction hashes (and heights) for address represented by <tt>scriptPubKey</tt>, optionally including unconfirmed transactions. */
	List<TransactionHash> getAddressTransactions(byte[] scriptPubKey, boolean includeUnconfirmed) throws BitcoinException;

	/** Returns list of unspent transaction outputs for address represented by <tt>scriptPubKey</tt>, optionally including unconfirmed transactions. */
	List<UnspentOutput> getUnspentOutputs(byte[] scriptPubKey, boolean includeUnconfirmed) throws BitcoinException;

	/** Broadcasts raw, serialized, transaction bytes to network, returning success/failure. */
	boolean broadcastTransaction(byte[] rawTransaction) throws BitcoinException;

}
