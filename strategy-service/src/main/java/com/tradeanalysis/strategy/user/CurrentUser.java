package com.tradeanalysis.strategy.user;

import java.util.Optional;

/** The logged-in user making the current request, if any. Set by {@link CurrentUserFilter}. */
public final class CurrentUser {

    private record Caller(Long id, boolean admin) {}
    private static final ThreadLocal<Caller> CALLER = new ThreadLocal<>();

    private CurrentUser() {}

    public static Optional<Long> id() { return Optional.ofNullable(CALLER.get()).map(Caller::id); }

    /** Admins see every user's data. */
    public static boolean admin() { Caller c = CALLER.get(); return c != null && c.admin(); }

    /**
     * True when the caller may see everyone's data: an admin, or no user at all (scheduled jobs such as the live
     * runner, and startup). Every /api request has a user, because the filter refuses requests without a login.
     */
    public static boolean unrestricted() { Caller c = CALLER.get(); return c == null || c.admin(); }

    /** Whether the caller may see a row owned by this user id (rows without an owner are admin-only). */
    public static boolean owns(Long ownerId) {
        Caller c = CALLER.get();
        return c == null || c.admin() || (ownerId != null && ownerId.equals(c.id()));
    }

    static void set(Long userId, boolean admin) { CALLER.set(new Caller(userId, admin)); }
    static void set(Long userId)                { set(userId, false); }
    static void clear()                         { CALLER.remove(); }
}
