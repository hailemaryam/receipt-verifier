package org.hmmk.verifier.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * Entity representing a failed verification attempt.
 */
@Entity
@Table(name = "failed_verifications")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedVerification extends PanacheEntity {

    public String senderId;

    public String reference;

    public String bankType;

    public String reason;

    public String merchantReferenceId;

    public LocalDateTime failedAt;
}
