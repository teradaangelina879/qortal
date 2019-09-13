package org.qora;

import java.io.IOException;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.qora.account.PrivateKeyAccount;
import org.qora.account.PublicKeyAccount;
import org.qora.utils.Base58;

public class ProxyKeys {

	private static void usage() {
		System.err.println("Usage: ProxyKeys <private-key> <public-key>");
		System.err.println("Example: ProxyKeys pYQ6DpQBJ2n72TCLJLScEvwhf3boxWy2kQEPynakwpj 6rNn9b3pYRrG9UKqzMWYZ9qa8F3Zgv2mVWrULGHUusb");
		System.exit(1);
	}

	public static void main(String argv[]) throws IOException {
		if (argv.length != 2)
			usage();

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		PrivateKeyAccount privateAccount = new PrivateKeyAccount(null, Base58.decode(argv[0]));
		PublicKeyAccount publicAccount = new PublicKeyAccount(null, Base58.decode(argv[1]));

		byte[] proxyPrivateKey = privateAccount.getProxyPrivateKey(publicAccount.getPublicKey());

		PrivateKeyAccount proxyAccount = new PrivateKeyAccount(null, proxyPrivateKey);

		System.out.println(String.format("Private key account: %s", privateAccount.getAddress()));
		System.out.println(String.format("Public key account: %s", publicAccount.getAddress()));

		System.out.println(String.format("Proxy private key: %s", Base58.encode(proxyAccount.getPrivateKey())));
		System.out.println(String.format("Proxy public key: %s", Base58.encode(proxyAccount.getPublicKey())));
	}

}
