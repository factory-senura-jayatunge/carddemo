package org.carddemo.batch.execution;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/** This batch-service process as a lease owner: a random ID per process, with host and PID for the record. */
public record LeaseOwner(UUID id, String host, long pid) {

    public static LeaseOwner ofThisProcess() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        return new LeaseOwner(UUID.randomUUID(), host.length() > 255 ? host.substring(0, 255) : host,
                ProcessHandle.current().pid());
    }

    @Override
    public String toString() {
        return host + "/" + pid;
    }
}
