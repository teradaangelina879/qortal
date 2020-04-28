package org.qortal.crypto;

import java.nio.ByteBuffer;

import com.google.common.primitives.Bytes;

public class MemoryPoW {

	private static final int HASH_LENGTH = 32;
	private static final int HASH_LENGTH_MASK = HASH_LENGTH - 1;

	public static Integer compute(byte[] data, int workBufferLength, int start, int range, int difficulty) {
		if (range < 1)
			throw new IllegalArgumentException("range must be at least 1");

		if (difficulty < 1)
			throw new IllegalArgumentException("difficulty must be at least 1");

		final int workBufferLengthMask = workBufferLength - 1;

		// Hash data with SHA256
		byte[] hash = Crypto.digest(data);

		assert hash.length == HASH_LENGTH;

		byte[] perturbedHash = new byte[HASH_LENGTH];
		byte[] workBuffer = new byte[workBufferLength];
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

			for (int workBufferOffset = 0; workBufferOffset < workBufferLength; workBufferOffset += HASH_LENGTH) {
				System.arraycopy(perturbedHash, 0, workBuffer, workBufferOffset, HASH_LENGTH);

				hashOffset = ++hashOffset & HASH_LENGTH_MASK;

				ch += perturbedHash[hashOffset];

				for (byte hi = 0; hi < HASH_LENGTH; ++hi) {
					byte hashByte = perturbedHash[hi];
					wanderingBufferOffset = (wanderingBufferOffset << 3) ^ (hashByte & 0xff);

					perturbedHash[hi] = (byte) (hashByte ^ (ch + hi));
				}

				workBuffer[wanderingBufferOffset & workBufferLengthMask] ^= 0xAA;

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

	public static Integer compute2(byte[] data, int workBufferLength, long difficulty) {
		// Hash data with SHA256
		byte[] hash = Crypto.digest(data);

		long[] longHash = new long[4];
		ByteBuffer byteBuffer = ByteBuffer.wrap(hash);
		longHash[0] = byteBuffer.getLong();
		longHash[1] = byteBuffer.getLong();
		longHash[2] = byteBuffer.getLong();
		longHash[3] = byteBuffer.getLong();
		byteBuffer = null;

		int longBufferLength = workBufferLength / 8;
		long[] workBuffer = new long[longBufferLength / 8];
		long[] state = new long[4];

		long seed = 8682522807148012L;
		long seedMultiplier = 1181783497276652981L;

		// For each nonce...
		int nonce = -1;
		long result = 0;
		do {
			++nonce;

			seed *= seedMultiplier; // per nonce

			state[0] = longHash[0] ^ seed;
			state[1] = longHash[1] ^ seed;
			state[2] = longHash[2] ^ seed;
			state[3] = longHash[3] ^ seed;

			// Fill work buffer with random
			for (int i = 0; i < workBuffer.length; ++i)
				workBuffer[i] = xoshiro256p(state);

			// Random bounce through whole buffer
			result = workBuffer[0];
			for (int i = 0; i < 1024; ++i) {
				int index = (int) (xoshiro256p(state) & Integer.MAX_VALUE) % workBuffer.length;
				result ^= workBuffer[index];
			}

			// Return if final value > difficulty
		} while (Long.numberOfLeadingZeros(result) < difficulty);

		return nonce;
	}

	public static boolean verify2(byte[] data, int workBufferLength, long difficulty, int nonce) {
		// Hash data with SHA256
		byte[] hash = Crypto.digest(data);

		long[] longHash = new long[4];
		ByteBuffer byteBuffer = ByteBuffer.wrap(hash);
		longHash[0] = byteBuffer.getLong();
		longHash[1] = byteBuffer.getLong();
		longHash[2] = byteBuffer.getLong();
		longHash[3] = byteBuffer.getLong();
		byteBuffer = null;

		int longBufferLength = workBufferLength / 8;
		long[] workBuffer = new long[longBufferLength / 8];
		long[] state = new long[4];

		long seed = 8682522807148012L;
		long seedMultiplier = 1181783497276652981L;

		for (int i = 0; i <= nonce; ++i)
			seed *= seedMultiplier;

		state[0] = longHash[0] ^ seed;
		state[1] = longHash[1] ^ seed;
		state[2] = longHash[2] ^ seed;
		state[3] = longHash[3] ^ seed;

		// Fill work buffer with random
		for (int i = 0; i < workBuffer.length; ++i)
			workBuffer[i] = xoshiro256p(state);

		// Random bounce through whole buffer
		long result = workBuffer[0];
		for (int i = 0; i < 1024; ++i) {
			int index = (int) (xoshiro256p(state) & Integer.MAX_VALUE) % workBuffer.length;
			result ^= workBuffer[index];
		}

		return Long.numberOfLeadingZeros(result) >= difficulty;
	}

	private static final long xoshiro256p(long[] state) {
		final long result = state[0] + state[3];
		final long temp = state[1] << 17;

		state[2] ^= state[0];
		state[3] ^= state[1];
		state[1] ^= state[2];
		state[0] ^= state[3];

		state[2] ^= temp;
		state[3] = (state[3] << 45) | (state[3] >>> (64 - 45)); // rol64(s[3], 45);

		return result;
	}

}
