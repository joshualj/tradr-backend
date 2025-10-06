package com.tradrbackend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Embeddable
public class CompositeStockId implements Serializable {
    @Column(name = "ticker")
    private String ticker;
    @Column(name = "date")
    private LocalDate date;

    /**
     * JPA requires correctly implemented equals() and hashCode() methods
     * for composite primary keys. While Lombok can generate these,
     * manually implementing them ensures reliability and avoids potential
     * annotation processing issues with IDEs or build tools.
     * This implementation uses the `ticker` and `date` fields to
     * uniquely identify each record.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CompositeStockId that = (CompositeStockId) o;
        return Objects.equals(ticker, that.ticker) && Objects.equals(date, that.date);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticker, date);
    }
}