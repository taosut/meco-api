package uk.thepragmaticdev.endpoint.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.thepragmaticdev.account.Account;
import uk.thepragmaticdev.account.AccountService;
import uk.thepragmaticdev.account.dto.request.AccountSigninRequest;
import uk.thepragmaticdev.account.dto.request.AccountSignupRequest;
import uk.thepragmaticdev.account.dto.request.AccountUpdateRequest;
import uk.thepragmaticdev.account.dto.response.AccountMeResponse;
import uk.thepragmaticdev.account.dto.response.AccountSigninResponse;
import uk.thepragmaticdev.account.dto.response.AccountSignupResponse;
import uk.thepragmaticdev.account.dto.response.AccountUpdateResponse;
import uk.thepragmaticdev.log.billing.BillingLog;
import uk.thepragmaticdev.log.security.SecurityLog;

@RestController
@RequestMapping("/accounts")
@CrossOrigin("*")
@Tag(name = "accounts")
public class AccountController {

  private final AccountService accountService;

  private final ModelMapper modelMapper;

  @Autowired
  public AccountController(AccountService accountService, ModelMapper modelMapper) {
    this.accountService = accountService;
    this.modelMapper = modelMapper;
  }

  /**
   * Authorize an account.
   * 
   * @param request The account details for signing in
   * @return An authentication token
   */
  @PostMapping(value = "/signin", consumes = MediaType.APPLICATION_JSON_VALUE)
  public AccountSigninResponse signin(@Valid @RequestBody AccountSigninRequest request) {
    var token = accountService.signin(request.getUsername(), request.getPassword());
    return new AccountSigninResponse(token);
  }

  /**
   * Create a new account.
   * 
   * @param request The new account details to be created
   * @return A newly created account
   */
  @ResponseStatus(value = HttpStatus.CREATED)
  @PostMapping(value = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
  public AccountSignupResponse signup(@Valid @RequestBody AccountSignupRequest request) {
    var token = accountService.signup(request.getUsername(), request.getPassword());
    return new AccountSignupResponse(token);
  }

  /**
   * Find the currently authenticated account.
   * 
   * @param principal The currently authenticated principal user
   * @return The authenticated account
   */
  @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public AccountMeResponse findAuthenticatedAccount(Principal principal) {
    var account = accountService.findAuthenticatedAccount(principal.getName());
    // TODO: unit test
    return modelMapper.map(account, AccountMeResponse.class);
  }

  /**
   * Update all mutable fields of an authenticated account.
   * 
   * @param principal The currently authenticated principal user
   * @param request   Details of account with the desired values
   * @return The updated account
   */
  @PutMapping(value = "/me", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public AccountUpdateResponse update(Principal principal, @Valid @RequestBody AccountUpdateRequest request) {
    var account = accountService.update(principal.getName(), request.getFullName(),
        request.getEmailSubscriptionEnabled(), request.getBillingAlertEnabled());
    // TODO: unit test
    return modelMapper.map(account, AccountUpdateResponse.class);
  }

  /**
   * Send a forgotten password email to the requested account.
   * 
   * @param username A valid account username
   */
  @PostMapping(value = "/me/forgot", produces = MediaType.APPLICATION_JSON_VALUE)
  public void forgot(@RequestParam(value = "username", required = true) String username) {
    accountService.forgot(username);
  }

  /**
   * Reset old password to new password for the requested account.
   * 
   * @param account An account containing the new password
   * @param token   The generated password reset token from the /me/forgot
   *                endpoint
   */
  @PostMapping(value = "/me/reset", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public void reset(@Valid @RequestBody Account account, @RequestParam(value = "token", required = true) String token) {
    accountService.reset(account, token);
  }

  /**
   * Find the latest billing logs for the account.
   * 
   * @param pageable  The pagination information
   * @param principal The currently authenticated principal user
   * @return A page of the latest billing logs
   */
  @GetMapping(value = "/me/billing/logs", produces = MediaType.APPLICATION_JSON_VALUE)
  public Page<BillingLog> billingLogs(Pageable pageable, Principal principal) {
    return accountService.billingLogs(pageable, principal.getName());
  }

  /**
   * Download all billing logs for the account as a CSV file.
   * 
   * @param response  The servlet response
   * @param principal The currently authenticated principal user
   */
  @GetMapping(value = "/me/billing/logs/download")
  public void downloadBillingLogs(HttpServletResponse response, Principal principal) {
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + "billing.csv" + "\"");
    accountService.downloadBillingLogs(response, principal.getName());
  }

  /**
   * Find the latest security logs for the account.
   * 
   * @param pageable  The pagination information
   * @param principal The currently authenticated principal user
   * @return A page of the latest security logs
   */
  @GetMapping(value = "/me/security/logs", produces = MediaType.APPLICATION_JSON_VALUE)
  public Page<SecurityLog> securityLogs(Pageable pageable, Principal principal) {
    return accountService.securityLogs(pageable, principal.getName());
  }

  /**
   * Download all security logs for the account as a CSV file.
   * 
   * @param response  The servlet response
   * @param principal The currently authenticated principal user
   */
  @GetMapping(value = "/me/security/logs/download")
  public void downloadSecurityLogs(HttpServletResponse response, Principal principal) {
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + "security.csv" + "\"");
    accountService.downloadSecurityLogs(response, principal.getName());
  }
}