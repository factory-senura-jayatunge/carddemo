package org.carddemo.identity.user;

import static org.mockito.Mockito.doThrow;

/** Stubs of the package-private parts of {@link UserAdminLocks} for tests in other packages. */
public final class UserAdminLocksStubs {

    private UserAdminLocksStubs() {
    }

    /** The guard's read of the transaction level fails with {@code failure}. */
    public static void levelsFail(UserAdminLocks spy, RuntimeException failure) {
        doThrow(failure).when(spy).levels();
    }
}
