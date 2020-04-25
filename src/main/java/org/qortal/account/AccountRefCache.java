package org.qortal.account;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;

import org.qortal.data.account.AccountData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Pair;

public class AccountRefCache implements AutoCloseable {

	private static final Map<Repository, RefCache> CACHE = new HashMap<Repository, RefCache>();

	private static class RefCache {
		private final Map<String, byte[]> getLastReferenceValues = new HashMap<String, byte[]>();
		private final Map<String, Pair<byte[], byte[]>> setLastReferenceValues = new HashMap<String, Pair<byte[], byte[]>>();

		public byte[] getLastReference(Repository repository, String address) throws DataException {
			synchronized (this.getLastReferenceValues) {
				byte[] lastReference = getLastReferenceValues.get(address);
				if (lastReference != null)
					// address is present in map, lastReference not null
					return lastReference;

				// address is present in map, just lastReference is null
				if (getLastReferenceValues.containsKey(address))
					return null;

				lastReference = repository.getAccountRepository().getLastReference(address);
				this.getLastReferenceValues.put(address, lastReference);
				return lastReference;
			}
		}

		public void setLastReference(AccountData accountData) {
			BiFunction<String, Pair<byte[], byte[]>, Pair<byte[], byte[]>> mergePublicKey = (key, oldPair) -> {
				byte[] mergedPublicKey = accountData.getPublicKey() != null ? accountData.getPublicKey() : oldPair.getB();
				return new Pair<>(accountData.getReference(), mergedPublicKey);
			};

			synchronized (this.setLastReferenceValues) {
				setLastReferenceValues.computeIfPresent(accountData.getAddress(), mergePublicKey);
			}
		}

		Map<String, Pair<byte[], byte[]>> getNewLastReferences() {
			return setLastReferenceValues;
		}
	}

	private Repository repository;

	public AccountRefCache(Repository repository) {
		RefCache refCache = new RefCache();

		synchronized (CACHE) {
			if (CACHE.putIfAbsent(repository, refCache) != null)
				throw new IllegalStateException("Account reference cache entry already exists");
		}

		this.repository = repository;
	}

	public void commit() throws DataException {
		RefCache refCache;

		synchronized (CACHE) {
			refCache = CACHE.remove(this.repository);
		}

		if (refCache == null)
			throw new IllegalStateException("Tried to commit non-existent account reference cache");

		Map<String, Pair<byte[], byte[]>> newLastReferenceValues = refCache.getNewLastReferences();

		for (Entry<String, Pair<byte[], byte[]>> entry : newLastReferenceValues.entrySet()) {
			AccountData accountData = new AccountData(entry.getKey());

			accountData.setReference(entry.getValue().getA());

			if (entry.getValue().getB() != null)
				accountData.setPublicKey(entry.getValue().getB());

			this.repository.getAccountRepository().setLastReference(accountData);
		}
	}

	@Override
	public void close() throws Exception {
		synchronized (CACHE) {
			CACHE.remove(this.repository);
		}
	}

	/*package*/ static byte[] getLastReference(Repository repository, String address) throws DataException {
		RefCache refCache;

		synchronized (CACHE) {
			refCache = CACHE.get(repository);
		}

		if (refCache == null)
			return repository.getAccountRepository().getLastReference(address);

		return refCache.getLastReference(repository, address);
	}

	/*package*/ static void setLastReference(Repository repository, AccountData accountData) throws DataException {
		RefCache refCache;

		synchronized (CACHE) {
			refCache = CACHE.get(repository);
		}

		if (refCache == null)
			repository.getAccountRepository().setLastReference(accountData);

		refCache.setLastReference(accountData);
	}

}
