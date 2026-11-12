package org.carddemo.batch.catalogue;

/** A value-free failure code and location, safe for Spring Batch's persisted failure chain. */
public final class CatalogueFailure extends RuntimeException {
    public CatalogueFailure(String code, String location) {
        super(code + ": " + location);
    }
}
