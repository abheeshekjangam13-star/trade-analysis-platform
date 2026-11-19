package com.tradeanalysis.strategy.user;

/** Tests in other packages acting as a signed-in user (CurrentUser's setters are package-private). */
public final class TestCaller {
    private TestCaller() {}
    public static void signIn(Long userId, boolean admin) { CurrentUser.set(userId, admin); }
    public static void signOut() { CurrentUser.clear(); }
}
