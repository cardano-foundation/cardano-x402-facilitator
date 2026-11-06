package org.cardanofoundation.x402.facilitator.e2e.kit;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The one case format of the devnet suite: a named payment, the single defect
 * applied to it, and what the facilitator must answer. Immutable; every builder
 * returns a copy, so a row can be derived from another.
 *
 * <p>Only this TYPE lives in the kit. The rows stay in their test classes with
 * the expected codes written as string literals, because ErrorCodeCoverageTest
 * checks the referencing class's own source for each code's value.
 *
 * <p>How a test uses a row: build the {@link #base()} payment for a wallet (the
 * control, proven valid), apply the {@link #defect()} to it, edit the request
 * with {@link #bodyDefect()} / {@link #rawJsonEdit()}, then
 * {@link TransferAssert#assertCase} asserts {@link #expect()}.
 */
public final class Case {

    /** {@link #expect()} of a case that must verify and settle. */
    public static final String SETTLED = "SETTLED";

    private static final String DEFAULT_ARROW = "←";
    private static Wallet standardReceiver;

    private final String name;
    private final Function<Wallet, Payment> base;
    private final BiFunction<Payment, Wallet, Payment> defect;
    private final UnaryOperator<Map<String, Object>> bodyDefect;
    private final UnaryOperator<String> rawJsonEdit;
    private final String expectedMessage;
    private final String expect;
    private final boolean sharedBase;
    private final String arrow;

    private Case(String name, Function<Wallet, Payment> base, BiFunction<Payment, Wallet, Payment> defect,
                 UnaryOperator<Map<String, Object>> bodyDefect, UnaryOperator<String> rawJsonEdit,
                 String expectedMessage, String expect, boolean sharedBase, String arrow) {
        this.name = name;
        this.base = base;
        this.defect = defect;
        this.bodyDefect = bodyDefect;
        this.rawJsonEdit = rawJsonEdit;
        this.expectedMessage = expectedMessage;
        this.expect = expect;
        this.sharedBase = sharedBase;
        this.arrow = arrow;
    }

    /**
     * A new case.
     *
     * @param expect {@link #SETTLED} or the error code the facilitator must answer on
     *               /verify ({@code invalidReason}) and /settle ({@code errorReason}).
     * @param name   what the case does; part of the test's display name.
     */
    public static Case expect(String expect, String name) {
        return new Case(name, null, (payment, wallet) -> payment, null, null, null, expect, false, DEFAULT_ARROW);
    }

    /** The control: builds the unmutated, valid payment for a wallet. Default: the test class's standard base. */
    public Case base(Function<Wallet, Payment> base) {
        return new Case(name, base, defect, bodyDefect, rawJsonEdit, expectedMessage, expect, sharedBase, arrow);
    }

    /** The transaction/payment mutation, applied to the base. Default: none. */
    public Case defect(BiFunction<Payment, Wallet, Payment> defect) {
        return new Case(name, base, defect, bodyDefect, rawJsonEdit, expectedMessage, expect, sharedBase, arrow);
    }

    /** An edit of the request envelope (the {@link Payment#body()} tree). Default: none. */
    public Case body(UnaryOperator<Map<String, Object>> bodyDefect) {
        return new Case(name, base, defect, bodyDefect, rawJsonEdit, expectedMessage, expect, sharedBase, arrow);
    }

    /** An edit of the serialized request, for what a Java tree cannot hold (an unpaired surrogate). */
    public Case rawJson(UnaryOperator<String> rawJsonEdit) {
        return new Case(name, base, defect, bodyDefect, rawJsonEdit, expectedMessage, expect, sharedBase, arrow);
    }

    /** Pins {@code invalidMessage} / {@code errorMessage}: for codes shared by several checks. */
    public Case message(String expectedMessage) {
        return new Case(name, base, defect, bodyDefect, rawJsonEdit, expectedMessage, expect, sharedBase, arrow);
    }

    /** Marks a case that never spends from the payer, so it may use the class's one shared base payment. */
    public Case sharedBase() {
        return new Case(name, base, defect, bodyDefect, rawJsonEdit, expectedMessage, expect, true, arrow);
    }

    /** Overrides the {@code expect ← name} separator, to keep a display name a class already had. */
    public Case arrow(String arrow) {
        return new Case(name, base, defect, bodyDefect, rawJsonEdit, expectedMessage, expect, sharedBase, arrow);
    }

    public String name() {
        return name;
    }

    /** The control builder; {@link #standardBase} unless the case set its own. */
    public Function<Wallet, Payment> base() {
        return base != null ? base : Case::standardBase;
    }

    /** The receiver of every {@link #standardBase}: one wallet per process. */
    public static Wallet standardReceiver() {
        synchronized (Case.class) {
            if (standardReceiver == null) standardReceiver = Funding.wallet(5);
            return standardReceiver;
        }
    }

    /** One devnet view per process, as in {@code TransferAssert}: a new backend client per base is costly under load. */
    private static final class SharedKit {
        static final DevnetKit KIT = DevnetKit.fromEnv();
    }

    /** The default control: 2 ADA from {@code payer} to {@link #standardReceiver()}, default method, default ttl. */
    public static Payment standardBase(Wallet payer) {
        return new PaymentFactory(SharedKit.KIT).from(payer).payTo(standardReceiver()).lovelace(2_000_000).build();
    }

    public BiFunction<Payment, Wallet, Payment> defect() {
        return defect;
    }

    /** Null when the envelope is not edited. */
    public UnaryOperator<Map<String, Object>> bodyDefect() {
        return bodyDefect;
    }

    /** Null when the serialized request is not edited. */
    public UnaryOperator<String> rawJsonEdit() {
        return rawJsonEdit;
    }

    /** Null when no message is pinned. */
    public String expectedMessage() {
        return expectedMessage;
    }

    public String expect() {
        return expect;
    }

    public boolean usesSharedBase() {
        return sharedBase;
    }

    public boolean expectsSettled() {
        return SETTLED.equals(expect);
    }

    @Override
    public String toString() {
        return expect + " " + arrow + " " + name;
    }
}
