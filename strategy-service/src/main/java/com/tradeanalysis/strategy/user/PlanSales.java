package com.tradeanalysis.strategy.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which plans users can pick themselves at sign-up or buy online ({@code plans.on-sale}, default FREE,GOLD). The others
 * show as "coming shortly"; an admin can still give any plan, and a user already on a plan can renew it.
 */
@Component
public class PlanSales {

    public static final String COMING_SHORTLY = "Premium coming shortly. You can choose Free or Gold for now.";

    private final Set<Plan> onSale;

    public PlanSales(@Value("${plans.on-sale:FREE,GOLD}") String onSale) {
        EnumSet<Plan> s = EnumSet.of(Plan.FREE);
        Arrays.stream(onSale.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .map(x -> Plan.valueOf(x.toUpperCase(Locale.ROOT))).forEach(s::add);
        this.onSale = s;
    }

    public boolean onSale(Plan p) { return p == null || onSale.contains(p); }

    /** The catalog with each plan marked available or coming shortly. */
    public List<Plan.Info> catalog() {
        return Arrays.stream(Plan.values()).map(p -> p.info(onSale(p))).toList();
    }
}
