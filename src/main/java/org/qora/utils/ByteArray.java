package org.qora.utils;

public class ByteArray implements Comparable<ByteArray> {

	private int hash;
	public final byte[] value;

	public ByteArray(byte[] value) {
		this.value = value;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other)
			return true;

		if (other instanceof ByteArray)
			return this.compareTo((ByteArray) other) == 0;

		if (other instanceof byte[])
			return this.compareTo((byte[]) other) == 0;

		return false;
	}

	@Override
	public int hashCode() {
		int h = hash;
		if (h == 0 && value.length > 0) {
			byte[] val = value;

			for (int i = 0; i < val.length; ++i)
				h = 31 * h + val[i];

			hash = h;
		}
		return h;
	}

	@Override
	public int compareTo(ByteArray other) {
		return this.compareTo(other.value);
	}

	public int compareTo(byte[] otherValue) {
		byte[] val = value;

		if (val.length < otherValue.length)
			return -1;

		if (val.length > otherValue.length)
			return 1;

		for (int i = 0; i < val.length; ++i) {
			int a = val[i] & 0xFF;
			int b = otherValue[i] & 0xFF;
			if (a < b)
				return -1;
			if (a > b)
				return 1;
		}

		return 0;
	}

}
