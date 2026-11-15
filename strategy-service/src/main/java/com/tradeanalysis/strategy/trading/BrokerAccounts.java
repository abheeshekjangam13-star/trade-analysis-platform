package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.strategy.user.AppUser;
import com.tradeanalysis.strategy.user.AppUserRepository;
import com.tradeanalysis.strategy.user.Plan;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Which broker a user trades on: their own Kite account ({@link KiteAccounts}) or their own Angel One account
 * ({@link AngelAccounts}); a user connects at most one. Admins without either use the platform Kite session. Market
 * data (quotes, option contracts, candles, paper fills) always uses the platform Kite session.
 */
@Service
public class BrokerAccounts {

    public enum Broker {
        KITE("Kite"), ANGEL_ONE("Angel One");
        public final String label;
        Broker(String label) { this.label = label; }
    }

    private final KiteAccounts kite;
    private final AngelAccounts angel;
    private final AppUserRepository users;
    private final Clock clock;

    public BrokerAccounts(KiteAccounts kite, AngelAccounts angel, AppUserRepository users, Clock clock) {
        this.kite = kite; this.angel = angel; this.users = users; this.clock = clock;
    }

    /** The account for this user's orders and broker views (Angel One if they connected it, else Kite). */
    public KiteApi forUser(Long userId) {
        return angel.hasAccount(userId) ? angel.forUser(userId) : kite.forUser(userId);
    }

    /** The account that holds this broker order: Angel One ids start with "AO:", everything else is Kite. */
    public KiteApi forOrder(Long userId, String brokerOrderId) {
        if (AngelOneRestApi.isAngelOrderId(brokerOrderId)) {
            if (!angel.hasAccount(userId))
                throw new TradingException("Order " + brokerOrderId + " was placed on an Angel One account that is no longer connected");
            return angel.forUser(userId);
        }
        return kite.forUser(userId);
    }

    /** The broker this user's orders go to (Kite also for admins on the platform session). */
    public Broker brokerOf(Long userId) { return angel.hasAccount(userId) ? Broker.ANGEL_ONE : Broker.KITE; }

    public String label(Long userId) { return brokerOf(userId).label; }

    /** Whether the user connected an account of their own (either broker). */
    public boolean hasOwnAccount(Long userId) { return kite.hasOwnAccount(userId) || angel.hasAccount(userId); }

    /** Refuses connecting a second broker: disconnect the other one first. */
    public void requireNoOtherBroker(Long userId, Broker wanted) {
        if (wanted == Broker.KITE && angel.hasAccount(userId))
            throw new TradingException("You already connected an Angel One account: disconnect it first to use Kite");
        if (wanted == Broker.ANGEL_ONE && kite.hasOwnAccount(userId))
            throw new TradingException("You already connected a Kite account: disconnect it first to use Angel One");
    }

    /** Why this user may not open LIVE positions, or null if they may (system/admins always; others: PREMIUM + own account). */
    public String liveRefusal(Long userId) {
        if (userId == null) return null;
        AppUser u = users.findById(userId).orElse(null);
        if (u == null) return "Unknown user";
        if (u.isAdmin()) return null;
        if (!u.effectivePlan(clock.instant()).atLeast(Plan.PREMIUM))
            return "Live trading needs the PREMIUM plan (your plan is " + u.effectivePlan(clock.instant()) + ")";
        if (!hasOwnAccount(userId)) return "Connect your trading account first (Account page)";
        return null;
    }
}
