package org.qortal.utils;

import java.math.BigDecimal;

public abstract class Amounts {

	public static String prettyAmount(long amount) {
		StringBuilder stringBuilder = new StringBuilder(20);

		stringBuilder.append(amount / 100000000L);

		stringBuilder.append('.');

		int dpLength = stringBuilder.length();

		stringBuilder.append(amount % 100000000L);

		int paddingRequired = 8 - (stringBuilder.length() - dpLength);
		if (paddingRequired > 0)
			stringBuilder.append("00000000", 0, paddingRequired);

		return stringBuilder.toString();
	}

	public static BigDecimal toBigDecimal(long amount) {
		return BigDecimal.valueOf(amount, 8);
	}

	public static long greatestCommonDivisor(long a, long b) {
		if (b == 0)
			return Math.abs(a);
		else if (a == 0)
			return Math.abs(b);

		while (b != 0) {
			long r = a % b;
			a = b;
			b = r;
		}

		return Math.abs(a);
	}

}
