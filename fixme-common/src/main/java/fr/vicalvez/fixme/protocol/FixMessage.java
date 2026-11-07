package fr.vicalvez.fixme.protocol;

import java.util.Map;

public record FixMessage(
		String senderId,
		String targetId,
		FixMessageType type,
		Map<FixTag, String> fields
) {
	public FixMessage {
		fields = Map.copyOf(fields);
	}

	public String getField(FixTag tag) {
		return fields.get(tag);
	}
}
