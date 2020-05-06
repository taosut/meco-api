package uk.thepragmaticdev.account.dto.response;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountMeResponse {

  private String username;

  private String fullName;

  private boolean emailSubscriptionEnabled;

  private boolean billingAlertEnabled;

  private OffsetDateTime createdDate;
}