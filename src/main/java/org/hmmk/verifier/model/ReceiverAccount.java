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

    /**
     * Finds all receiver accounts for a specific bank type.
     *
     * @param bankType The bank type (e.g., CBE, TELEBIRR)
     * @return List of ReceiverAccount
     */
    public static List<ReceiverAccount> findByBankType(String bankType) {
        return list("UPPER(bankType) = UPPER(?1)", bankType);
    }
}
