package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.carddemo.platform.ledger.PostedCategory;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;

class CategoryFillerTest {

    @Test
    void theWorkAreaStartsAsLowValues() {
        assertThat(CategoryFiller.from(new ExecutionContext())).hasSize(22).matches("\u0000{22}");
    }

    @Test
    void aCreatedRowAndARowWithAFillerPassOnTheirFiller() {
        String filler = "AB\u0000\u00FF" + "0".repeat(18);

        assertThat(CategoryFiller.after(new PostedCategory(true, filler))).isEqualTo(filler);
        assertThat(CategoryFiller.after(new PostedCategory(false, filler))).isEqualTo(filler);
    }

    @Test
    void aRowWithoutFillerPassesOnTheFillerItsExportWrites() {
        assertThat(CategoryFiller.after(new PostedCategory(false, null))).isEqualTo("0".repeat(22));
        assertThat(CategoryFiller.UNWRITTEN).isNotEqualTo(CategoryFiller.LOW_VALUES);
    }

    @Test
    void theStepContextKeepsEveryByte() {
        String filler = "\u0000\u0001 Z\u00E9\u00FF" + "\u0000".repeat(16);
        ExecutionContext context = new ExecutionContext();

        CategoryFiller.put(context, filler);

        assertThat(context.getString(CategoryFiller.KEY)).matches("[0-9a-f]{44}");
        assertThat(CategoryFiller.from(context)).isEqualTo(filler);
    }

    @Test
    void aContextValueOfAnotherLengthIsRefused() {
        ExecutionContext context = new ExecutionContext();
        context.putString(CategoryFiller.KEY, "0030");

        assertThatThrownBy(() -> CategoryFiller.from(context)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 bytes");
    }
}
