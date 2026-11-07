package org.carddemo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PageTest {

    @Test
    void theRequestDefaultsToTheFirstPageOfTheScreenSize() {
        assertThat(PageRequest.of(null, null, null, null, 7)).isEqualTo(new PageRequest(PageRequest.Kind.FIRST, null, 7));
        assertThat(PageRequest.of(" ", "", null, 3, 7)).isEqualTo(new PageRequest(PageRequest.Kind.FIRST, null, 3));
        assertThat(PageRequest.of("4111", null, null, null, 7).kind()).isEqualTo(PageRequest.Kind.START);
        assertThat(PageRequest.of(null, "k", null, null, 7).kind()).isEqualTo(PageRequest.Kind.AFTER);
        assertThat(PageRequest.of(null, null, "k", null, 7).kind()).isEqualTo(PageRequest.Kind.BEFORE);
        assertThat(PageRequest.of(null, null, null, null, 7).fetchSize()).isEqualTo(8);
    }

    @Test
    void oneStartingPointAndAScreenSizedPage() {
        assertThatThrownBy(() -> PageRequest.of("a", "b", null, null, 7))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_PAGE_REQUEST"));
        for (int size : new int[] {0, -1, 8}) {
            assertThatThrownBy(() -> PageRequest.of(null, null, null, size, 7))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo("INVALID_PAGE_SIZE");
                        assertThat(e.text()).isEqualTo("Page size must be between 1 and 7");
                    });
        }
    }

    @Test
    void forwardPagesLookOneRowAhead() {
        Page<String> full = Page.forward(List.of("a", "b", "c", "d"), 3, Function.identity(), false);
        assertThat(full).isEqualTo(new Page<>(List.of("a", "b", "c"), true, false, "a", "c"));
        Page<String> last = Page.forward(List.of("d", "e"), 3, Function.identity(), true);
        assertThat(last).isEqualTo(new Page<>(List.of("d", "e"), false, true, "d", "e"));
        Page<String> exact = Page.forward(List.of("a", "b", "c"), 3, Function.identity(), false);
        assertThat(exact.hasNext()).isFalse();
        Page<String> empty = Page.forward(List.of(), 3, Function.identity(), false);
        assertThat(empty).isEqualTo(new Page<String>(List.of(), false, false, null, null));
    }

    @Test
    void backwardPagesShowAscendingAndKnowTheyHaveANextPage() {
        Page<String> middle = Page.backward(List.of("f", "e", "d", "c"), 3, Function.identity());
        assertThat(middle).isEqualTo(new Page<>(List.of("d", "e", "f"), true, true, "d", "f"));
        Page<String> first = Page.backward(List.of("b", "a"), 3, Function.identity());
        assertThat(first).isEqualTo(new Page<>(List.of("a", "b"), true, false, "a", "b"));
    }

    @Test
    void pagesMapRowsAndKeys() {
        Page<String> page = Page.forward(List.of("17", "42"), 7, Function.identity(), false);
        Page<Integer> mapped = page.map(Integer::valueOf).withKeys(Cursors::encode);
        assertThat(mapped.items()).containsExactly(17, 42);
        assertThat(Cursors.decode(mapped.firstKey())).isEqualTo("17");
        assertThat(Cursors.decode(mapped.lastKey())).isEqualTo("42");
    }

    @Test
    void cursorsAreOpaqueAndCheckedOnTheWayBack() {
        String token = Cursors.encode("123456");
        assertThat(token).doesNotContain("123456").doesNotContain("=");
        assertThat(Cursors.decode(token)).isEqualTo("123456");
        for (String bad : new String[] {"", "!!", token + "A", "MTIzNDU2=="}) {
            assertThatThrownBy(() -> Cursors.decode(bad))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CURSOR"));
        }
    }
}
