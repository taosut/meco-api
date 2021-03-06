package uk.thepragmaticdev.happy;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import com.stripe.exception.StripeException;
import java.util.List;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import uk.thepragmaticdev.IntegrationConfig;
import uk.thepragmaticdev.IntegrationData;
import uk.thepragmaticdev.account.AccountService;
import uk.thepragmaticdev.billing.BillingService;
import uk.thepragmaticdev.billing.dto.response.BillingPriceResponse;

@Import(IntegrationConfig.class)
@TestExecutionListeners({ DependencyInjectionTestExecutionListener.class, FlywayTestExecutionListener.class })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class BillingEndpointIT extends IntegrationData {

  private static final String TEST_USERNAME = "billing@integration.test";

  private static final String TEST_PASSWORD = "aTestPassword";

  private static final String TEST_PRICE = "price_1HO7M3BH6611072qpfZ3scEU";

  @LocalServerPort
  private int port;

  @Autowired
  private AccountService accountService;

  // Only injected to test non public service endpoints
  @Autowired
  private BillingService billingService;

  // @formatter:off

  /**
   * Called before each integration test to reset database to default state. 
   * A new account is created per test which contains a unique stipe test customer id.
   */
  @BeforeEach
  @FlywayTest
  public void initEach() {
    // Create a new account
    given()
      .headers(headers())
      .contentType(JSON)
      .body(authSignupRequest(TEST_USERNAME, TEST_PASSWORD))
    .when()
      .post(authEndpoint(port) + "signup")
    .then().statusCode(201);
    // Create a new Stripe customer for account
    given()
      .headers(headers())
      .header(HttpHeaders.AUTHORIZATION, signin(authSigninRequest(TEST_USERNAME, TEST_PASSWORD), port))
      .contentType(JSON)
    .when()
      .post(billingEndpoint(port))
    .then().statusCode(201);
  }

  /**
   * Clean up stripe customer test data.
   * 
   * @throws StripeException if a stripe api error occurs
   */
  @AfterEach
  public void cleanUpEach() throws StripeException {
    var account = accountService.findAuthenticatedAccount(TEST_USERNAME);
    billingService.deleteCustomer(account.getStripeCustomerId());
  }

  // @endpoint:findAllPrices

  @Test
  void shouldReturnAllBillingPricesWithNoAuthentication() {
    var billingPrices = given()
        .headers(headers())
        .when()
          .get(billingEndpoint(port) + "prices")
        .then()
            .statusCode(200)
        .extract().body().jsonPath().getList(".", BillingPriceResponse.class);
    assertBillingPrices(billingPrices);
  }

  // @endpoint:internal->create-customer

  @Test
  void shouldCreateStripeCustomer() {
    // account created within @BeforeEach
    var account = accountService.findAuthenticatedAccount(TEST_USERNAME);
    assertThat(account.getStripeCustomerId(), startsWith("cus_"));
  }

  // @endpoint:create-subscription

  @Test
  void shouldCreateSubscription() {
    // account created within @BeforeEach
    given()
      .headers(headers())
      .header(HttpHeaders.AUTHORIZATION, signin(authSigninRequest(TEST_USERNAME, TEST_PASSWORD), port))
      .contentType(JSON)
      .body(billingCreateSubscriptionRequest(TEST_PRICE))
    .when()
      .post(billingEndpoint(port) + "subscriptions")
    .then().statusCode(201);
    var account = accountService.findAuthenticatedAccount(TEST_USERNAME);
    assertThat(account.getStripeSubscriptionId(), startsWith("sub_"));
    assertThat(account.getStripeSubscriptionItemId(), startsWith("si_"));
  }

  // @endpoint:cancel-subscription

  @Test
  void shouldCancelSubscription() {
    // account created within @BeforeEach
    billingService.createSubscription(TEST_USERNAME, TEST_PRICE);
    given()
      .headers(headers())
      .header(HttpHeaders.AUTHORIZATION, signin(authSigninRequest(TEST_USERNAME, TEST_PASSWORD), port))
    .when()
      .delete(billingEndpoint(port) + "subscriptions")
    .then().statusCode(204);
    var account = accountService.findAuthenticatedAccount(TEST_USERNAME);
    assertThat(account.getStripeSubscriptionId(), is(nullValue()));
    assertThat(account.getStripeSubscriptionItemId(), is(nullValue()));
  }

  // @endpoint:internal->create-usage-record

  @Test
  void shouldCreateUsageRecordForSubscription() {
    // account created within @BeforeEach
    var operations = 10000;
    billingService.createSubscription(TEST_USERNAME, TEST_PRICE);
    billingService.createUsageRecord(TEST_USERNAME, operations);
    var usageRecordSummaries = billingService.findAllUsageRecords(TEST_USERNAME).getData();
    assertThat(usageRecordSummaries, hasSize(1));
    assertThat(usageRecordSummaries.get(0).getTotalUsage(), is(10000L));
  }

  // @endpoint:internal->find-upcoming-invoice

  @Test
  void shouldReturnUpcomingInvoice() {
    // account created within @BeforeEach
    var operations = 11000;
    billingService.createSubscription(TEST_USERNAME, TEST_PRICE);
    billingService.createUsageRecord(TEST_USERNAME, operations);
    var invoice = billingService.findUpcomingInvoice(TEST_USERNAME);
    assertThat(invoice.getAmountDue(), is(5200L));
  }

  // @formatter:on

  private void assertBillingPrices(List<BillingPriceResponse> billingPrices) {
    billingPrices.forEach(price -> {
      assertThat(price.getId(), startsWith("price_"));
      assertThat(price.getCurrency(), is("gbp"));
      assertThat(price.getNickname(), anyOf(containsString("Monthly"), containsString("Yearly")));
      assertThat(price.getProduct(), startsWith("prod_"));
      assertThat(price.getRecurring().getInterval(), anyOf(is("month"), is("year")));
      assertThat(price.getRecurring().getIntervalCount(), is(1L));
    });
  }
}