package org.carddemo.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.carddemo.identity.IdentityProperties.Seed;
import org.carddemo.identity.fidelity.IdentityFidelity;
import org.carddemo.identity.fidelity.IdentitySwitches;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The seed against the SYSUT1 records of DUSRSECJ, without a database: the seed list is the
 * JCL's ten records in file order, the seed inserts them in that order, and a table that holds
 * any row is only counted, never written.
 */
class UserSeederRecordsTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    private UserSeeder seeder() {
        return new UserSeeder(users, hasher, new IdentityFidelity(FidelitySwitches.allLegacy(IdentitySwitches.ALL)),
                new Seed(true, true, "PASSWORD", null), Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC),
                tx);
    }

    private static List<String> records(List<UserSeederTest.JclUser> jcl) {
        return jcl.stream().map(u -> u.id() + "|" + u.first() + "|" + u.last() + "|" + u.type()).toList();
    }

    @Test
    @SpecItem("SE-JUSEC-002")
    void theSeedUsersAreTheDusrsecjRecordsInFileOrder() throws IOException {
        List<UserSeederTest.JclUser> jcl = UserSeederTest.dusrsecj();
        assertThat(jcl).hasSize(10);
        assertThat(jcl).extracting(UserSeederTest.JclUser::type)
                .containsExactly('A', 'A', 'A', 'A', 'A', 'U', 'U', 'U', 'U', 'U');

        assertThat(UserSeeder.DEMO_USERS.stream()
                .map(u -> u.userId() + "|" + u.firstName() + "|" + u.lastName() + "|" + u.userType()).toList())
                .containsExactlyElementsOf(records(jcl));
    }

    @Test
    @SpecItem("SE-JUSEC-005")
    void everyRecordIsInsertedInFileOrder() throws IOException {
        when(users.count()).thenReturn(0L);
        when(users.insertIfAbsent(any(), any())).thenReturn(true);
        when(hasher.hash(anyString())).thenReturn("{argon2}seed");
        when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));

        assertThat(seeder().seedIfEmpty()).isEqualTo(10);

        ArgumentCaptor<AppUser> inserted = ArgumentCaptor.forClass(AppUser.class);
        InOrder order = inOrder(users);
        order.verify(users).count();
        order.verify(users, times(10)).insertIfAbsent(inserted.capture(), any());
        verifyNoMoreInteractions(users);
        assertThat(inserted.getAllValues().stream()
                .map(u -> u.userId() + "|" + u.firstName() + "|" + u.lastName() + "|" + u.userType()).toList())
                .containsExactlyElementsOf(records(UserSeederTest.dusrsecj()));
        assertThat(inserted.getAllValues()).allSatisfy(u -> {
            assertThat(u.passwordHash()).isEqualTo("{argon2}seed");
            assertThat(u.mustChangePassword()).isFalse();
        });
        verify(tx, times(10)).execute(any());
    }

    @Test
    @SpecItem("ERR-JUSEC-003")
    void aTableThatHoldsAnyRowIsOnlyCounted() {
        when(users.count()).thenReturn(1L);

        assertThat(seeder().seedIfEmpty()).isZero();

        verify(users).count();
        verifyNoMoreInteractions(users);
        verifyNoInteractions(hasher, tx);
    }
}
