package org.qora;

import java.io.IOException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.qora.account.PrivateKeyAccount;
import org.qora.crypto.Crypto;
import org.qora.utils.BIP39;
import org.qora.utils.Base58;

import com.google.common.primitives.Bytes;

public class VanityGen {

	// From utils.Base58:
	private static final String ALPHABET_STR = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

	private static String prefix = null;

	private static void usage() {
		System.err.println("Usage: Vanitygen [-t threads] <leading-chars>");
		System.err.println("Example: VanityGen Qcat");
		System.exit(1);
	}

	private static class Generator implements Runnable {
		public void run() {
			Random random = new SecureRandom();
			byte[] entropy = new byte[16];

			while (true) {
				// Generate entropy internally
				random.nextBytes(entropy);

				// Use SHA256 to generate more bits
				byte[] hash = Crypto.digest(entropy);

				// Append first 4 bits from hash to end. (Actually 8 bits but we only use 4).
				byte checksum = (byte) (hash[0] & 0xf0);
				byte[] entropy132 = Bytes.concat(entropy, new byte[] { checksum });

				String mnemonic = BIP39.encode(entropy132, "en");

				PrivateKeyAccount account = new PrivateKeyAccount(null, hash);

				if (!account.getAddress().startsWith(prefix))
					continue;

				System.out.println(String.format("Address: %s, public key: %s, private key: %s, mnemonic: %s",
						account.getAddress(), Base58.encode(account.getPublicKey()), Base58.encode(hash), mnemonic));
				System.out.flush();
			}
		}
	}

	public static void main(String argv[]) throws IOException {
		if (argv.length == 0)
			usage();

		int threadCount = 1;

		int argIndex = 0;
		while (argIndex < argv.length) {
			String arg = argv[argIndex++];

			if (arg.equals("-t")) {
				if (argIndex >= argv.length)
					usage();

				try {
					threadCount = Integer.parseInt(argv[argIndex++]);
				} catch (NumberFormatException e) {
					usage();
				}

				continue;
			}

			if (prefix != null)
				usage();

			prefix = arg;
			if (!prefix.matches("[" + ALPHABET_STR + "]+")) {
				System.err.println("Only the following characters are allowed:\n" + ALPHABET_STR);
				System.exit(1);
			}
		}

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);

		for (int ti = 0; ti < threadCount; ++ti)
			executor.execute(new Generator());
	}

}
