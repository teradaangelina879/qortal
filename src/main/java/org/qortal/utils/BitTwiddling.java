package org.qortal.utils;

public class BitTwiddling {

	/**
	 * Returns bit-mask for values up to, and including, <tt>maxValue</tt>.
	 * <p>
	 * e.g. for values up to 5 (0101b) this returns a mask of 7 (0111b).
	 * <p>
	 * Based on Integer.highestOneBit.
	 * 
	 * @param maxValue
	 * @return mask
	 */
	public static int calcMask(int maxValue) {
		maxValue |= maxValue >> 1;
		maxValue |= maxValue >> 2;
		maxValue |= maxValue >> 4;
		maxValue |= maxValue >> 8;
		maxValue |= maxValue >> 16;
		return maxValue;
	}

	/** Convert int to little-endian byte array */
	public static byte[] toLEByteArray(int value) {
		return new byte[] { (byte) (value), (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24) };
	}

	/** Convert little-endian bytes to int */
	public static int fromLEBytes(byte[] bytes, int offset) {
		return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8 | (bytes[offset + 2] & 0xff) << 16 | (bytes[offset + 3] & 0xff) << 24;
	}

}
