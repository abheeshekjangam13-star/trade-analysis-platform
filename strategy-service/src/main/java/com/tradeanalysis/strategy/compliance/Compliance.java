package com.tradeanalysis.strategy.compliance;

import com.tradeanalysis.strategy.user.AppUserRepository;
import org.springframework.stereotype.Service;

/**
 * The compliance conditions for trading live, checked when a user switches a strategy to LIVE or places a live order
 * (after the plan, broker and live-access checks): the risk disclosure (users, not admins), and, when an admin turned
 * them on, two-factor sign-in and a verified PAN (everyone).
 */
@Service
public class Compliance {

    private final ComplianceSettings settings;
    private final RiskDisclosure disclosure;
    private final TwoFactor twoFactor;
    private final PanRegistry pans;
    private final AppUserRepository users;

    public Compliance(ComplianceSettings settings, RiskDisclosure disclosure, TwoFactor twoFactor, PanRegistry pans, AppUserRepository users) {
        this.settings = settings; this.disclosure = disclosure; this.twoFactor = twoFactor; this.pans = pans; this.users = users;
    }

    /** Why this user may not trade live for compliance reasons, or null. */
    public String liveRefusal(Long userId) {
        if (userId == null) return null;
        ComplianceSettings.Settings s = settings.get();
        if (s.liveNeedsPan() && !pans.verified(userId))
            return pans.kyc(userId).panAdded() ? "Your PAN is waiting for verification: live trading starts once it is verified"
                    : "Add your PAN on My account first: live trading needs a verified PAN";
        if (s.liveNeedsTwoFactor() && !twoFactor.enabled(userId))
            return "Turn on two-factor sign-in on My account first: live trading needs it";
        boolean admin = users.findById(userId).map(com.tradeanalysis.strategy.user.AppUser::isAdmin).orElse(false);
        if (!admin && !disclosure.accepted(userId))
            return "Read and accept the live trading risk disclosure first (Trading page)";
        return null;
    }
}
