package uk.thepragmaticdev.endpoint.controller;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.opencsv.bean.StatefulBeanToCsv;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.thepragmaticdev.UnitData;
import uk.thepragmaticdev.account.AccountService;
import uk.thepragmaticdev.account.dto.request.AccountUpdateRequest;
import uk.thepragmaticdev.account.dto.response.AccountMeResponse;
import uk.thepragmaticdev.account.dto.response.AccountUpdateResponse;
import uk.thepragmaticdev.log.billing.BillingLog;
import uk.thepragmaticdev.log.dto.BillingLogResponse;
import uk.thepragmaticdev.log.dto.SecurityLogResponse;
import uk.thepragmaticdev.log.security.SecurityLog;
import uk.thepragmaticdev.security.request.RequestMetadata;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class AccountControllerTest extends UnitData {

  @Autowired
  private ObjectMapper mapper;

  @Autowired
  private MockMvc mvc;

  @Mock
  private AccountService accountService;

  @Mock
  private StatefulBeanToCsv<BillingLog> billingLogWriter;

  @Mock
  private StatefulBeanToCsv<SecurityLog> securityLogWriter;

  private Principal principal;

  private AccountController sut;

  /**
   * Called before each test. Builds the system under test, mocks the mvc endpoint
   * and creates a test principal for authentication.
   */
  @BeforeEach
  public void initEach() {
    sut = new AccountController(accountService, new ModelMapper(), billingLogWriter, securityLogWriter);
    mvc = MockMvcBuilders.standaloneSetup(sut)//
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())//
        .build();
    principal = new Principal() {
      @Override
      public String getName() {
        return "principal";
      }
    };
  }

  @Test
  void shouldMapToAccountMeResponse() throws Exception {
    var account = account();
    when(accountService.findAuthenticatedAccount(anyString())).thenReturn(account);

    var body = mvc.perform(//
        MockMvcRequestBuilders.get("/v1/accounts/me")//
            .principal(principal)//
            .accept(MediaType.APPLICATION_JSON)//
    ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var response = mapper.readValue(body, AccountMeResponse.class);

    assertThat(response.getUsername(), is(account.getUsername()));
    assertThat(response.getFullName(), is(account.getFullName()));
    assertThat(response.getEmailSubscriptionEnabled(), is(account.getEmailSubscriptionEnabled()));
    assertThat(response.getBillingAlertEnabled(), is(account.getBillingAlertEnabled()));
    assertThat(response.getCreatedDate(), is(account.getCreatedDate()));
  }

  @Test
  void shouldMapToAccountUpdateResponse() throws Exception {
    var account = account();
    var accountUpdateRequest = new AccountUpdateRequest(//
        account.getFullName(), //
        account.getEmailSubscriptionEnabled(), //
        account.getBillingAlertEnabled(), account.getBillingAlertAmount());
    when(accountService.update(anyString(), anyString(), anyBoolean(), anyBoolean(), anyShort())).thenReturn(account);

    var body = mvc.perform(//
        MockMvcRequestBuilders.put("/v1/accounts/me")//
            .principal(principal)//
            .contentType(MediaType.APPLICATION_JSON)//
            .content(new Gson().toJson(accountUpdateRequest)) //
            .accept(MediaType.APPLICATION_JSON)//
    ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var response = mapper.readValue(body, AccountUpdateResponse.class);

    assertThat(response.getUsername(), is(account.getUsername()));
    assertThat(response.getFullName(), is(account.getFullName()));
    assertThat(response.getEmailSubscriptionEnabled(), is(account.getEmailSubscriptionEnabled()));
    assertThat(response.getBillingAlertEnabled(), is(account.getBillingAlertEnabled()));
    assertThat(response.getCreatedDate(), is(account.getCreatedDate()));
  }

  @Test
  void shouldMapToPageOfBillingLogResponses() throws Exception {
    var logs = billingLogs();
    when(accountService.billingLogs(any(Pageable.class), anyString())).thenReturn(logs);

    var body = mvc.perform(//
        MockMvcRequestBuilders.get("/v1/accounts/me/billing/logs")//
            .principal(principal)//
            .accept(MediaType.APPLICATION_JSON)//
    ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    var actual = pageToList(body, BillingLogResponse.class);
    var expected = logs.getContent();
    for (int i = 0; i < actual.size(); i++) {
      assertThat(actual.get(i).getAction(), is(expected.get(i).getAction()));
      assertThat(actual.get(i).getAmount(), is(expected.get(i).getAmount()));
      assertThat(actual.get(i).getCreatedDate(), is(expected.get(i).getCreatedDate()));
    }
  }

  @Test
  void shouldMapToPageOfSecurityLogResponses() throws Exception {
    var logs = securityLogs();
    when(accountService.securityLogs(any(Pageable.class), anyString())).thenReturn(logs);

    var body = mvc.perform(//
        MockMvcRequestBuilders.get("/v1/accounts/me/security/logs")//
            .principal(principal)//
            .accept(MediaType.APPLICATION_JSON)//
    ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    var actual = pageToList(body, SecurityLogResponse.class);
    var expected = logs.getContent();
    for (int i = 0; i < actual.size(); i++) {
      assertThat(actual.get(i).getAction(), is(expected.get(i).getAction()));
      assertThat(actual.get(i).getRequestMetadata(), is(expected.get(i).getRequestMetadata()));
      assertThat(actual.get(i).getCreatedDate(), is(expected.get(i).getCreatedDate()));
    }
  }

  private Page<BillingLog> billingLogs() {
    var billingLogs = List.of(//
        new BillingLog(1L, account(), "action1", "amount1", OffsetDateTime.now(ZoneOffset.UTC)), //
        new BillingLog(2L, account(), "action2", "amount2", OffsetDateTime.now(ZoneOffset.UTC)) //
    );
    return new PageImpl<BillingLog>(billingLogs, PageRequest.of(1, 1), billingLogs.size());
  }

  private Page<SecurityLog> securityLogs() {
    var securityLogs = List.of(//
        new SecurityLog(1L, account(), "action1", metadata(), OffsetDateTime.now(ZoneOffset.UTC)), //
        new SecurityLog(1L, account(), "action1", metadata(), OffsetDateTime.now(ZoneOffset.UTC)));
    return new PageImpl<SecurityLog>(securityLogs, PageRequest.of(1, 1), securityLogs.size());
  }

  private RequestMetadata metadata() {
    return new RequestMetadata();
  }
}