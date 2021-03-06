package uk.thepragmaticdev.security.token;

import java.time.OffsetDateTime;
import java.util.UUID;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.thepragmaticdev.account.Account;
import uk.thepragmaticdev.security.request.RequestMetadata;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(uniqueConstraints = { @UniqueConstraint(columnNames = "token") })
public class RefreshToken {

  @Id
  private UUID token;

  private OffsetDateTime createdDate;

  private OffsetDateTime expirationTime;

  @Embedded
  private RequestMetadata requestMetadata;

  @ManyToOne
  private Account account;
}
