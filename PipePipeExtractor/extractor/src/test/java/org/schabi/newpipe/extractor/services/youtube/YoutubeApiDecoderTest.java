package org.schabi.newpipe.extractor.services.youtube;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubeApiDecoderTest {
    private static final String PLAYER = "abcdef12";
    private static final String SIG = "AOq0QJ8wRAIgSIG1234567890";
    private static final String N = "nThrottle123";

    private FakeDownloader downloader;

    @BeforeEach
    void setUp() {
        downloader = new FakeDownloader();
        NewPipe.init(downloader);
        YoutubeApiDecoder.setLocalDecoder(null);
    }

    @AfterEach
    void tearDown() {
        YoutubeApiDecoder.setLocalDecoder(null);
    }

    @Test
    void signatureErrorDoesNotDiscardThrottlingParameters() throws Exception {
        downloader.respond = url -> "{\"type\":\"result\",\"responses\":["
                + "{\"type\":\"result\",\"data\":{\"" + N + "\":\"decodedN\"}},"
                + "{\"type\":\"error\",\"error\":\"Failed to extract sig function\"}]}";

        final YoutubeApiDecoder.BatchDecodeResult result = YoutubeApiDecoder.decodeBatch(
                PLAYER, Collections.singletonList(SIG), Collections.singletonList(N));

        assertEquals("decodedN", result.getNParameters().get(N));
        assertNull(result.getSignatures().get(SIG));
    }

    @Test
    void missingValuesAreLeftOutInsteadOfFailingTheBatch() throws Exception {
        final String otherSig = "AOq0QJ8wRAIgOTHER987654321";
        downloader.respond = url -> "{\"type\":\"result\",\"responses\":["
                + "{\"type\":\"result\",\"data\":{\"" + N + "\":\"decodedN\"}},"
                + "{\"type\":\"result\",\"data\":{\"" + SIG + "\":\"decodedSig\","
                + "\"" + otherSig + "\":\"\"}}]}";

        final YoutubeApiDecoder.BatchDecodeResult result = YoutubeApiDecoder.decodeBatch(
                PLAYER, Arrays.asList(SIG, otherSig), Collections.singletonList(N));

        assertEquals("decodedSig", result.getSignatures().get(SIG));
        assertFalse(result.getSignatures().containsKey(otherSig));
        assertEquals("decodedN", result.getNParameters().get(N));
    }

    @Test
    void valuesAreMatchedByKeyNotByResponsePosition() throws Exception {
        downloader.respond = url -> "{\"type\":\"result\",\"responses\":["
                + "{\"type\":\"result\",\"data\":{\"" + SIG + "\":\"decodedSig\"}},"
                + "{\"type\":\"result\",\"data\":{\"" + N + "\":\"decodedN\"}}]}";

        final YoutubeApiDecoder.BatchDecodeResult result = YoutubeApiDecoder.decodeBatch(
                PLAYER, Collections.singletonList(SIG), Collections.singletonList(N));

        assertEquals("decodedSig", result.getSignatures().get(SIG));
        assertEquals("decodedN", result.getNParameters().get(N));
    }

    @Test
    void failedThrottlingFunctionOutputIsRejected() throws Exception {
        downloader.respond = url -> "{\"type\":\"result\",\"responses\":["
                + "{\"type\":\"result\",\"data\":{\"" + N + "\":\"enhanced_except_AAA_" + N
                + "\"}}]}";

        final YoutubeApiDecoder.BatchDecodeResult result = YoutubeApiDecoder.decodeBatch(
                PLAYER, null, Collections.singletonList(N));

        assertTrue(result.getNParameters().isEmpty());
    }

    @Test
    void unchangedSignatureIsRejected() {
        assertFalse(YoutubeApiDecoder.isValidDecodedValue("sig", SIG, SIG));
        assertTrue(YoutubeApiDecoder.isValidDecodedValue("sig", SIG, SIG.substring(2)));
        assertFalse(YoutubeApiDecoder.isValidDecodedValue("n", N, N));
        assertFalse(YoutubeApiDecoder.isValidDecodedValue("n", N, ""));
        assertTrue(YoutubeApiDecoder.isValidDecodedValue("n", N, "decodedN"));
    }

    @Test
    void unreachableApiWithNothingDecodedThrows() {
        downloader.respond = url -> {
            throw new IllegalStateException("offline");
        };

        assertThrows(ParsingException.class, () -> YoutubeApiDecoder.decodeBatch(
                PLAYER, Collections.singletonList(SIG), Collections.singletonList(N)));
    }

    @Test
    void localDecoderGapsAreFilledByTheApi() throws Exception {
        final List<String> apiUrls = new ArrayList<>();
        downloader.respond = url -> {
            apiUrls.add(url);
            return "{\"type\":\"result\",\"responses\":["
                    + "{\"type\":\"result\",\"data\":{\"" + SIG + "\":\"remoteSig\"}}]}";
        };
        YoutubeApiDecoder.setLocalDecoder(new FakeLocalDecoder(
                Collections.emptyMap(), Collections.singletonMap(N, "localN")));

        final YoutubeApiDecoder.BatchDecodeResult result = YoutubeApiDecoder.decodeBatch(
                PLAYER, Collections.singletonList(SIG), Collections.singletonList(N));

        assertEquals("remoteSig", result.getSignatures().get(SIG));
        assertEquals("localN", result.getNParameters().get(N));
        assertEquals(1, apiUrls.size());
        assertTrue(apiUrls.get(0).contains("sig="));
        assertFalse(apiUrls.get(0).contains("&n="), "Decoded n was sent to the API again");
    }

    @Test
    void decodedValuesAreCached() throws Exception {
        final int[] calls = {0};
        downloader.respond = url -> {
            calls[0]++;
            return "{\"type\":\"result\",\"responses\":["
                    + "{\"type\":\"result\",\"data\":{\"" + N + "\":\"decodedN\"}}]}";
        };

        assertEquals("decodedN", YoutubeApiDecoder.decodeThrottlingParameter(PLAYER, N));
        assertEquals("decodedN", YoutubeApiDecoder.decodeThrottlingParameter(PLAYER, N));
        assertEquals(1, calls[0]);
    }

    @Test
    void undecodableSingleSignatureThrows() {
        downloader.respond = url -> "{\"type\":\"result\",\"responses\":["
                + "{\"type\":\"error\",\"error\":\"Failed to extract sig function\"}]}";

        assertThrows(ParsingException.class,
                () -> YoutubeApiDecoder.decodeSignature(PLAYER, SIG));
    }

    private static final class FakeDownloader extends Downloader {
        private Function<String, String> respond;

        @Override
        public Response execute(@Nonnull final Request request) throws IOException {
            final String body;
            try {
                body = respond.apply(request.url());
            } catch (final IllegalStateException e) {
                throw new IOException(e);
            }
            return new Response(200, "OK", new HashMap<>(), body, body.getBytes(),
                    request.url());
        }

        @Override
        public CancellableCall executeAsync(@Nonnull final Request request,
                                            final AsyncCallback callback) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeLocalDecoder implements YoutubeJavaScriptDecoder {
        private final Map<String, String> signatures;
        private final Map<String, String> nParameters;

        FakeLocalDecoder(final Map<String, String> signatures,
                         final Map<String, String> nParameters) {
            this.signatures = signatures;
            this.nParameters = nParameters;
        }

        @Nonnull
        @Override
        public PlayerData getPlayerData(@Nonnull final String videoId) {
            return new PlayerData(PLAYER, 20000);
        }

        @Nonnull
        @Override
        public YoutubeApiDecoder.BatchDecodeResult decodeBatch(
                @Nonnull final String playerId,
                @Nullable final List<String> sigs,
                @Nullable final List<String> throttlingParameters) {
            return new YoutubeApiDecoder.BatchDecodeResult(
                    new HashMap<>(signatures), new HashMap<>(nParameters));
        }
    }
}
