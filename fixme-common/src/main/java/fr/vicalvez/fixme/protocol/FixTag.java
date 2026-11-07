package fr.vicalvez.fixme.protocol;

public enum FixTag {

	CHECKSUM(10),

	MSG_TYPE(35),
	SENDER_COMP_ID(49),
	TARGET_COMP_ID(56),

	SYMBOL(55),
	ORDER_QTY(38),
	PRICE(44),
	ORDER_ID(37),
	MARKET(207);

	private final int tag;

	FixTag(int tag) {
		this.tag = tag;
	}

	public int getTag() {
		return tag;
	}
}
