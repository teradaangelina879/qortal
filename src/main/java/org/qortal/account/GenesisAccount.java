package org.qortal.account;

import org.qortal.repository.Repository;

public final class GenesisAccount extends PublicKeyAccount {

	public static final byte[] PUBLIC_KEY = new byte[] { 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

	public GenesisAccount(Repository repository) {
		super(repository, PUBLIC_KEY);
	}

}
