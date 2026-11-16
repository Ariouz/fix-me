package fr.vicalvez.fixme.persistence;

import java.math.BigDecimal;

public record InventoryRecord(String symbol, long quantity, BigDecimal price) {
}
