package org.carddemo.platform.outputroot;

import java.nio.file.Path;

/** One process of the racing-processes test: ensures the root and prints it. */
public final class EnsureMain {

    private EnsureMain() {
    }

    public static void main(String[] args) {
        System.out.println(OutputRoot.ensure(Path.of(args[0]), OutputRoot.Instance.of(args[1], args[2])));
    }
}
