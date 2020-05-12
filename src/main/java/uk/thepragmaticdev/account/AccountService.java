package uk.thepragmaticdev.account;

import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import uk.thepragmaticdev.email.EmailService;
import uk.thepragmaticdev.exception.ApiException;
import uk.thepragmaticdev.exception.code.AccountCode;
import uk.thepragmaticdev.exception.code.CriticalCode;
import uk.thepragmaticdev.log.billing.BillingLog;
import uk.thepragmaticdev.log.billing.BillingLogService;
import uk.thepragmaticdev.log.security.SecurityLog;
import uk.thepragmaticdev.log.security.SecurityLogService;
import uk.thepragmaticdev.security.JwtTokenProvider;
import uk.thepragmaticdev.security.request.RequestMetadataService;

@Service
public class AccountService {

  private final AccountRepository accountRepository;

  private final BillingLogService billingLogService;

  private final SecurityLogService securityLogService;

  private final EmailService emailService;

  private final RequestMetadataService requestMetadataService;

  private final PasswordEncoder passwordEncoder;

  private final JwtTokenProvider jwtTokenProvider;

  private final AuthenticationManager authenticationManager;

  /**
   * Service for creating, authorizing and updating accounts. Billing and security
   * logs related to an authorised account may also be downloaded.
   * 
   * @param accountRepository      The data access repository for accounts
   * @param billingLogService      The service for accessing billing logs
   * @param securityLogService     The service for accessing security logs
   * @param emailService           The service for sending emails
   * @param requestMetadataService The service for gathering ip and location
   *                               information
   * @param passwordEncoder        The service for encoding passwords
   * @param jwtTokenProvider       The provider for creating, validating tokens
   * @param authenticationManager  The manager for authentication providers
   */
  @Autowired
  public AccountService(//
      AccountRepository accountRepository, //
      BillingLogService billingLogService, //
      SecurityLogService securityLogService, //
      EmailService emailService, //
      RequestMetadataService requestMetadataService, //
      PasswordEncoder passwordEncoder, //
      JwtTokenProvider jwtTokenProvider, //
      AuthenticationManager authenticationManager) {
    this.accountRepository = accountRepository;
    this.billingLogService = billingLogService;
    this.securityLogService = securityLogService;
    this.emailService = emailService;
    this.requestMetadataService = requestMetadataService;
    this.passwordEncoder = passwordEncoder;
    this.jwtTokenProvider = jwtTokenProvider;
    this.authenticationManager = authenticationManager;
  }

  /**
   * Authorize an account. If signing in from an unfamiliar ip or device the user
   * will be notified by email.
   * 
   * @param username The username of an account attemping to signin
   * @param password The password of an account attemping to signin
   * @return An authentication token
   */
  public String signin(String username, String password, HttpServletRequest request) {
    String token;
    try {
      authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
      var persistedAccount = findAuthenticatedAccount(username);
      token = jwtTokenProvider.createToken(username, persistedAccount.getRoles());
      requestMetadataService.verifyRequest(persistedAccount, request);
    } catch (AuthenticationException ex) {
      throw new ApiException(AccountCode.INVALID_CREDENTIALS);
    }
    return token;
  }

  /**
   * Create a new account.
   * 
   * @param username The username of an account attemping to signup
   * @param password The password of an account attemping to signup
   * @return An authentication token
   */
  public String signup(String username, String password) {
    if (!accountRepository.existsByUsername(username)) {
      var account = new Account();
      account.setUsername(username);
      account.setPassword(passwordEncoder.encode(password));
      account.setRoles(Arrays.asList(Role.ROLE_ADMIN));
      account.setCreatedDate(OffsetDateTime.now());
      var persistedAccount = accountRepository.save(account);
      securityLogService.created(persistedAccount);
      emailService.sendAccountCreated(persistedAccount);
      return jwtTokenProvider.createToken(persistedAccount.getUsername(), persistedAccount.getRoles());
    } else {
      throw new ApiException(AccountCode.USERNAME_UNAVAILABLE);
    }
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
   * Update all mutable fields of an authenticated account if a change is
   * detected.
   * 
   * @param username                 The authenticated account username
   * @param fullName                 An updated fullname
   * @param emailSubscriptionEnabled An updated email subscription
   * @param billingAlertEnabled      An updated billing alert
   * @return The updated account
   */
  public Account update(String username, String fullName, boolean emailSubscriptionEnabled,
      boolean billingAlertEnabled) {
    var authenticatedAccount = findAuthenticatedAccount(username);
    updateFullName(authenticatedAccount, fullName);
    updateBillingAlertEnabled(authenticatedAccount, billingAlertEnabled);
    updateEmailSubscriptionEnabled(authenticatedAccount, emailSubscriptionEnabled);
    return accountRepository.save(authenticatedAccount);
  }

  private void updateFullName(Account account, String fullName) {
    if (account.getFullName() == null ? fullName != null : !account.getFullName().equals(fullName)) {
      securityLogService.fullname(account);
      account.setFullName(fullName);
    }
  }

  private void updateBillingAlertEnabled(Account account, boolean billingAlertEnabled) {
    if (account.getBillingAlertEnabled() != billingAlertEnabled) {
      securityLogService.billingAlertEnabled(account, billingAlertEnabled);
      account.setBillingAlertEnabled(billingAlertEnabled);
    }
  }

  private void updateEmailSubscriptionEnabled(Account account, boolean emailSubscriptionEnabled) {
    if (account.getEmailSubscriptionEnabled() != emailSubscriptionEnabled) {
      securityLogService.emailSubscriptionEnabled(account, emailSubscriptionEnabled);
      account.setEmailSubscriptionEnabled(emailSubscriptionEnabled);
    }
  }

  /**
   * Send a forgotten password email to the requested account. The reset token is
   * valid for 24hours.
   * 
   * @param username A valid account username
   */
  public void forgot(String username) {
    var authenticatedAccount = findAuthenticatedAccount(username);
    authenticatedAccount.setPasswordResetToken(UUID.randomUUID().toString());
    authenticatedAccount.setPasswordResetTokenExpire(OffsetDateTime.now().plusDays(1));
    accountRepository.save(authenticatedAccount);
    emailService.sendForgottenPassword(authenticatedAccount);
  }

  /**
   * Reset old password to new password for the requested account.
   * 
   * @param password The accounts new password
   * @param token    The generated password reset token from the /me/forgot
   *                 endpoint
   */
  public void reset(String password, String token) {
    var persistedAccount = accountRepository.findByPasswordResetToken(token)
        .orElseThrow(() -> new ApiException(AccountCode.INVALID_PASSWORD_RESET_TOKEN));
    if (OffsetDateTime.now().isAfter(persistedAccount.getPasswordResetTokenExpire())) {
      throw new ApiException(AccountCode.INVALID_PASSWORD_RESET_TOKEN);
    }
    persistedAccount.setPassword(passwordEncoder.encode(password));
    persistedAccount.setPasswordResetToken(null);
    persistedAccount.setPasswordResetTokenExpire(null);
    accountRepository.save(persistedAccount);
    securityLogService.reset(persistedAccount);
    emailService.sendResetPassword(persistedAccount);
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

  // TODO: unused method yet to be implemented
  @SuppressWarnings("unused")
  private String refresh(String username) {
    return jwtTokenProvider.createToken(username, findAuthenticatedAccount(username).getRoles());
  }
}
