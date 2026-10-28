package com.paf.aerovisit.service;

import org.springframework.stereotype.Service;

import com.paf.aerovisit.repository.VisitorRepository;

/**
 * 🚦 TWO GATES, ONE PASS: the gate checks read the visitor, then save, so two guards (or two gates sharing the Guard
 * account) pressing Accept on the same pass at the same moment could both check it in and send two emails. claim()
 * moves the status in one database UPDATE that only matches while the status is still the old one, so exactly one
 * of them wins; the other is told the visitor is already checked in (or out). Optional in the controllers, so
 * web-layer test slices without a database still start.
 */
@Service
public class GateClaim {

    private final VisitorRepository repo;

    public GateClaim(VisitorRepository repo) {
        this.repo = repo;
    }

    /** True if this request moved the visitor from `from` to `to`; false if someone else changed the status first. */
    public boolean claim(Long visitorId, String from, String to) {
        return repo.claimStatus(visitorId, from, to) == 1;
    }

    /** The same, for callers that may have no GateClaim (test slices): without one, every claim succeeds, as before. */
    public static boolean claim(GateClaim gate, Long visitorId, String from, String to) {
        return gate == null || gate.claim(visitorId, from, to);
    }
}
