package uk.thepragmaticdev.account;

import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.thepragmaticdev.billing.BillingService;
import uk.thepragmaticdev.exception.ApiException;
import uk.thepragmaticdev.exception.code.AccountCode;
import uk.thepragmaticdev.exception.code.AuthCode;
import uk.thepragmaticdev.exception.code.CriticalCode;
import uk.thepragmaticdev.log.billing.BillingLog;
import uk.thepragmaticdev.log.billing.BillingLogService;
import uk.thepragmaticdev.log.security.SecurityLog;
import uk.thepragmaticdev.log.security.SecurityLogService;
import uk.thepragmaticdev.security.token.RefreshToken;

@Service
public class AccountService {

  private final AccountRepository accountRepository;

  private final BillingLogService billingLogService;

  private final SecurityLogService securityLogService;

  /**
   * Service for managing accounts. Billing and security logs related to an
   * authorised account may also be downloaded.
   * 
   * @param accountRepository  The data access repository for accounts
   * @param billingLogService  The service for accessing billing logs
   * @param securityLogService The service for accessing security logs
   */
  @Autowired
  public AccountService(//
      AccountRepository accountRepository, //
      BillingService billingService, //
      BillingLogService billingLogService, //
      SecurityLogService securityLogService) {
    this.accountRepository = accountRepository;
    this.billingLogService = billingLogService;
    this.securityLogService = securityLogService;
  }

  /**
   * Find the currently authenticated account.
   * 
   * @param username The authenticated account username
   * @return The authenticated account
   */
  public Account findAuthenticatedAccount(String username) {
    return accountRepository.findByUsername(username)
        .orElseThrow(() -> new ApiException(AccountCode.USERNAME_NOT_FOUND));
  }

  /**
   * Find and account by it's password reset token.
   * 
   * @param token The the password reset token
   * @return A matching account or empty if not found
   */
  public Optional<Account> findByPasswordResetToken(String token) {
    return accountRepository.findByPasswordResetToken(token);
  }

  /**
   * Check to see if a username already exists.
   * 
   * @param username The account username
   * @return True if the given username already exists
   */
  public boolean existsByUsername(String username) {
    return accountRepository.existsByUsername(username);
  }

  /**
   * Create a new account. A stripe customer is also created and associated with
   * the account. Disables email subscription and billing alerts by default to
   * comply with GDPR.
   * 
   * @param username        The account username
   * @param encodedPassword An encoded password
   * @param roles           A list of granted roles
   * @return The newly created account
   */
  public Account create(String username, String encodedPassword, List<Role> roles) {
    var account = new Account();
    account.setAvatar(generateAvatar());
    account.setUsername(username);
    account.setPassword(encodedPassword);
    account.setRoles(roles);
    account.setEmailSubscriptionEnabled(false);
    account.setBillingAlertEnabled(false);
    account.setBillingAlertAmount((short) 0);
    account.setCreatedDate(OffsetDateTime.now());
    return accountRepository.save(account);
  }

  private short generateAvatar() {
    return (short) new Random().nextInt(16);
  }

  /**
   * Update all mutable fields of an authenticated account if a change is
   * detected.
   * 
   * @param username                 The authenticated account username
   * @param fullName                 An updated fullname
   * @param emailSubscriptionEnabled An updated email subscription
   * @param billingAlertEnabled      An updated billing alert
   * @param billingAlertAmount       An updated billing alert amount
   * @return The updated account
   */
  @Transactional
  public Account update(String username, String fullName, Boolean emailSubscriptionEnabled, Boolean billingAlertEnabled,
      short billingAlertAmount) {
    var authenticatedAccount = findAuthenticatedAccount(username);
    updateFullName(authenticatedAccount, fullName);
    updateBillingAlertEnabled(authenticatedAccount, billingAlertEnabled);
    updateBillingAlertAmount(authenticatedAccount, billingAlertAmount);
    updateEmailSubscriptionEnabled(authenticatedAccount, emailSubscriptionEnabled);
    return accountRepository.save(authenticatedAccount);
  }

  private void updateFullName(Account account, String fullName) {
    if (fullName == null) {
      return;
    }
    if (account.getFullName() == null ? fullName != null : !account.getFullName().equals(fullName)) {
      securityLogService.fullname(account);
      account.setFullName(fullName);
    }
  }

  private void updateBillingAlertEnabled(Account account, Boolean billingAlertEnabled) {
    if (billingAlertEnabled == null) {
      return;
    }
    if (!account.getBillingAlertEnabled().equals(billingAlertEnabled)) {
      securityLogService.billingAlertEnabled(account, billingAlertEnabled);
      account.setBillingAlertEnabled(billingAlertEnabled);
    }
  }

  private void updateBillingAlertAmount(Account account, short billingAlertAmount) {
    if (account.getBillingAlertAmount() != billingAlertAmount) {
      securityLogService.billingAlertAmount(account);
      account.setBillingAlertAmount(billingAlertAmount);
    }
  }

  private void updateEmailSubscriptionEnabled(Account account, Boolean emailSubscriptionEnabled) {
    if (emailSubscriptionEnabled == null) {
      return;
    }
    if (!account.getEmailSubscriptionEnabled().equals(emailSubscriptionEnabled)) {
      securityLogService.emailSubscriptionEnabled(account, emailSubscriptionEnabled);
      account.setEmailSubscriptionEnabled(emailSubscriptionEnabled);
    }
  }

  /**
   * Find the latest billing logs for the account.
   * 
   * @param pageable The pagination information
   * @param username The authenticated account username
   * @return A page of the latest billing logs
   */
  public Page<BillingLog> billingLogs(Pageable pageable, String username) {
    var authenticatedAccount = findAuthenticatedAccount(username);
    return billingLogService.findAllByAccountId(pageable, authenticatedAccount);
  }

  /**
   * Download the latest billing logs for the account as a CSV file.
   * 
   * @param writer   The csv writer
   * @param username The authenticated account username
   */
  public void downloadBillingLogs(StatefulBeanToCsv<BillingLog> writer, String username) {
    var authenticatedAccount = findAuthenticatedAccount(username);
    try {
      writer.write(billingLogService.findAllByAccountId(authenticatedAccount));
      securityLogService.downloadBillingLogs(authenticatedAccount);
    } catch (CsvDataTypeMismatchException | CsvRequiredFieldEmptyException ex) {
      throw new ApiException(CriticalCode.CSV_WRITING_ERROR);
    }
  }

  /**
   * Find the latest security logs for the account.
   * 
   * @param pageable The pagination information
   * @param username The authenticated account username
   * @return A page of the latest security logs
   */
  public Page<SecurityLog> securityLogs(Pageable pageable, String username) {
    var authenticatedAccount = findAuthenticatedAccount(username);
    return securityLogService.findAllByAccountId(pageable, authenticatedAccount);
  }

  /**
   * Download the latest security logs for the account as a CSV file.
   * 
   * @param writer   The csv writer
   * @param username The authenticated account username
   */
  public void downloadSecurityLogs(StatefulBeanToCsv<SecurityLog> writer, String username) {
    var authenticatedAccount = findAuthenticatedAccount(username);
    try {
      writer.write(securityLogService.findAllByAccountId(authenticatedAccount));
      securityLogService.downloadSecurityLogs(authenticatedAccount);
    } catch (CsvDataTypeMismatchException | CsvRequiredFieldEmptyException ex) {
      throw new ApiException(CriticalCode.CSV_WRITING_ERROR);
    }
  }

  /**
   * Find all signed-in devices associated with the account.
   * 
   * @param username The authenticated account username
   * @return A list of signed-in devices
   */
  public List<RefreshToken> findAllActiveDevices(String username) {
    var account = findAuthenticatedAccount(username);
    return account.getRefreshTokens().stream().filter(r -> r.getExpirationTime().isAfter(OffsetDateTime.now()))
        .collect(Collectors.toList());
  }

  /**
   * Delete all signed-in devices accociated with the account, forcing a global
   * signout.
   * 
   * @param username The authenticated account username
   */
  public void deleteAllActiveDevices(String username) {
    var account = findAuthenticatedAccount(username);
    account.getRefreshTokens().clear();
    accountRepository.save(account);
  }

  /**
   * Persists the accounts new stripe customer id.
   * 
   * @param persistedAccount An authenticated account entity
   * @param stripeCustomerId The id of the new stripe customer
   */
  public void saveCustomerId(Account persistedAccount, String stripeCustomerId) {
    persistedAccount.setStripeCustomerId(stripeCustomerId);
    accountRepository.save(persistedAccount);
  }

  /**
   * Persists the accounts new stripe subscription.
   * 
   * @param persistedAccount         An authenticated account entity
   * @param stripeSubscriptionId     The id of the new stripe subscription
   * @param stripeSubscriptionItemId The id of the new stripe subscription item
   */
  public void saveSubscription(Account persistedAccount, String stripeSubscriptionId, String stripeSubscriptionItemId) {
    persistedAccount.setStripeSubscriptionId(stripeSubscriptionId);
    persistedAccount.setStripeSubscriptionItemId(stripeSubscriptionItemId);
    accountRepository.save(persistedAccount);
  }

  /**
   * Removes the accounts stripe subscription.
   * 
   * @param persistedAccount An authenticated account entity
   */
  public void cancelSubscription(Account persistedAccount) {
    persistedAccount.setStripeSubscriptionId(null);
    persistedAccount.setStripeSubscriptionItemId(null);
    accountRepository.save(persistedAccount);
  }

  /**
   * Create a new password reset token which is valid for 24hours.
   * 
   * @param username A valid account username
   * @return An account with a new password reset token
   */
  public Account createPasswordResetToken(String username) {
    var authenticatedAccount = findAuthenticatedAccount(username);
    authenticatedAccount.setPasswordResetToken(UUID.randomUUID().toString());
    authenticatedAccount.setPasswordResetTokenExpire(OffsetDateTime.now().plusDays(1));
    return accountRepository.save(authenticatedAccount);
  }

  /**
   * Reset old password to new password for the requested account.
   * 
   * @param encodedPassword The new encoded password
   * @param token           The generated password reset token from the /forgot
   *                        endpoint
   * @return An account with no password reset token
   */
  public Account resetPasswordResetToken(String encodedPassword, String token) {
    var persistedAccount = findByPasswordResetToken(token)
        .orElseThrow(() -> new ApiException(AuthCode.PASSWORD_RESET_TOKEN_NOT_FOUND));
    if (OffsetDateTime.now().isAfter(persistedAccount.getPasswordResetTokenExpire())) {
      throw new ApiException(AuthCode.PASSWORD_RESET_TOKEN_EXPIRED);
    }
    persistedAccount.setPassword(encodedPassword);
    persistedAccount.setPasswordResetToken(null);
    persistedAccount.setPasswordResetTokenExpire(null);
    return accountRepository.save(persistedAccount);
  }
}
