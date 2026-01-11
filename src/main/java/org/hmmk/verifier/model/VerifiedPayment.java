package org.hmmk.verifier.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity representing a verified payment receipt stored in the database.
 */
@Entity
@Table(name = "verified_payments")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifiedPayment extends PanacheEntity {

    @Column(nullable = false)
    public String senderId;

    @Column(nullable = false)
    public String reference;

    @Column(nullable = false)
    public String bankType;

    public BigDecimal amount;

    public String payerName;

    public LocalDateTime transactionDate;

    public String rawData; // Store the full JSON or summary from the bank service

    public String merchantReferenceId;

    public String receiverAccount;

    public String receiverName;

    public String merchantReference;

    public LocalDateTime verifiedAt;

    /**
     * Checks if a transaction with the given bank type and reference already
     * exists.
     *
     * @param bankType  The bank type
     * @param reference The transaction reference
     * @return true if exists, false otherwise
     */
    public static boolean exists(String bankType, String reference) {
        return find("bankType = ?1 and reference = ?2", bankType, reference).count() > 0;
    }
}
