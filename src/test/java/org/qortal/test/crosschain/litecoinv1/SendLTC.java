package org.qortal.test.crosschain.litecoinv1;

import java.security.Security;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.Litecoin;
import org.qortal.settings.Settings;

public class SendLTC {

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: SendLTC <xprv58> <recipient> <LTC-amount>"));
		System.err.println(String.format("example: SendLTC "
				+ "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ \\\n"
				+ "\tmsAfaDaJ8JiprxxFaAXEEPxKK3JaZCYpLv \\\n"
				+ "\t0.00008642"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 3 || args.length > 3)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		Settings.fileInstance("settings-test.json");

		Litecoin litecoin = Litecoin.getInstance();
		NetworkParameters params = litecoin.getNetworkParameters();

		String xprv58 = null;
		Address litecoinAddress = null;
		Coin litecoinAmount = null;

		int argIndex = 0;
		try {
			xprv58 = args[argIndex++];
			if (!litecoin.isValidXprv(xprv58))
				usage("xprv invalid");

			litecoinAddress = Address.fromString(params, args[argIndex++]);

			litecoinAmount = Coin.parseCoin(args[argIndex++]);
		} catch (IllegalArgumentException e) {
			usage(String.format("Invalid argument %d: %s", argIndex, e.getMessage()));
		}

		System.out.println(String.format("Litecoin address: %s", litecoinAddress));
		System.out.println(String.format("Litecoin amount: %s", litecoinAmount.toPlainString()));

		Transaction transaction = litecoin.buildSpend(xprv58, litecoinAddress.toString(), litecoinAmount.value);
		if (transaction == null) {
			System.err.println("Insufficent funds");
			System.exit(1);
		}

		try {
			litecoin.broadcastTransaction(transaction);
		} catch (ForeignBlockchainException e) {
			System.err.println("Transaction broadcast failed: " + e.getMessage());
		}
	}

}
