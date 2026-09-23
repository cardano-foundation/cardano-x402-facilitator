package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

/** Operator approval for an explicitly supplied escrow trust domain, after seller authentication. */
@FunctionalInterface
public interface MasumiDeploymentValidator {
    boolean validate(Context context);

    record Context(String network, String payTo, MasumiBlueprint.MasumiDeployment deployment) { }
}
