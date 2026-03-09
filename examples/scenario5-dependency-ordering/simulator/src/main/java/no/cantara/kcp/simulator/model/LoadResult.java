package no.cantara.kcp.simulator.model;

/**
 * Result of attempting to load a knowledge unit.
 *
 * @param unitId  the unit ID
 * @param status  LOADED, SKIPPED, or BLOCKED
 * @param reason  human-readable reason (null for LOADED)
 */
public record LoadResult(String unitId, Status status, String reason) {

    public enum Status {
        LOADED,
        SKIPPED,
        BLOCKED
    }

    public static LoadResult loaded(String unitId) {
        return new LoadResult(unitId, Status.LOADED, null);
    }

    public static LoadResult skipped(String unitId, String reason) {
        return new LoadResult(unitId, Status.SKIPPED, reason);
    }

    public static LoadResult blocked(String unitId, String reason) {
        return new LoadResult(unitId, Status.BLOCKED, reason);
    }
}
