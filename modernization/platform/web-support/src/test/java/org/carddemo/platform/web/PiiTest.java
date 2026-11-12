package org.carddemo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

class PiiTest {

    record CustomerView(String firstName, Pii ssn, Pii dob, Pii govtIssuedId, Pii eftAccountId, Pii cardNum) {
    }

    @Test
    void eachKindMasksAllButTheLastFour() {
        assertThat(Pii.cardNumber("4111111111111111").masked()).isEqualTo("************1111");
        assertThat(Pii.ssn("123456789").masked()).isEqualTo("***-**-6789");
        assertThat(Pii.date(LocalDate.of(1961, 3, 14)).masked()).isEqualTo("****-03-14");
        assertThat(Pii.text("A1B2C3D4E5").masked()).isEqualTo("******D4E5");
        assertThat(Pii.text("0053581756").masked()).isEqualTo("******1756");
        assertThat(Pii.text("ABCD").masked()).isEqualTo("****");
        assertThat(Pii.ssn("12345").masked()).isEqualTo("*2345");
    }

    @Test
    void blankAndMissingValuesStaySo() {
        assertThat(Pii.text("").masked()).isEmpty();
        assertThat(Pii.text("   ").masked()).isEqualTo("   ");
        assertThat(Pii.date(null).masked()).isNull();
        assertThat(Pii.date(null).isBlank()).isTrue();
    }

    @Test
    void responsesAndLogsSeeOnlyTheMaskedForm() {
        CustomerView view = new CustomerView("Immanuel", Pii.ssn("123456789"), Pii.date(LocalDate.of(1961, 3, 14)),
                Pii.text("A1B2C3D4E5"), Pii.text("0053581756"), Pii.cardNumber("4111111111111111"));
        String json = JsonMapper.builder().build().writeValueAsString(view);
        assertThat(json).isEqualTo("{\"firstName\":\"Immanuel\",\"ssn\":\"***-**-6789\",\"dob\":\"****-03-14\","
                + "\"govtIssuedId\":\"******D4E5\",\"eftAccountId\":\"******1756\",\"cardNum\":\"************1111\"}");
        assertThat(view.toString()).doesNotContain("123456789", "1961", "4111111111111111", "A1B2C3", "005358");
        assertThat(view.ssn().reveal()).isEqualTo("123456789");
        assertThat(view.dob().reveal()).isEqualTo("1961-03-14");
    }

    @Test
    void unmaskNamesKnownFieldsAndIsNeverCached() {
        Set<String> allowed = Set.of("ssn", "dob", "cardNum");
        assertThat(Unmask.requireFields(new Unmask.Request(List.of("ssn", "dob", "ssn")), allowed))
                .containsExactly("dob", "ssn");
        for (Unmask.Request bad : new Unmask.Request[] {null, new Unmask.Request(null), new Unmask.Request(List.of()),
                new Unmask.Request(List.of("ssn", "cvv"))}) {
            assertThatThrownBy(() -> Unmask.requireFields(bad, allowed))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo("INVALID_UNMASK_FIELD");
                        assertThat(e.text()).isEqualTo("Unmask one or more of: cardNum, dob, ssn");
                    });
        }
        assertThatThrownBy(() -> Unmask.requireFields(new Unmask.Request(List.of("cvv")), List.of("ssn", "dob", "cardNum")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.text())
                        .isEqualTo("Unmask one or more of: ssn, dob, cardNum"));
        assertThat(Unmask.requireFields(new Unmask.Request(List.of("ssn", "dob")), List.of("ssn", "dob", "cardNum")))
                .containsExactly("dob", "ssn");
        ResponseEntity<String> response = Unmask.noStore("x");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
    }
}
