package org.qora.test;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.qora.crypto.Crypto;
import org.qora.data.block.BlockSummaryData;
import org.qora.transform.Transformer;
import org.qora.transform.block.BlockTransformer;
import org.junit.Test;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;

public class ChainWeightTests {

	private static final int ACCOUNTS_COUNT_SHIFT = Transformer.PUBLIC_KEY_LENGTH * 8;
	private static final int CHAIN_WEIGHT_SHIFT = 8;
	private static final Random RANDOM = new Random();

	private static final BigInteger MAX_DISTANCE;
	static {
		byte[] maxValue = new byte[Transformer.PUBLIC_KEY_LENGTH];
		Arrays.fill(maxValue, (byte) 0xFF);
		MAX_DISTANCE = new BigInteger(1, maxValue);
	}


	private static byte[] perturbPublicKey(int height, byte[] publicKey) {
		return Crypto.digest(Bytes.concat(Longs.toByteArray(height), publicKey));
	}

	private static BigInteger calcKeyDistance(int parentHeight, byte[] parentGeneratorKey, byte[] publicKey) {
		byte[] idealKey = perturbPublicKey(parentHeight, parentGeneratorKey);
		byte[] perturbedKey = perturbPublicKey(parentHeight + 1, publicKey);

		BigInteger keyDistance = MAX_DISTANCE.subtract(new BigInteger(idealKey).subtract(new BigInteger(perturbedKey)).abs());
		return keyDistance;
	}

	private static BigInteger calcBlockWeight(int parentHeight, byte[] parentGeneratorKey, BlockSummaryData blockSummaryData) {
		BigInteger keyDistance = calcKeyDistance(parentHeight, parentGeneratorKey, blockSummaryData.getMinterPublicKey());
		BigInteger weight = BigInteger.valueOf(blockSummaryData.getOnlineAccountsCount()).shiftLeft(ACCOUNTS_COUNT_SHIFT).add(keyDistance);
		return weight;
	}

	private static BigInteger calcChainWeight(int commonBlockHeight, byte[] commonBlockGeneratorKey, List<BlockSummaryData> blockSummaries) {
		BigInteger cumulativeWeight = BigInteger.ZERO;
		int parentHeight = commonBlockHeight;
		byte[] parentGeneratorKey = commonBlockGeneratorKey;

		for (BlockSummaryData blockSummaryData : blockSummaries) {
			cumulativeWeight = cumulativeWeight.shiftLeft(CHAIN_WEIGHT_SHIFT).add(calcBlockWeight(parentHeight, parentGeneratorKey, blockSummaryData));
			parentHeight = blockSummaryData.getHeight();
			parentGeneratorKey = blockSummaryData.getMinterPublicKey();
		}

		return cumulativeWeight;
	}

	private static BlockSummaryData genBlockSummary(int height) {
		byte[] generatorPublicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
		RANDOM.nextBytes(generatorPublicKey);

		byte[] signature = new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH];
		RANDOM.nextBytes(signature);

		int onlineAccountsCount = RANDOM.nextInt(1000);

		return new BlockSummaryData(height, signature, generatorPublicKey, onlineAccountsCount);
	}

	private static List<BlockSummaryData> genBlockSummaries(int count, BlockSummaryData commonBlockSummary) {
		List<BlockSummaryData> blockSummaries = new ArrayList<>();
		blockSummaries.add(commonBlockSummary);

		final int commonBlockHeight = commonBlockSummary.getHeight();

		for (int i = 1; i <= count; ++i)
			blockSummaries.add(genBlockSummary(commonBlockHeight + i));

		return blockSummaries;
	}

	// Check that more online accounts beats a better key
	@Test
	public void testMoreAccountsBlock() {
		final int parentHeight = 1;
		final byte[] parentGeneratorKey = new byte[Transformer.PUBLIC_KEY_LENGTH];

		int betterAccountsCount = 100;
		int worseAccountsCount = 20;

		byte[] betterKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
		betterKey[0] = 0x41;

		byte[] worseKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
		worseKey[0] = 0x23;

		BigInteger betterKeyDistance = calcKeyDistance(parentHeight, parentGeneratorKey, betterKey);
		BigInteger worseKeyDistance = calcKeyDistance(parentHeight, parentGeneratorKey, worseKey);
		assertEquals("hard-coded keys are wrong", 1, betterKeyDistance.compareTo(worseKeyDistance));

		BlockSummaryData betterBlockSummary = new BlockSummaryData(parentHeight + 1, null, worseKey, betterAccountsCount);
		BlockSummaryData worseBlockSummary = new BlockSummaryData(parentHeight + 1, null, betterKey, worseAccountsCount);

		BigInteger betterBlockWeight = calcBlockWeight(parentHeight, parentGeneratorKey, betterBlockSummary);
		BigInteger worseBlockWeight = calcBlockWeight(parentHeight, parentGeneratorKey, worseBlockSummary);

		assertEquals("block weights are wrong", 1, betterBlockWeight.compareTo(worseBlockWeight));
	}

	// Check that a longer chain beats a shorter chain
	@Test
	public void testLongerChain() {
		final int commonBlockHeight = 1;
		BlockSummaryData commonBlockSummary = genBlockSummary(commonBlockHeight);
		byte[] commonBlockGeneratorKey = commonBlockSummary.getMinterPublicKey();

		List<BlockSummaryData> shorterChain = genBlockSummaries(3, commonBlockSummary);
		List<BlockSummaryData> longerChain = genBlockSummaries(shorterChain.size() + 1, commonBlockSummary);

		BigInteger shorterChainWeight = calcChainWeight(commonBlockHeight, commonBlockGeneratorKey, shorterChain);
		BigInteger longerChainWeight = calcChainWeight(commonBlockHeight, commonBlockGeneratorKey, longerChain);

		assertEquals("longer chain should have greater weight", 1, longerChainWeight.compareTo(shorterChainWeight));
	}

}
