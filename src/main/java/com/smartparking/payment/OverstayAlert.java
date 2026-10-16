package com.smartparking.payment;

import com.smartparking.infrastructure.DomainException;

/** Pure prepaid-duration and tariff policy used by the payment terminal. */
public final class OverstayAlert {
    public int priceCents(int hours) {
        if (hours < 1 || hours > 24) {
            throw new DomainException("Prepaid parking must be between 1 and 24 hours.");
        }
        return hours * 500;
    }

    public int requireSufficientTender(int hours, int tenderCents) {
        int charge = priceCents(hours);
        if (tenderCents < charge) {
            throw new DomainException("Payment rejected: " + charge + " cents required, but only " + tenderCents + " tendered.");
        }
        return charge;
    }

    public int overdueMinutes(int prepaidHours, int elapsedMinutes) {
        priceCents(prepaidHours);
        if (elapsedMinutes < 0) {
            throw new DomainException("Elapsed parking time cannot be negative.");
        }
        return Math.max(0, elapsedMinutes - prepaidHours * 60);
    }
}
