package org.carddemo.identity.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.carddemo.identity.IdentityProperties;
import org.carddemo.identity.fidelity.IdentityFidelity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** D-06: users at startup when iam.app_user is empty. {@code dataLoadJob} never touches users. */
@Component
public class UserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    record SeedUser(String userId, String firstName, String lastName, char userType) {
    }

    /** The users of DUSRSECJ, in file order. */
    static final List<SeedUser> DEMO_USERS = List.of(
            new SeedUser("ADMIN001", "MARGARET", "GOLD", 'A'),
            new SeedUser("ADMIN002", "RUSSELL", "RUSSELL", 'A'),
            new SeedUser("ADMIN003", "RAYMOND", "WHITMORE", 'A'),
            new SeedUser("ADMIN004", "EMMANUEL", "CASGRAIN", 'A'),
            new SeedUser("ADMIN005", "GRANVILLE", "LACHAPELLE", 'A'),
            new SeedUser("USER0001", "LAWRENCE", "THOMAS", 'U'),
            new SeedUser("USER0002", "AJITH", "KUMAR", 'U'),
            new SeedUser("USER0003", "LAURITZ", "ALME", 'U'),
            new SeedUser("USER0004", "AVERARDO", "MAZZI", 'U'),
            new SeedUser("USER0005", "LEE", "TING", 'U'));

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final IdentityFidelity fidelity;
    private final IdentityProperties.Seed seed;
    private final Clock clock;
    private final TransactionTemplate tx;

    @Autowired
    public UserSeeder(UserRepository users, PasswordHasher hasher, IdentityFidelity fidelity,
            IdentityProperties props, Clock clock, TransactionTemplate tx) {
        this(users, hasher, fidelity, props.seed(), clock, tx);
    }

    UserSeeder(UserRepository users, PasswordHasher hasher, IdentityFidelity fidelity, IdentityProperties.Seed seed,
            Clock clock, TransactionTemplate tx) {
        this.users = users;
        this.hasher = hasher;
        this.fidelity = fidelity;
        this.seed = seed;
        this.clock = clock;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seed.enabled()) {
            seedIfEmpty();
        }
    }

    /** Returns the number of users added. */
    public int seedIfEmpty() {
        if (users.count() > 0) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        int added = 0;
        if (seed.demoUsers()) {
            String hash = hashOf(seed.demoPassword(), "carddemo.identity.seed.demo-password (CARDDEMO_SEED_USER_PASSWORD)");
            for (SeedUser u : DEMO_USERS) {
                added += insert(u, hash, false, now);
            }
            log.info("Seeded {} demo users", added);
        } else {
            String hash = hashOf(seed.bootstrapAdminPassword(),
                    "carddemo.identity.seed.bootstrap-admin-password (CARDDEMO_BOOTSTRAP_ADMIN_PASSWORD)");
            added = insert(DEMO_USERS.getFirst(), hash, true, now);
            log.info("Seeded the bootstrap admin ADMIN001; it must change its password at first sign-on");
        }
        return added;
    }

    private String hashOf(String password, String property) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("identity-service: iam.app_user is empty and " + property
                    + " is not set, so no user can be seeded");
        }
        return hasher.hash(PasswordHasher.prepare(password, fidelity.caseSensitivePasswords()));
    }

    private int insert(SeedUser u, String hash, boolean mustChange, LocalDateTime now) {
        AppUser user = new AppUser(u.userId(), u.firstName(), u.lastName(), hash, u.userType(), mustChange, 0, null);
        // The user row and its sign-on state row commit together.
        return Boolean.TRUE.equals(tx.execute(t -> users.insertIfAbsent(user, now))) ? 1 : 0;
    }
}
