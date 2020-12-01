package org.qortal.crosschain;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.qortal.utils.ByteArray;
import org.qortal.utils.Triple;

public enum SupportedBlockchain {

	BITCOIN(Arrays.asList(
				Triple.valueOf(BitcoinACCTv1.NAME, BitcoinACCTv1.CODE_BYTES_HASH, BitcoinACCTv1::getInstance)
				// Could add improved BitcoinACCTv2 here in the future
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

	private static final Map<ByteArray, Supplier<ACCT>> supportedAcctsByCodeHash = Arrays.stream(SupportedBlockchain.values())
			.map(supportedBlockchain -> supportedBlockchain.supportedAccts)
			.flatMap(List::stream)
			.collect(Collectors.toUnmodifiableMap(triple -> new ByteArray(triple.getB()), Triple::getC));

	private static final Map<String, Supplier<ACCT>> supportedAcctsByName = Arrays.stream(SupportedBlockchain.values())
			.map(supportedBlockchain -> supportedBlockchain.supportedAccts)
			.flatMap(List::stream)
			.collect(Collectors.toUnmodifiableMap(Triple::getA, Triple::getC));

	private final List<Triple<String, byte[], Supplier<ACCT>>> supportedAccts;

	SupportedBlockchain(List<Triple<String, byte[], Supplier<ACCT>>> supportedAccts) {
		this.supportedAccts = supportedAccts;
	}

	public abstract ForeignBlockchain getInstance();
	public abstract ACCT getLatestAcct();

	public static Map<ByteArray, Supplier<ACCT>> getAcctMap() {
		return supportedAcctsByCodeHash;
	}

	public static ACCT getAcctByCodeHash(byte[] codeHash) {
		ByteArray wrappedCodeHash = new ByteArray(codeHash);

		Supplier<ACCT> acctInstanceSupplier = supportedAcctsByCodeHash.get(wrappedCodeHash);

		if (acctInstanceSupplier == null)
			return null;

		return acctInstanceSupplier.get();
	}

	public static ACCT getAcctByName(String acctName) {
		Supplier<ACCT> acctInstanceSupplier = supportedAcctsByName.get(acctName);

		if (acctInstanceSupplier == null)
			return null;

		return acctInstanceSupplier.get();
	}

}