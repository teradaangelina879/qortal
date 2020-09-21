package org.qortal.crosschain;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.qortal.utils.ByteArray;
import org.qortal.utils.Triple;

public enum SupportedBlockchain {

	BITCOIN(Arrays.asList(
				Triple.valueOf(BitcoinACCTv1.NAME, BitcoinACCTv1.CODE_BYTES_HASH, BitcoinACCTv1::getInstance)
			)) {
		@Override
		public ForeignBlockchain getInstance() {
			return Bitcoin.getInstance();
		}

		@Override
		public ACCT getLatestAcct() {
			return BitcoinACCTv1.getInstance();
		}
	},

	LITECOIN(Arrays.asList(
			Triple.valueOf(LitecoinACCTv1.NAME, LitecoinACCTv1.CODE_BYTES_HASH, LitecoinACCTv1::getInstance)
		)) {
		@Override
		public ForeignBlockchain getInstance() {
			return Litecoin.getInstance();
		}

		@Override
		public ACCT getLatestAcct() {
			return LitecoinACCTv1.getInstance();
		}
	};

	private final Map<ByteArray, Supplier<ACCT>> supportedAcctsByCodeHash = new HashMap<>();
	private final Map<String, Supplier<ACCT>> supportedAcctsByName = new HashMap<>();

	SupportedBlockchain(List<Triple<String, byte[], Supplier<ACCT>>> supportedAccts) {
		supportedAccts.forEach(triple -> triple.consume((acctName, hashBytes, supplier) -> {
			supportedAcctsByCodeHash.put(new ByteArray(hashBytes), supplier);
			supportedAcctsByName.put(acctName, supplier);
		}));
	}

	public abstract ForeignBlockchain getInstance();
	public abstract ACCT getLatestAcct();

	public Map<ByteArray, Supplier<ACCT>> getAcctMap() {
		return Collections.unmodifiableMap(this.supportedAcctsByCodeHash);
	}

	public static ACCT getAcctByCodeHash(byte[] codeHash) {
		ByteArray wrappedCodeHash = new ByteArray(codeHash);

		for (SupportedBlockchain supportedBlockchain : SupportedBlockchain.values()) {
			Supplier<ACCT> acctInstanceSupplier = supportedBlockchain.supportedAcctsByCodeHash.get(wrappedCodeHash);

			if (acctInstanceSupplier != null)
				return acctInstanceSupplier.get();
		}

		return null;
	}

	public static ACCT getAcctByName(String acctName) {
		for (SupportedBlockchain supportedBlockchain : SupportedBlockchain.values()) {
			Supplier<ACCT> acctInstanceSupplier = supportedBlockchain.supportedAcctsByName.get(acctName);

			if (acctInstanceSupplier != null)
				return acctInstanceSupplier.get();
		}

		return null;
	}

}