package org.qortal.test;

import org.junit.Test;
import org.qortal.crypto.MemoryPoW;

import static org.junit.Assert.*;

import java.util.Random;

public class MemoryPoWTests {

	private static final int workBufferLength = 8 * 1024 * 1024;
	private static final int start = 0;
	private static final int range = 1000000;
	private static final int difficulty = 11;

	@Test
	public void testCompute() {
		Random random = new Random();

		byte[] data = new byte[256];
		random.nextBytes(data);

		long startTime = System.currentTimeMillis();

		// Integer nonce = MemoryPoW.compute(data, workBufferLength, start, range, difficulty);
		int nonce = MemoryPoW.compute2(data, workBufferLength, difficulty);

		long finishTime = System.currentTimeMillis();

		System.out.println(String.format("Memory-hard PoW (buffer size: %dKB, range: %d, leading zeros: %d) took %dms", workBufferLength / 1024, range, difficulty, finishTime - startTime));

		assertNotNull(nonce);

		System.out.println(String.format("nonce: %d", nonce));
	}

	@Test
	public void testMultipleComputes() {
		Random random = new Random();

		byte[] data = new byte[256];
		int[] times = new int[100];

		int timesS1 = 0;
		int timesS2 = 0;

		int maxNonce = 0;

		for (int i = 0; i < times.length; ++i) {
			random.nextBytes(data);

			long startTime = System.currentTimeMillis();
			int nonce = MemoryPoW.compute2(data, workBufferLength, difficulty);
			times[i] = (int) (System.currentTimeMillis() - startTime);

			timesS1 += times[i];
			timesS2 += (times[i] * times[i]);

			if (nonce > maxNonce)
				maxNonce = nonce;
		}

		double stddev = Math.sqrt( ((double) times.length * timesS2 - timesS1 * timesS1) / ((double) times.length * (times.length - 1)) );
		System.out.println(String.format("%d timings, mean: %d ms, stddev: %.2f ms", times.length, timesS1 / times.length, stddev));

		System.out.println(String.format("Max nonce: %d", maxNonce));
	}

	@Test
	public void testKnownCompute2() {
		byte[] data = new byte[] { (byte) 0xaa, (byte) 0xbb, (byte) 0xcc };

		int difficulty = 12;
		int expectedNonce = 4013;
		int nonce = MemoryPoW.compute2(data, 8 * 1024 * 1024, difficulty);

		System.out.println(String.format("Difficulty %d, nonce: %d", difficulty, nonce));
		assertEquals(expectedNonce, nonce);

		difficulty = 16;
		expectedNonce = 41029;
		nonce = MemoryPoW.compute2(data, 8 * 1024 * 1024, difficulty);

		System.out.println(String.format("Difficulty %d, nonce: %d", difficulty, nonce));
		assertEquals(expectedNonce, nonce);
	}

}
