package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decoder for YouTube signature and throttling parameters.
 *
 * <p>
 * Values are decoded with the local decoder when one is set, and the ones it could not decode
 * are sent to https://api.pipepipe.dev/decoder/decode.
 * </p>
 *
 * <p>
 * Decoding is done per value: a value that could not be decoded is left out of the result
 * instead of failing the values that could, so callers can drop only the streams that need it.
 * </p>
 */
public final class YoutubeApiDecoder {

    private static final String API_BASE_URL = "https://api.pipepipe.dev/decoder/decode";
    private static final String USER_AGENT = "PipePipe/4.9.0";
    private static final String SIGNATURE = "sig";
    private static final String THROTTLING_PARAMETER = "n";

    @Nonnull
    private static final Map<String, String> DECODE_CACHE = new ConcurrentHashMap<>();

    @Nullable
    private static volatile YoutubeJavaScriptDecoder localDecoder;

    private YoutubeApiDecoder() {
    }

    /**
     * Decode a signature parameter.
     *
     * @param playerId  the YouTube player ID (8-character hash)
     * @param signature the obfuscated signature to decode
     * @return the deobfuscated signature
     * @throws ParsingException if the signature could not be decoded
     */
    @Nonnull
    static String decodeSignature(@Nonnull final String playerId,
                                  @Nonnull final String signature) throws ParsingException {
        final String decoded = decodeBatch(playerId, Collections.singletonList(signature), null)
                .getSignatures().get(signature);
        if (decoded == null) {
            throw new ParsingException("Could not decode signature: " + signature);
        }
        return decoded;
    }

    /**
     * Decode a throttling parameter (n parameter).
     *
     * @param playerId          the YouTube player ID (8-character hash)
     * @param nParameter        the obfuscated n parameter to decode
     * @return the deobfuscated n parameter
     * @throws ParsingException if the n parameter could not be decoded
     */
    @Nonnull
    static String decodeThrottlingParameter(@Nonnull final String playerId,
                                            @Nonnull final String nParameter)
            throws ParsingException {
        final String decoded = decodeBatch(playerId, null, Collections.singletonList(nParameter))
                .getNParameters().get(nParameter);
        if (decoded == null) {
            throw new ParsingException("Could not decode n parameter: " + nParameter);
        }
        return decoded;
    }

    static void clearCache() {
        DECODE_CACHE.clear();
    }

    private static void disableLocalDecoder(@Nonnull final YoutubeJavaScriptDecoder decoder) {
        if (localDecoder == decoder) {
            localDecoder = null;
            clearCache();
        }
    }

    public static void setLocalDecoder(@Nullable final YoutubeJavaScriptDecoder decoder) {
        localDecoder = decoder;
        clearCache();
    }

    @Nullable
    static YoutubeJavaScriptDecoder getLocalDecoder() {
        return localDecoder;
    }

    static int getCacheSize() {
        return DECODE_CACHE.size();
    }

    /**
     * Batch decode multiple signatures and throttling parameters.
     *
     * <p>
     * Values that could not be decoded, or whose decoded value is known to be invalid, are not
     * present in the returned maps.
     * </p>
     *
     * @param playerId        the YouTube player ID (8-character hash)
     * @param signatureParams list of obfuscated signatures to decode (can be null or empty)
     * @param nParams         list of obfuscated n parameters to decode (can be null or empty)
     * @return a BatchDecodeResult containing the values that could be decoded
     * @throws ParsingException if no value could be decoded because the decoder API could not be
     *                          used
     */
    @Nonnull
    static BatchDecodeResult decodeBatch(@Nonnull final String playerId,
                                         @Nullable final List<String> signatureParams,
                                         @Nullable final List<String> nParams)
            throws ParsingException {
        final Map<String, String> sigResults = new HashMap<>();
        final Map<String, String> nResults = new HashMap<>();
        final Set<String> pendingSigs =
                takeCachedValues(playerId, SIGNATURE, signatureParams, sigResults);
        final Set<String> pendingNs =
                takeCachedValues(playerId, THROTTLING_PARAMETER, nParams, nResults);
        if (pendingSigs.isEmpty() && pendingNs.isEmpty()) {
            return new BatchDecodeResult(sigResults, nResults);
        }

        final YoutubeJavaScriptDecoder decoder = localDecoder;
        if (decoder != null) {
            try {
                final BatchDecodeResult local = decoder.decodeBatch(playerId,
                        new ArrayList<>(pendingSigs), new ArrayList<>(pendingNs));
                takeDecodedValues(playerId, SIGNATURE, local.getSignatures(),
                        pendingSigs, sigResults);
                takeDecodedValues(playerId, THROTTLING_PARAMETER, local.getNParameters(),
                        pendingNs, nResults);
            } catch (final Exception localFailure) {
                disableLocalDecoder(decoder);
            }
            if (pendingSigs.isEmpty() && pendingNs.isEmpty()) {
                return new BatchDecodeResult(sigResults, nResults);
            }
        }

        try {
            decodeRemotely(playerId, pendingSigs, pendingNs, sigResults, nResults);
        } catch (final ParsingException e) {
            if (sigResults.isEmpty() && nResults.isEmpty()) {
                throw e;
            }
        }
        return new BatchDecodeResult(sigResults, nResults);
    }

    /**
     * Put the cached decoded values of the given ones into {@code results}.
     *
     * @return the values which are not cached
     */
    @Nonnull
    private static Set<String> takeCachedValues(@Nonnull final String playerId,
                                                @Nonnull final String type,
                                                @Nullable final Collection<String> values,
                                                @Nonnull final Map<String, String> results) {
        final Set<String> pending = new LinkedHashSet<>();
        if (values == null) {
            return pending;
        }
        for (final String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            final String cached = DECODE_CACHE.get(cacheKey(playerId, type, value));
            if (cached != null) {
                results.put(value, cached);
            } else {
                pending.add(value);
            }
        }
        return pending;
    }

    /**
     * Move the valid decoded values of pending values from {@code decoded} into {@code results}
     * and the cache.
     */
    private static void takeDecodedValues(@Nonnull final String playerId,
                                          @Nonnull final String type,
                                          @Nonnull final Map<String, ?> decoded,
                                          @Nonnull final Set<String> pending,
                                          @Nonnull final Map<String, String> results) {
        for (final String value : new ArrayList<>(pending)) {
            final Object decodedValue = decoded.get(value);
            if (decodedValue instanceof String
                    && isValidDecodedValue(type, value, (String) decodedValue)) {
                results.put(value, (String) decodedValue);
                DECODE_CACHE.put(cacheKey(playerId, type, value), (String) decodedValue);
                pending.remove(value);
            }
        }
    }

    /**
     * Check a decoded value against the known ways decoding fails without an error.
     */
    static boolean isValidDecodedValue(@Nonnull final String type,
                                       @Nonnull final String input,
                                       @Nonnull final String output) {
        if (output.isEmpty()) {
            return false;
        }
        if (THROTTLING_PARAMETER.equals(type)) {
            // When the throttling function throws internally, it returns
            // "enhanced_except_" followed by its input.
            return !output.startsWith("enhanced_except") && !output.endsWith(input);
        }
        // The signature function always splices characters out of its input.
        return !output.equals(input);
    }

    private static void decodeRemotely(@Nonnull final String playerId,
                                       @Nonnull final Set<String> pendingSigs,
                                       @Nonnull final Set<String> pendingNs,
                                       @Nonnull final Map<String, String> sigResults,
                                       @Nonnull final Map<String, String> nResults)
            throws ParsingException {
        final JsonObject jsonResponse;
        try {
            final StringBuilder urlBuilder = new StringBuilder(API_BASE_URL)
                    .append("?player=").append(encode(playerId));
            appendChallenges(urlBuilder, THROTTLING_PARAMETER, pendingNs);
            appendChallenges(urlBuilder, SIGNATURE, pendingSigs);

            final Map<String, List<String>> headers = new HashMap<>();
            headers.put("User-Agent", Collections.singletonList(USER_AGENT));

            final Response response = NewPipe.getDownloader().get(
                    urlBuilder.toString(), headers, Localization.DEFAULT);
            jsonResponse = JsonParser.object().from(response.responseBody());
        } catch (final IOException | ReCaptchaException e) {
            throw new ParsingException("Failed to call decode API", e);
        } catch (final JsonParserException e) {
            throw new ParsingException("Failed to parse decode API response", e);
        }

        if (!"result".equals(jsonResponse.getString("type"))) {
            throw new ParsingException("Decode API returned unexpected type: "
                    + jsonResponse.getString("type") + " " + jsonResponse.getString("error"));
        }

        final JsonArray responses = jsonResponse.getArray("responses");
        for (int i = 0; i < responses.size(); i++) {
            final JsonObject item = responses.getObject(i);
            // An error item means the function for that parameter type could not be used;
            // its values are left undecoded.
            if (!"result".equals(item.getString("type"))) {
                continue;
            }
            // Match values by key instead of by response position.
            final JsonObject data = item.getObject("data");
            takeDecodedValues(playerId, THROTTLING_PARAMETER, data, pendingNs, nResults);
            takeDecodedValues(playerId, SIGNATURE, data, pendingSigs, sigResults);
        }
    }

    private static void appendChallenges(@Nonnull final StringBuilder urlBuilder,
                                         @Nonnull final String type,
                                         @Nonnull final Collection<String> values)
            throws UnsupportedEncodingException {
        if (values.isEmpty()) {
            return;
        }
        urlBuilder.append('&').append(type).append('=');
        boolean first = true;
        for (final String value : values) {
            if (!first) {
                urlBuilder.append(',');
            }
            urlBuilder.append(encode(value));
            first = false;
        }
    }

    @Nonnull
    private static String encode(@Nonnull final String value)
            throws UnsupportedEncodingException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    @Nonnull
    private static String cacheKey(@Nonnull final String playerId,
                                   @Nonnull final String type,
                                   @Nonnull final String value) {
        return playerId + ":" + type + ":" + value;
    }

    /**
     * Result class for batch decode operations.
     */
    public static class BatchDecodeResult {
        private final Map<String, String> signatures;
        private final Map<String, String> nParameters;

        public BatchDecodeResult(@Nonnull final Map<String, String> signatures,
                                 @Nonnull final Map<String, String> nParameters) {
            this.signatures = signatures;
            this.nParameters = nParameters;
        }

        @Nonnull
        public Map<String, String> getSignatures() {
            return signatures;
        }

        @Nonnull
        public Map<String, String> getNParameters() {
            return nParameters;
        }
    }
}
