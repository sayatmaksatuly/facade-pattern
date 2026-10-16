package com.smartparking.payment;

import java.util.List;
import java.util.Optional;

public final class PaymentTerminal {
    private final PaymentGateway gateway;
    private final OverstayAlert policy;

    public PaymentTerminal(PaymentGateway gateway, OverstayAlert policy) {
        this.gateway = gateway;
        this.policy = policy;
    }

    public ParkingPayment charge(String operationId, int hours, int tenderCents) {
        return gateway.save(authorized(operationId, hours, tenderCents), true);
    }

    public ParkingPayment authorize(String operationId, int hours, int tenderCents) {
        return gateway.save(authorized(operationId, hours, tenderCents), false);
    }

    private ParkingPayment authorized(String operationId, int hours, int tenderCents) {
        int amount = policy.requireSufficientTender(hours, tenderCents);
        return new ParkingPayment(operationId, hours, tenderCents, amount, ParkingPayment.Status.AUTHORIZED);
    }

    public void capture(String operationId) { gateway.capture(operationId); }
    public void refund(String operationId) { gateway.refund(operationId); }
    public Optional<ParkingPayment> find(String operationId) { return gateway.find(operationId); }
    public List<ParkingPayment> all() { return gateway.all(); }
}
