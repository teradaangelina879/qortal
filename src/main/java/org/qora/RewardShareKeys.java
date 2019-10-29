package org.qora;

import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.qora.account.PrivateKeyAccount;
import org.qora.account.PublicKeyAccount;
import org.qora.utils.Base58;

public class RewardShareKeys {

	private static void usage() {
		System.err.println("Usage: RewardShareKeys <private-key> <public-key>");
		System.err.println("Example: RewardShareKeys pYQ6DpQBJ2n72TCLJLScEvwhf3boxWy2kQEPynakwpj 6rNn9b3pYRrG9UKqzMWYZ9qa8F3Zgv2mVWrULGHUusb");
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length != 2)
			usage();

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		PrivateKeyAccount privateAccount = new PrivateKeyAccount(null, Base58.decode(args[0]));
		PublicKeyAccount publicAccount = new PublicKeyAccount(null, Base58.decode(args[1]));

		byte[] rewardSharePrivateKey = privateAccount.getRewardSharePrivateKey(publicAccount.getPublicKey());
		byte[] rewardSharePublicKey = PrivateKeyAccount.toPublicKey(rewardSharePrivateKey);

		System.out.println(String.format("Private key account: %s", privateAccount.getAddress()));
		System.out.println(String.format("Public key account: %s", publicAccount.getAddress()));

		System.out.println(String.format("Reward-share private key: %s", Base58.encode(rewardSharePrivateKey)));
		System.out.println(String.format("Reward-share public key: %s", Base58.encode(rewardSharePublicKey)));
	}

}
