package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.bloxbean.cardano.client.account.Account;

/** A devnet key pair with its base address. Fresh and random per test class. */
public record Wallet(Account account) {

    public String address() {
        return account.baseAddress();
    }

    @Override
    public String toString() {
        return address();
    }
}
