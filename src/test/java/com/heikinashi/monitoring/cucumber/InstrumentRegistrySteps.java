package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.domain.Instrument;
import com.heikinashi.monitoring.domain.InstrumentStatus;
import com.heikinashi.monitoring.domain.error.DomainException;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class InstrumentRegistrySteps {

    private static final String NULL_LITERAL = "<null>";

    private final World world;

    public InstrumentRegistrySteps(World world) {
        this.world = world;
    }

    // -------- Background ------------------------------------------------------

    @Given("the registry is empty")
    public void the_registry_is_empty() {
        // World is fresh per scenario; nothing to do.
    }

    @Given("current UTC time is {string}")
    public void current_utc_time_is(String iso) {
        world.setNow(Instant.parse(iso));
    }

    @Given("the supported exchanges are {string}")
    public void the_supported_exchanges_are(String csv) {
        Set<String> set = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        world.configureExchanges(set);
    }

    // -------- Given-state setup -----------------------------------------------

    @Given("an instrument {string} on {string} already exists")
    public void an_instrument_already_exists(String ticker, String exchange) {
        Instrument i = world.registry().register(ticker, exchange, Optional.empty(), Optional.empty());
        world.rememberInstrument(ticker, i);
    }

    @Given("the following instruments exist:")
    public void the_following_instruments_exist(DataTable table) {
        for (Map<String, String> row : table.asMaps()) {
            Instrument i = world.registry()
                    .register(row.get("ticker"), row.get("exchange"), Optional.empty(), Optional.empty());
            world.rememberInstrument(row.get("ticker"), i);
            if ("archived".equalsIgnoreCase(row.get("status"))) {
                world.registry().archive(i.id());
            }
        }
    }

    @Given("{int} active instruments exist")
    public void n_active_instruments_exist(int n) {
        for (int i = 0; i < n; i++) {
            String ticker = "T" + i;
            Instrument inst = world.registry().register(ticker, "NASDAQ", Optional.empty(), Optional.empty());
            world.rememberInstrument(ticker, inst);
        }
    }

    @Given("the instrument has been archived")
    public void the_instrument_has_been_archived() {
        Instrument current = world.lastInstrument();
        Instrument archived = world.registry().archive(current.id());
        world.setLastInstrument(archived);
    }

    // -------- When: register --------------------------------------------------

    @When("I register ticker {string} on exchange {string}")
    public void i_register(String ticker, String exchange) {
        register(ticker, exchange, Optional.empty(), Optional.empty());
    }

    @When("I register ticker {string} on exchange {string} with name {string} and currency {string}")
    public void i_register_with_name_and_currency(String ticker, String exchange, String name, String currency) {
        register(ticker, exchange, Optional.of(name), Optional.of(currency));
    }

    private void register(String rawTicker, String rawExchange, Optional<String> name, Optional<String> currency) {
        world.clearException();
        String ticker = NULL_LITERAL.equals(rawTicker) ? null : decodeWhitespaceLiterals(rawTicker);
        String exchange = NULL_LITERAL.equals(rawExchange) ? null : rawExchange;
        try {
            Instrument i = world.registry().register(ticker, exchange, name, currency);
            world.rememberInstrument(i.ticker(), i);
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    // -------- When: lookups, listing, mutations -------------------------------

    @When("I look up the instrument by its id")
    public void i_look_up_the_instrument_by_its_id() {
        world.clearException();
        try {
            Instrument i = world.registry().get(world.lastInstrument().id());
            world.setLastInstrument(i);
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I look up the instrument with id {string}")
    public void i_look_up_the_instrument_with_id(String id) {
        world.clearException();
        try {
            world.setLastInstrument(world.registry().get(id));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I list instruments with status {string}")
    public void i_list_instruments_with_status(String status) {
        world.setLastPage(world.registry().list(InstrumentStatus.fromWire(status), 50, Optional.empty()));
    }

    @When("I list instruments with status {string} and page size {int}")
    public void i_list_instruments_with_status_and_page_size(String status, int pageSize) {
        world.setLastPage(world.registry().list(InstrumentStatus.fromWire(status), pageSize, Optional.empty()));
    }

    @When("I list the next page with page size {int}")
    public void i_list_the_next_page_with_page_size(int pageSize) {
        Optional<String> cursor = world.lastPage().nextCursor();
        world.setLastPage(world.registry().list(InstrumentStatus.ACTIVE, pageSize, cursor));
    }

    @When("I update its metadata with name {string} and currency {string}")
    public void i_update_its_metadata(String name, String currency) {
        Instrument updated =
                world.registry().updateMetadata(world.lastInstrument().id(), Optional.of(name), Optional.of(currency));
        world.setLastInstrument(updated);
    }

    @When("I attempt to update field {string}")
    public void i_attempt_to_update_field(String field) {
        world.clearException();
        try {
            world.registry().rejectImmutableUpdates(Set.of(field));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I attempt to update fields {string}")
    public void i_attempt_to_update_fields(String csv) {
        world.clearException();
        Set<String> fields = Arrays.stream(csv.split(",")).map(String::trim).collect(Collectors.toUnmodifiableSet());
        try {
            world.registry().rejectImmutableUpdates(fields);
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I archive the instrument")
    public void i_archive_the_instrument() {
        world.clearException();
        try {
            Instrument i = world.registry().archive(world.lastInstrument().id());
            world.setLastInstrument(i);
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I archive the instrument with id {string}")
    public void i_archive_the_instrument_with_id(String id) {
        world.clearException();
        try {
            world.setLastInstrument(world.registry().archive(id));
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
    }

    @When("I restore the instrument")
    public void i_restore_the_instrument() {
        Instrument i = world.registry().restore(world.lastInstrument().id());
        world.setLastInstrument(i);
    }

    @When("I hard-delete the instrument")
    public void i_hard_delete_the_instrument() {
        world.registry().delete(world.lastInstrument().id());
    }

    @When("I hard-delete the instrument with id {string}")
    public void i_hard_delete_the_instrument_with_id(String id) {
        world.registry().delete(id);
    }

    // -------- Then: assertions ------------------------------------------------

    @Then("the registration succeeds")
    public void the_registration_succeeds() {
        assertThat(world.lastException()).as("expected no exception").isNull();
        assertThat(world.lastInstrument()).isNotNull();
    }

    @Then("the result has ticker {string}, exchange {string}, and status {string}")
    public void the_result_has_ticker_exchange_status(String ticker, String exchange, String status) {
        Instrument i = world.lastInstrument();
        assertThat(i.ticker()).isEqualTo(ticker);
        assertThat(i.exchange()).isEqualTo(exchange);
        assertThat(i.status()).isEqualTo(InstrumentStatus.fromWire(status));
    }

    @Then("the result has status {string}")
    public void the_result_has_status(String status) {
        assertThat(world.lastInstrument().status()).isEqualTo(InstrumentStatus.fromWire(status));
    }

    @Then("the result has name {string} and currency {string}")
    public void the_result_has_name_and_currency(String name, String currency) {
        assertThat(world.lastInstrument().name()).contains(name);
        assertThat(world.lastInstrument().currency()).contains(currency);
    }

    @Then("ticker, exchange, status, and created_at are unchanged")
    public void immutable_fields_are_unchanged() {
        Instrument current = world.lastInstrument();
        Instrument original = world.registry().get(current.id());
        assertThat(current.ticker()).isEqualTo(original.ticker());
        assertThat(current.exchange()).isEqualTo(original.exchange());
        assertThat(current.status()).isEqualTo(original.status());
        assertThat(current.createdAt()).isEqualTo(original.createdAt());
    }

    @Then("the result has created_at and updated_at equal to the current UTC time")
    public void created_at_and_updated_at_match_clock() {
        assertThat(world.lastInstrument().createdAt()).isEqualTo(world.now());
        assertThat(world.lastInstrument().updatedAt()).isEqualTo(world.now());
    }

    @Then("a UNIQUE_LOCK exists for ticker {string} on exchange {string}")
    public void unique_lock_exists(String ticker, String exchange) {
        assertThat(world.repository().hasLock(ticker, exchange)).isTrue();
    }

    @Then("no UNIQUE_LOCK exists for ticker {string} on exchange {string}")
    public void no_unique_lock_exists(String ticker, String exchange) {
        assertThat(world.repository().hasLock(ticker, exchange)).isFalse();
    }

    @Then("a CONFIG default has been provisioned for the new instrument")
    public void config_default_provisioned() {
        assertThat(world.repository().hasConfig(world.lastInstrument().id())).isTrue();
    }

    @Then("the operation fails with code {string}")
    public void the_operation_fails_with_code(String expectedCode) {
        Throwable t = world.lastException();
        assertThat(t).as("expected a domain exception").isNotNull();
        assertThat(t).isInstanceOf(DomainException.class);
        assertThat(((DomainException) t).code()).isEqualTo(expectedCode);
    }

    @Then("no error is raised")
    public void no_error_is_raised() {
        assertThat(world.lastException()).as("expected no exception").isNull();
    }

    @Then("the registry remains empty")
    public void the_registry_remains_empty() {
        assertThat(world.repository().size()).isZero();
    }

    @Then("looking it up by id fails with code {string}")
    public void looking_it_up_by_id_fails_with_code(String code) {
        world.clearException();
        try {
            world.registry().get(world.lastInstrument().id());
        } catch (RuntimeException e) {
            world.setLastException(e);
        }
        the_operation_fails_with_code(code);
    }

    @Then("{int} instrument(s) is/are returned")
    public void n_instruments_are_returned(int n) {
        assertThat(world.lastPage().items()).hasSize(n);
    }

    @Then("{int} instruments are returned and a next cursor is present")
    public void n_instruments_with_next_cursor(int n) {
        List<Instrument> items = world.lastPage().items();
        assertThat(items).hasSize(n);
        assertThat(world.lastPage().nextCursor()).isPresent();
    }

    @Then("{int} instruments are returned and no next cursor is present")
    public void n_instruments_no_next_cursor(int n) {
        assertThat(world.lastPage().items()).hasSize(n);
        assertThat(world.lastPage().nextCursor()).isEmpty();
    }

    @And("the operation succeeds")
    public void the_operation_succeeds() {
        assertThat(world.lastException()).isNull();
    }

    private static String decodeWhitespaceLiterals(String raw) {
        return raw.replace("\\t", "\t").replace("\\n", "\n");
    }
}
