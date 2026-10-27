package org.cardanofoundation.x402.facilitator.e2e.kit;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * Marks a test class that drives the running devnet stack. A missing stack
 * coordinate FAILS the class — it never skips — so a misconfigured CI run cannot
 * go green by running nothing. Stackless tests simply don't carry it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(StackRequired.Check.class)
public @interface StackRequired {

    final class Check implements BeforeAllCallback {
        @Override
        public void beforeAll(ExtensionContext context) {
            List<String> missing = E2eEnv.missing();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("devnet stack env missing " + missing
                        + " — run the suite through e2e/run.sh --java");
            }
        }
    }
}
