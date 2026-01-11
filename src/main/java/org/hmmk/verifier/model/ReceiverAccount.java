package org.hmmk.verifier.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Entity representing an allowed receiver account for verification.
 */
@Entity
@Table(name = "receiver_accounts")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReceiverAccount extends PanacheEntity {

    @Column(nullable = false)
    public String bankType;

    @Column(nullable = false)
    public String accountNumber;

    @Column(nullable = false)
    public String accountName;

    public java.time.LocalDateTime lastUsedAt;

    /**
     * Finds all receiver accounts for a specific bank type.
     *
     * @param bankType The bank type (e.g., CBE, TELEBIRR)
     * @return List of ReceiverAccount
     */
    public static List<ReceiverAccount> findByBankType(String bankType) {
        return list("UPPER(bankType) = UPPER(?1)", bankType);
    }

    /**
     * Finds all unique bank types.
     *
     * @return List of bank types
     */
    public static List<String> findUniqueBankTypes() {
        return find("SELECT DISTINCT r.bankType FROM ReceiverAccount r").project(String.class).list();
    }

    /**
     * Gets the next account for a bank type based on the oldest lastUsedAt.
     *
     * @param bankType The bank type
     * @return The next ReceiverAccount or null if none found
     */
    public static ReceiverAccount getNextAccount(String bankType) {
        List<ReceiverAccount> accounts = find("UPPER(bankType) = UPPER(?1) ORDER BY lastUsedAt ASC NULLS FIRST",
                bankType)
                .range(0, 0)
                .list();
        if (accounts.isEmpty()) {
            return null;
        }
        ReceiverAccount next = accounts.get(0);
        next.lastUsedAt = java.time.LocalDateTime.now();
        next.persist();
        return next;
    }
}
