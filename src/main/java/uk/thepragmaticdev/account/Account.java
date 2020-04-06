package uk.thepragmaticdev.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.validation.Valid;
import javax.validation.constraints.Email;
import javax.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.thepragmaticdev.api.Model;
import uk.thepragmaticdev.kms.ApiKey;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(uniqueConstraints = { @UniqueConstraint(columnNames = "id") })
public class Account implements Model {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @JsonIgnore
  private Long id;

  @Column(unique = true, nullable = false)
  @Email(message = "Username is not a valid email.")
  private String username;

  @Size(min = 8, message = "Minimum password length: 8 characters")
  @JsonProperty(access = Access.WRITE_ONLY)
  private String password;

  private String fullName;

  private Boolean emailSubscriptionEnabled;

  private Boolean billingAlertEnabled;

  private OffsetDateTime createdDate; // generated

  @ElementCollection(fetch = FetchType.EAGER)
  @Column(name = "name")
  @Enumerated(EnumType.STRING)
  @JsonIgnore
  private List<Role> roles;

  @Valid
  @OneToMany(cascade = { CascadeType.ALL }, orphanRemoval = true)
  @JoinColumn(name = "account_id")
  @JsonIgnore
  private Collection<ApiKey> apiKeys;
}