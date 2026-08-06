package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import com.bloxbean.cardano.client.util.HexUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decodes the Masumi compatibility identifier ({@code extra.blockchainIdentifier}).
 *
 * <p>The identifier is LZString-compressed text whose last segment is the escrow
 * contract address. The facilitator decodes it so that a third, independent
 * source has to agree about where the funds go — alongside the derived escrow
 * address and the seller-signed {@code contractAddress}. An identifier that
 * names a different contract is a rejection.
 *
 * <p>Decompression is bounded during expansion rather than checked afterwards:
 * the upstream LZString decoder only reveals the size once it has already
 * allocated it, which makes a small input capable of exhausting memory.
 *
 * <p>Mirrors {@code identifier.ts} and {@code boundedLz.ts} in the reference
 * implementation.
 */
public final class MasumiIdentifier {

    /** Seller nonce is a fixed-width prefix of the first segment. */
    private static final int SELLER_NONCE_HEX_LENGTH = 64;
    /** Defensive budget on the compressed input. */
    private static final int MAX_COMPRESSED_BYTES = 8 * 1024;
    /** Defensive budget on the expanded text. */
    private static final int MAX_TEXT_CHARS = 64 * 1024;

    private static final Pattern LOWER_HEX = Pattern.compile("^[0-9a-f]+$");

    private MasumiIdentifier() {
    }

    /**
     * The five segments an identifier carries.
     *
     * @param sellerNonce the 32-byte seller nonce hex.
     * @param agentIdentifier registry asset id, or empty.
     * @param buyerNonce buyer nonce hex, possibly empty.
     * @param referenceSignature COSE_Sign1 hex.
     * @param referenceKey COSE_Key hex.
     * @param contractAddress bech32 escrow address.
     */
    public record IdentifierParts(String sellerNonce, String agentIdentifier, String buyerNonce,
                                  String referenceSignature, String referenceKey,
                                  String contractAddress) {
    }

    /**
     * Rebuilds the period-delimited identifier text from its parts.
     *
     * @param parts the identifier segments.
     * @return the ASCII text that gets compressed.
     */
    public static String buildIdentifierText(IdentifierParts parts) {
        return String.join(".",
                parts.sellerNonce() + parts.agentIdentifier(),
                parts.buyerNonce(),
                parts.referenceSignature(),
                parts.referenceKey(),
                parts.contractAddress());
    }

    /**
     * Decodes a hex-encoded compatibility identifier.
     *
     * @param blockchainIdentifier lowercase hex of the compressed UCS-2 bytes.
     * @return the parsed segments, or null when the identifier is unusable.
     */
    public static IdentifierParts decode(String blockchainIdentifier) {
        if (blockchainIdentifier == null
                || blockchainIdentifier.isEmpty()
                || blockchainIdentifier.length() % 2 != 0
                || blockchainIdentifier.length() / 2 > MAX_COMPRESSED_BYTES
                || !LOWER_HEX.matcher(blockchainIdentifier).matches()) {
            return null;
        }
        String text = decompressBounded(HexUtil.decodeHexString(blockchainIdentifier), MAX_TEXT_CHARS);
        if (text == null || text.length() > MAX_TEXT_CHARS) return null;

        // -1 keeps trailing empty segments, which a buyer nonce may legitimately be.
        String[] segments = text.split("\\.", -1);
        if (segments.length != 5) return null;
        String sellerIdentifier = segments[0];
        if (sellerIdentifier.length() < SELLER_NONCE_HEX_LENGTH) return null;
        return new IdentifierParts(
                sellerIdentifier.substring(0, SELLER_NONCE_HEX_LENGTH),
                sellerIdentifier.substring(SELLER_NONCE_HEX_LENGTH),
                segments[1], segments[2], segments[3], segments[4]);
    }

    /**
     * Decompresses the big-endian UCS-2 byte form LZString's
     * {@code compressToUint8Array} produces, enforcing the output limit as it
     * expands rather than after the fact.
     *
     * @param compressed the compressed bytes.
     * @param maxOutputChars the ceiling on produced UTF-16 code units.
     * @return the text, or null when malformed or oversized.
     */
    static String decompressBounded(byte[] compressed, int maxOutputChars) {
        if (compressed.length == 0 || compressed.length % 2 != 0) return null;
        int codeUnitCount = compressed.length / 2;

        BitReader reader = new BitReader(compressed, codeUnitCount);

        List<String> dictionary = new ArrayList<>();
        dictionary.add(null);
        dictionary.add(null);
        dictionary.add(null);
        int enlargeIn = 4;
        int dictionarySize = 4;
        int bitsPerCode = 3;

        Integer initialKind = reader.read(2);
        if (initialKind == null) return null;
        if (initialKind == 2) return "";
        Integer initialValue = reader.read(initialKind == 0 ? 8 : 16);
        if (initialValue == null) return null;

        String previous = String.valueOf((char) initialValue.intValue());
        dictionary.add(previous); // index 3
        StringBuilder output = new StringBuilder(previous);
        if (output.length() > maxOutputChars) return null;

        while (true) {
            Integer encoded = reader.read(bitsPerCode);
            if (encoded == null) return null;
            int code = encoded;

            if (code == 0 || code == 1) {
                Integer literal = reader.read(code == 0 ? 8 : 16);
                if (literal == null) return null;
                setDictionary(dictionary, dictionarySize, String.valueOf((char) literal.intValue()));
                code = dictionarySize;
                dictionarySize++;
                enlargeIn--;
            } else if (code == 2) {
                return output.toString();
            }

            if (enlargeIn == 0) {
                enlargeIn = 1 << bitsPerCode;
                bitsPerCode++;
            }

            String entry;
            if (code < dictionary.size() && dictionary.get(code) != null) {
                entry = dictionary.get(code);
            } else if (code == dictionarySize) {
                entry = previous + previous.charAt(0);
            } else {
                return null;
            }
            if (output.length() + entry.length() > maxOutputChars) return null;
            output.append(entry);

            setDictionary(dictionary, dictionarySize, previous + entry.charAt(0));
            dictionarySize++;
            enlargeIn--;
            previous = entry;

            if (enlargeIn == 0) {
                enlargeIn = 1 << bitsPerCode;
                bitsPerCode++;
            }
        }
    }

    private static void setDictionary(List<String> dictionary, int index, String value) {
        while (dictionary.size() <= index) dictionary.add(null);
        dictionary.set(index, value);
    }

    /** Reads LZString's bit stream out of big-endian UCS-2 code units. */
    private static final class BitReader {
        private final byte[] bytes;
        private final int codeUnitCount;
        private int codeUnitIndex;
        private int bitMask = 0x8000;
        private int currentCodeUnit;

        BitReader(byte[] bytes, int codeUnitCount) {
            this.bytes = bytes;
            this.codeUnitCount = codeUnitCount;
            this.currentCodeUnit = codeUnitAt(0);
        }

        private int codeUnitAt(int index) {
            return ((bytes[index * 2] & 0xFF) << 8) | (bytes[index * 2 + 1] & 0xFF);
        }

        Integer read(int count) {
            int value = 0;
            int power = 1;
            for (int i = 0; i < count; i++) {
                if (codeUnitIndex >= codeUnitCount) return null;
                if ((currentCodeUnit & bitMask) != 0) value |= power;
                power *= 2;
                bitMask >>= 1;
                if (bitMask == 0) {
                    bitMask = 0x8000;
                    codeUnitIndex++;
                    if (codeUnitIndex < codeUnitCount) currentCodeUnit = codeUnitAt(codeUnitIndex);
                }
            }
            return value;
        }
    }
}
