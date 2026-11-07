package fr.vicalvez.fixme.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reassembles complete simplified FIX messages from arbitrary TCP reads.
 * One framer must be kept for each connection.
 */
public final class FixMessageFramer {

	public static final int DEFAULT_MAX_MESSAGE_SIZE = 64 * 1024;

	private final int maxMessageSize;
	private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

	public FixMessageFramer() {
		this(DEFAULT_MAX_MESSAGE_SIZE);
	}

	public FixMessageFramer(int maxMessageSize) {
		if (maxMessageSize <= 0) {
			throw new IllegalArgumentException("Maximum message size must be positive");
		}
		this.maxMessageSize = maxMessageSize;
	}

	/**
	 * Consumes the received bytes and returns every complete message in arrival
	 * order. An incomplete trailing message remains buffered for the next read.
	 */
	public List<ByteBuffer> accept(ByteBuffer received) {
		byte[] bytes = new byte[received.remaining()];
		received.get(bytes);
		pending.writeBytes(bytes);

		byte[] buffered = pending.toByteArray();
		List<ByteBuffer> messages = new ArrayList<>();
		int messageStart = 0;
		int messageEnd;
		while ((messageEnd = findMessageEnd(buffered, messageStart)) >= 0) {
			int length = messageEnd - messageStart;
			if (length > maxMessageSize) {
				throw tooLarge();
			}
			messages.add(ByteBuffer.wrap(Arrays.copyOfRange(buffered, messageStart, messageEnd)));
			messageStart = messageEnd;
		}

		int remaining = buffered.length - messageStart;
		if (remaining > maxMessageSize) {
			throw tooLarge();
		}
		pending.reset();
		pending.write(buffered, messageStart, remaining);
		return messages;
	}

	private IllegalArgumentException tooLarge() {
		pending.reset();
		return new IllegalArgumentException("FIX message exceeds " + maxMessageSize + " bytes");
	}

	private static int findMessageEnd(byte[] bytes, int messageStart) {
		for (int index = messageStart + 1; index + 6 < bytes.length; index++) {
			if (bytes[index - 1] == FixCodec.SOH
					&& bytes[index] == '1'
					&& bytes[index + 1] == '0'
					&& bytes[index + 2] == '='
					&& isDigit(bytes[index + 3])
					&& isDigit(bytes[index + 4])
					&& isDigit(bytes[index + 5])
					&& bytes[index + 6] == FixCodec.SOH) {
				return index + 7;
			}
		}
		return -1;
	}

	private static boolean isDigit(byte value) {
		return value >= '0' && value <= '9';
	}
}
