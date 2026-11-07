package fr.vicalvez.fixme.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Codec for the project's simplified FIX notation.
 *
 * <p>A message is an ASCII sequence of {@code tag=value} fields separated by
 * the FIX SOH byte ({@code \u0001}). The sender ID is the first field and the
 * checksum ({@code 10=NNN}) is the last one. For example:</p>
 *
 * <pre>49=123456\u000156=654321\u000135=BUY\u000155=ACME\u000138=10\u000144=42.50\u0001207=XPAR\u000110=205\u0001</pre>
 */
public final class FixCodec {

	public static final byte SOH = 0x01;
	private static final Set<FixTag> ORDER_FIELDS = Set.of(
			FixTag.ORDER_ID, FixTag.SYMBOL, FixTag.ORDER_QTY, FixTag.MARKET, FixTag.PRICE
	);

	public ByteBuffer encode(FixMessage message) {
		validateMessage(message);

		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		append(payload, FixTag.SENDER_COMP_ID, message.senderId());
		append(payload, FixTag.TARGET_COMP_ID, message.targetId());
		append(payload, FixTag.MSG_TYPE, message.type().name());

		for (FixTag tag : FixTag.values()) {
			if (isTransportTag(tag)) {
				continue;
			}
			String value = message.getField(tag);
			if (value != null) {
				append(payload, tag, value);
			}
		}

		int checksum = checksum(payload.toByteArray());
		append(payload, FixTag.CHECKSUM, String.format("%03d", checksum));
		return ByteBuffer.wrap(payload.toByteArray());
	}

	public FixMessage decode(ByteBuffer buffer) {
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		int checksumStart = checksumStart(bytes);
		validateChecksum(bytes, checksumStart);

		Map<FixTag, String> fields = parseFields(bytes, checksumStart);
		String senderId = required(fields, FixTag.SENDER_COMP_ID);
		String targetId = required(fields, FixTag.TARGET_COMP_ID);
		FixMessageType type = parseType(required(fields, FixTag.MSG_TYPE));

		if (!firstFieldIsSender(bytes)) {
			throw new IllegalArgumentException("A FIX message must start with the sender ID");
		}

		fields.remove(FixTag.SENDER_COMP_ID);
		fields.remove(FixTag.TARGET_COMP_ID);
		fields.remove(FixTag.MSG_TYPE);
		validateMessage(new FixMessage(senderId, targetId, type, fields));
		return new FixMessage(senderId, targetId, type, fields);
	}

	private static Map<FixTag, String> parseFields(byte[] bytes, int checksumStart) {
		Map<FixTag, String> fields = new EnumMap<>(FixTag.class);
		int start = 0;
		while (start < checksumStart) {
			int end = findSoh(bytes, start, checksumStart);
			if (end < 0) {
				throw new IllegalArgumentException("FIX field is not SOH terminated");
			}
			String field = new String(bytes, start, end - start, StandardCharsets.US_ASCII);
			int separator = field.indexOf('=');
			if (separator <= 0 || separator == field.length() - 1) {
				throw new IllegalArgumentException("Invalid FIX field: " + field);
			}
			FixTag tag = tag(Integer.parseInt(field.substring(0, separator)));
			if (fields.put(tag, field.substring(separator + 1)) != null) {
				throw new IllegalArgumentException("Duplicate FIX tag: " + tag.getTag());
			}
			start = end + 1;
		}
		return fields;
	}

	private static void validateMessage(FixMessage message) {
		if (!message.senderId().matches("\\d{6}") || !message.targetId().matches("\\d{6}")) {
			throw new IllegalArgumentException("Sender and target IDs must contain six digits");
		}
		if (message.type() == FixMessageType.BUY || message.type() == FixMessageType.SELL) {
			for (FixTag tag : ORDER_FIELDS) {
				String value = message.getField(tag);
				if (value == null || value.isBlank()) {
					throw new IllegalArgumentException("Missing required order field: " + tag);
				}
			}
		}
		if ((message.type() == FixMessageType.EXECUTED || message.type() == FixMessageType.REJECTED)
				&& (message.getField(FixTag.ORDER_ID) == null || message.getField(FixTag.ORDER_ID).isBlank())) {
			throw new IllegalArgumentException("Missing required execution field: " + FixTag.ORDER_ID);
		}
		for (Map.Entry<FixTag, String> entry : message.fields().entrySet()) {
			if (isTransportTag(entry.getKey()) || entry.getValue() == null
					|| entry.getValue().isBlank() || entry.getValue().indexOf(SOH) >= 0) {
				throw new IllegalArgumentException("Invalid FIX field: " + entry.getKey());
			}
		}
	}

	private static boolean isTransportTag(FixTag tag) {
		return tag == FixTag.SENDER_COMP_ID || tag == FixTag.TARGET_COMP_ID
				|| tag == FixTag.MSG_TYPE || tag == FixTag.CHECKSUM;
	}

	private static void append(ByteArrayOutputStream output, FixTag tag, String value) {
		output.writeBytes((tag.getTag() + "=" + value).getBytes(StandardCharsets.US_ASCII));
		output.write(SOH);
	}

	private static int checksumStart(byte[] bytes) {
		if (bytes.length < 8 || bytes[bytes.length - 1] != SOH) {
			throw new IllegalArgumentException("FIX message must end with a SOH-terminated checksum");
		}
		int start = bytes.length - 1;
		while (start > 0 && bytes[start - 1] != SOH) {
			start--;
		}
		if (start == 0 || bytes.length - start != 7
				|| bytes[start] != '1' || bytes[start + 1] != '0' || bytes[start + 2] != '=') {
			throw new IllegalArgumentException("FIX message must end with tag 10 checksum");
		}
		return start;
	}

	private static void validateChecksum(byte[] bytes, int checksumStart) {
		String checksumValue = new String(bytes, checksumStart + 3, 3, StandardCharsets.US_ASCII);
		if (!checksumValue.matches("\\d{3}") || Integer.parseInt(checksumValue) != checksum(bytes, checksumStart)) {
			throw new IllegalArgumentException("Invalid FIX checksum");
		}
	}

	private static int checksum(byte[] bytes) {
		return checksum(bytes, bytes.length);
	}

	private static int checksum(byte[] bytes, int length) {
		int sum = 0;
		for (int index = 0; index < length; index++) {
			sum += Byte.toUnsignedInt(bytes[index]);
		}
		return sum % 256;
	}

	private static boolean firstFieldIsSender(byte[] bytes) {
		return bytes.length >= 3 && bytes[0] == '4' && bytes[1] == '9' && bytes[2] == '=';
	}

	private static int findSoh(byte[] bytes, int start, int limit) {
		for (int index = start; index < limit; index++) {
			if (bytes[index] == SOH) {
				return index;
			}
		}
		return -1;
	}

	private static FixTag tag(int number) {
		for (FixTag tag : FixTag.values()) {
			if (tag.getTag() == number) {
				return tag;
			}
		}
		throw new IllegalArgumentException("Unsupported FIX tag: " + number);
	}

	private static String required(Map<FixTag, String> fields, FixTag tag) {
		String value = fields.get(tag);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Missing required FIX tag: " + tag.getTag());
		}
		return value;
	}

	private static FixMessageType parseType(String value) {
		try {
			return FixMessageType.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Unsupported FIX message type: " + value, exception);
		}
	}
}
