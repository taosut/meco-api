package uk.thepragmaticdev.account;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.boot.test.context.SpringBootTest;
import uk.thepragmaticdev.UnitData;
import uk.thepragmaticdev.billing.BillingService;
import uk.thepragmaticdev.exception.ApiException;
import uk.thepragmaticdev.exception.code.AuthCode;
import uk.thepragmaticdev.exception.code.CriticalCode;
import uk.thepragmaticdev.log.billing.BillingLog;
import uk.thepragmaticdev.log.billing.BillingLogService;
import uk.thepragmaticdev.log.security.SecurityLog;
import uk.thepragmaticdev.log.security.SecurityLogService;

@SpringBootTest
class AccountServiceTest extends UnitData {

  @Mock
  private AccountRepository accountRepository;

  @Mock
  private BillingService billingService;

  @Mock
  private BillingLogService billingLogService;

  @Mock
  private SecurityLogService securityLogService;

  @Mock
  private StatefulBeanToCsv<BillingLog> billingLogWriter;

  @Mock
  private StatefulBeanToCsv<SecurityLog> securityLogWriter;

  private AccountService sut;

  /**
   * Called before each test, builds the system under test.
   */
  @BeforeEach
  public void initEach() {
    sut = new AccountService(accountRepository, billingService, billingLogService, securityLogService);
  }

  @Test
  void shouldThrowExceptionDownloadingBillingLogsIfInvalidCsvDataType() throws Exception {
    when(accountRepository.findByUsername(anyString())).thenReturn(Optional.of(account()));
    doThrow(CsvDataTypeMismatchException.class).when(billingLogWriter).write(anyList());

    var ex = Assertions.assertThrows(ApiException.class, () -> {
      sut.downloadBillingLogs(billingLogWriter, "username");
    });
    assertThat(ex.getErrorCode(), is(CriticalCode.CSV_WRITING_ERROR));
  }

  @Test
  void shouldThrowExceptionDownloadingBillingLogsIfRequiredFieldIsEmpty() throws Exception {
    when(accountRepository.findByUsername(anyString())).thenReturn(Optional.of(account()));
    doThrow(CsvRequiredFieldEmptyException.class).when(billingLogWriter).write(anyList());

    var ex = Assertions.assertThrows(ApiException.class, () -> {
      sut.downloadBillingLogs(billingLogWriter, "username");
    });
    assertThat(ex.getErrorCode(), is(CriticalCode.CSV_WRITING_ERROR));
  }

  @Test
  void shouldThrowExceptionDownloadingSecurityLogsIfInvalidCsvDataType() throws Exception {
    when(accountRepository.findByUsername(anyString())).thenReturn(Optional.of(account()));
    doThrow(CsvDataTypeMismatchException.class).when(securityLogWriter).write(anyList());

    var ex = Assertions.assertThrows(ApiException.class, () -> {
      sut.downloadSecurityLogs(securityLogWriter, "username");
    });
    assertThat(ex.getErrorCode(), is(CriticalCode.CSV_WRITING_ERROR));
  }

  @Test
  void shouldThrowExceptionDownloadingSecurityLogsIfRequiredFieldIsEmpty() throws Exception {
    when(accountRepository.findByUsername(anyString())).thenReturn(Optional.of(account()));
    doThrow(CsvRequiredFieldEmptyException.class).when(securityLogWriter).write(anyList());

    var ex = Assertions.assertThrows(ApiException.class, () -> {
      sut.downloadSecurityLogs(securityLogWriter, "username");
    });
    assertThat(ex.getErrorCode(), is(CriticalCode.CSV_WRITING_ERROR));
  }

  @Test
  void shouldCreateTokenAndFutureExpireDate() {
    var account = account();
    when(accountRepository.findByUsername(anyString())).thenReturn(Optional.of(account));

    sut.createPasswordResetToken("username");
    assertThat(account.getPasswordResetToken(), not(emptyString()));
    assertThat(account.getPasswordResetTokenExpire().isAfter(OffsetDateTime.now()), is(true));
  }

  @Test
  void shouldThrowExceptionIfResetTokenHasExpired() {
    var account = account();
    account.setPasswordResetTokenExpire(OffsetDateTime.now().minusMinutes(1));
    when(accountRepository.findByPasswordResetToken(anyString())).thenReturn(Optional.of(account));

    var ex = Assertions.assertThrows(ApiException.class, () -> {
      sut.resetPasswordResetToken("encodedPassword", "token");
    });
    assertThat(ex.getErrorCode(), is(AuthCode.PASSWORD_RESET_TOKEN_EXPIRED));
  }
}