package org.qortal.repository;

import org.qortal.data.network.ArbitraryPeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.*;

import java.util.List;

public interface ArbitraryRepository {

	public boolean isDataLocal(byte[] signature) throws DataException;

	public byte[] fetchData(byte[] signature) throws DataException;

	public void save(ArbitraryTransactionData arbitraryTransactionData) throws DataException;

	public void delete(ArbitraryTransactionData arbitraryTransactionData) throws DataException;

	public List<ArbitraryTransactionData> getArbitraryTransactions(String name, Service service, long since) throws DataException;

	public ArbitraryTransactionData getLatestTransaction(String name, Service service, Method method) throws DataException;


	public List<ArbitraryPeerData> getArbitraryPeerDataForSignature(byte[] signature) throws DataException;

	public ArbitraryPeerData getArbitraryPeerDataForSignatureAndPeer(byte[] signature, String peerAddress) throws DataException;

	public void save(ArbitraryPeerData arbitraryPeerData) throws DataException;

	public void delete(ArbitraryPeerData arbitraryPeerData) throws DataException;

}
