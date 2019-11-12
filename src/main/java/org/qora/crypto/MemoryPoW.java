package org.qora.crypto;

import com.google.common.primitives.Bytes;

public class MemoryPoW {

	private static final int WORK_BUFFER_LENGTH = 4 * 1024 * 1024;
	private static final int WORK_BUFFER_LENGTH_MASK = WORK_BUFFER_LENGTH - 1;

	private static final int HASH_LENGTH = 32;
	private static final int HASH_LENGTH_MASK = HASH_LENGTH - 1;

	public static Integer compute(byte[] data, int start, int range, int difficulty) {
		if (range < 1)
			throw new IllegalArgumentException("range must be at least 1");

		if (difficulty < 1)
			throw new IllegalArgumentException("difficulty must be at least 1");

		// Hash data with SHA256
		byte[] hash = Crypto.digest(data);

		assert hash.length == HASH_LENGTH;

		byte[] perturbedHash = new byte[HASH_LENGTH];
		byte[] workBuffer = new byte[WORK_BUFFER_LENGTH];
		byte[] bufferHash = new byte[HASH_LENGTH];

		// For each nonce...
		for (int nonce = start; nonce < start + range; ++nonce) {
			// Perturb hash using nonce
			int temp = nonce;
			for (int hi = 0; hi < HASH_LENGTH; ++hi) {
				perturbedHash[hi] = (byte) (hash[hi] ^ (temp & 0xff));
				temp >>>= 1;
			}

			// Fill large working memory buffer using hash, further perturbing as we go
			int wanderingBufferOffset = 0;
			byte ch = 0;

			int hashOffset = 0;

			for (int workBufferOffset = 0; workBufferOffset < WORK_BUFFER_LENGTH; workBufferOffset += HASH_LENGTH) {
				System.arraycopy(perturbedHash, 0, workBuffer, workBufferOffset, HASH_LENGTH);

				hashOffset = ++hashOffset & HASH_LENGTH_MASK;

				ch += perturbedHash[hashOffset];

				for (byte hi = 0; hi < HASH_LENGTH; ++hi) {
					byte hashByte = perturbedHash[hi];
					wanderingBufferOffset = (wanderingBufferOffset << 3) ^ (hashByte & 0xff);

					perturbedHash[hi] = (byte) (hashByte ^ (ch + hi));
				}

				workBuffer[wanderingBufferOffset & WORK_BUFFER_LENGTH_MASK] ^= 0xAA;

				// final int finalWanderingBufferOffset = wanderingBufferOffset & WORK_BUFFER_LENGTH_MASK;
				// System.out.println(String.format("wanderingBufferOffset: 0x%08x / 0x%08x - %02d%%", finalWanderingBufferOffset, WORK_BUFFER_LENGTH, finalWanderingBufferOffset * 100 / WORK_BUFFER_LENGTH));
			}

			Bytes.reverse(workBuffer);

			// bufferHash = Crypto.digest(workBuffer);
			System.arraycopy(workBuffer, 0, bufferHash, 0, HASH_LENGTH);

			int hi = 0;
			for (hi = 0; hi < difficulty; ++hi)
				if (bufferHash[hi] != 0)
					break;

			if (hi == difficulty)
				return nonce;

			Thread.yield();
		}

		return null;
	}

}
