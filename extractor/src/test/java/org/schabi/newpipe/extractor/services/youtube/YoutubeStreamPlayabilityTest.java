package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.schabi.newpipe.downloader.DownloaderTestImpl;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.AntiBotException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Extracts real YouTube videos and checks that every stream handed out can actually be played:
 * direct stream URLs must answer a ranged request with 200/206, and SABR sessions must deliver
 * media.
 *
 * <p>Failures of the decoder are simulated by rewriting its API responses, to check that streams
 * which cannot be deobfuscated are left out instead of being returned and failing with 403.</p>
 *
 * <p>Runs only with {@code -Dyoutube.realNetwork=true}, because it needs YouTube and the decoder
 * API to be reachable.</p>
 */
class YoutubeStreamPlayabilityTest {
    /** Videos played over SABR. Music label uploads have signature ciphered formats. */
    private static final String[] VIDEOS = {
            "dQw4w9WgXcQ", // Rick Astley - Never Gonna Give You Up
            "kJQP7kiw5Fk", // Luis Fonsi - Despacito
            "jNQXAC9IVRw", // Me at the zoo
            "aqz-KE-bpKQ", // Big Buck Bunny
    };
    /** Channels with 24/7 live streams, which are played over direct stream URLs. */
    private static final String[] LIVE_CHANNELS = {"LofiGirl", "aljazeeraenglish"};
    private static final Pattern CANONICAL_VIDEO_ID = Pattern.compile(
            "<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([\\w-]{11})\"");

    private static final Pattern SABR_STREAMING_URL = Pattern.compile(
            "\"serverAbrStreamingUrl\"\\s*:\\s*\"[^\"]*\"");

    private static final Pattern FORMAT_URL = Pattern.compile(
            "\"(?:url|signatureCipher)\"\\s*:\\s*\"[^\"]*videoplayback");

    private static DownloaderTestImpl downloader;
    private static volatile boolean playerResponseHadFormatUrls;

    @BeforeAll
    static void setUp() {
        assumeTrue(Boolean.getBoolean("youtube.realNetwork"),
                "Set -Dyoutube.realNetwork=true to run real YouTube tests");
        downloader = new DownloaderTestImpl();
        NewPipe.init(downloader);
    }

    @BeforeEach
    @AfterEach
    void resetDecoderState() {
        if (downloader != null) {
            downloader.setResponseRewriter(null);
        }
        YoutubeApiDecoder.setLocalDecoder(null);
        YoutubeJavaScriptPlayerManager.clearAllCaches();
    }

    static List<String> videoIds() {
        final List<String> ids = new ArrayList<>(List.of(VIDEOS));
        if (!Boolean.getBoolean("youtube.realNetwork")) {
            return ids;
        }
        for (final String channel : LIVE_CHANNELS) {
            final String liveId = resolveLiveVideoId(channel);
            if (liveId != null) {
                ids.add(liveId);
            }
        }
        return ids;
    }

    @ParameterizedTest
    @MethodSource("videoIds")
    void everyReturnedStreamIsPlayable(final String videoId) throws Exception {
        final List<Stream> streams = extractStreams(videoId);
        assertFalse(streams.isEmpty(), "No streams extracted for " + videoId);
        assertStreamsArePlayable(videoId, streams);
    }

    @ParameterizedTest
    @MethodSource("videoIds")
    void streamsWithUndecodableSignaturesAreDropped(final String videoId) throws Exception {
        // SABR media is requested through the SABR URL, which has no signature, so a video
        // played over SABR has to stay playable when signatures cannot be decoded.
        assertOnlyPlayableStreamsWhenDecodingFails(videoId, "sig", usesSabr(videoId));
    }

    @ParameterizedTest
    @MethodSource("videoIds")
    void streamsWithUndecodableThrottlingParametersAreDropped(final String videoId)
            throws Exception {
        // Every stream URL, including the SABR one, has an n parameter, so nothing may be left.
        assertOnlyPlayableStreamsWhenDecodingFails(videoId, "n", false);
    }

    /**
     * YouTube does not always send a SABR streaming URL; streams are then played over the direct
     * URLs of the formats, which for music label uploads need their signature deobfuscated.
     */
    @ParameterizedTest
    @MethodSource("videoIds")
    void directStreamsArePlayable(final String videoId) throws Exception {
        downloader.setResponseRewriter(YoutubeStreamPlayabilityTest::removeSabrStreamingUrl);
        final List<Stream> streams = extractDirectStreams(videoId);
        assertFalse(streams.isEmpty(), "No streams extracted for " + videoId);
        assertStreamsArePlayable(videoId, streams);
    }

    @ParameterizedTest
    @MethodSource("videoIds")
    void directStreamsWithUndecodableSignaturesAreDropped(final String videoId)
            throws Exception {
        final BiFunction<String, String, String> failSignatures = failDecoding("sig");
        downloader.setResponseRewriter((url, body) ->
                failSignatures.apply(url, removeSabrStreamingUrl(url, body)));
        assertOnlyPlayableStreams(videoId, "sig", false);
    }

    /**
     * The on-device decoder of the Android app leaves out the signatures it could not decode.
     * The API has to be asked for them instead of building URLs without a signature.
     */
    @ParameterizedTest
    @MethodSource("videoIds")
    void signaturesMissingFromLocalDecoderAreDecodedByTheApi(final String videoId)
            throws Exception {
        YoutubeApiDecoder.setLocalDecoder(new SignatureLessLocalDecoder());
        downloader.setResponseRewriter(YoutubeStreamPlayabilityTest::removeSabrStreamingUrl);
        final List<Stream> streams = extractDirectStreams(videoId);
        assertFalse(streams.isEmpty(), "No streams extracted for " + videoId);
        assertStreamsArePlayable(videoId, streams);
    }

    @ParameterizedTest
    @MethodSource("videoIds")
    void signaturesNoDecoderCanDecodeAreDropped(final String videoId) throws Exception {
        YoutubeApiDecoder.setLocalDecoder(new SignatureLessLocalDecoder());
        final BiFunction<String, String, String> failSignatures = failDecoding("sig");
        downloader.setResponseRewriter((url, body) ->
                failSignatures.apply(url, removeSabrStreamingUrl(url, body)));
        assertOnlyPlayableStreams(videoId, "sig", false);
    }

    private static List<Stream> extractDirectStreams(final String videoId) throws Exception {
        try {
            return extractStreams(videoId);
        } catch (final ParsingException e) {
            // Without a cause no stream was dropped: YouTube sent no usable format URLs in this
            // response, so there is nothing to check.
            assumeTrue(playerResponseHadFormatUrls && e.getCause() != null,
                    "YouTube sent no usable format URLs for " + videoId + " in this response");
            throw e;
        }
    }

    private static boolean usesSabr(final String videoId) throws Exception {
        final boolean usesSabr = extractStreams(videoId).stream()
                .anyMatch(stream -> stream.getDeliveryMethod() == DeliveryMethod.SABR);
        YoutubeJavaScriptPlayerManager.clearThrottlingParametersCache();
        return usesSabr;
    }

    private static void assertOnlyPlayableStreamsWhenDecodingFails(final String videoId,
                                                                   final String failingType,
                                                                   final boolean mustHaveStreams)
            throws Exception {
        downloader.setResponseRewriter(failDecoding(failingType));
        assertOnlyPlayableStreams(videoId, failingType, mustHaveStreams);
    }

    private static void assertOnlyPlayableStreams(final String videoId,
                                                  final String failingType,
                                                  final boolean mustHaveStreams)
            throws Exception {
        final List<Stream> streams;
        try {
            streams = extractStreams(videoId);
        } catch (final ParsingException e) {
            if (mustHaveStreams) {
                throw e;
            }
            // Every stream needed the undecodable parameter, so nothing playable was left to
            // return. That is the expected outcome; handing out URLs that answer 403 is not.
            System.out.println("[" + videoId + "] no streams without " + failingType + ": " + e
                    + (e.getCause() == null ? "" : " caused by " + e.getCause()));
            return;
        }
        assertStreamsArePlayable(videoId, streams);
    }

    private static List<Stream> extractStreams(final String videoId) throws Exception {
        final StreamExtractor extractor = ServiceList.YouTube.getStreamExtractor(
                "https://www.youtube.com/watch?v=" + videoId);
        try {
            extractor.fetchPage();
        } catch (final ExtractionException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                assumeTrue(!(cause instanceof AntiBotException),
                        "YouTube asks this IP to sign in to confirm it is not a bot");
            }
            throw e;
        }
        final List<Stream> streams = new ArrayList<>();
        streams.addAll(extractor.getVideoStreams());
        streams.addAll(extractor.getVideoOnlyStreams());
        streams.addAll(extractor.getAudioStreams());
        System.out.println("[" + videoId + "] " + extractor.getStreamType() + " extracted "
                + streams.size() + " streams");
        return streams;
    }

    private static void assertStreamsArePlayable(final String videoId,
                                                 final List<Stream> streams) throws Exception {
        final List<String> failures = new ArrayList<>();
        int probed = 0;
        YoutubeSabrInfo sabrInfo = null;
        for (final Stream stream : streams) {
            final String content = stream.getContent();
            final String label = describe(stream);
            if (content.contains("SIGNATURE_PLACEHOLDER")) {
                failures.add(probe(content) + " undecoded signature " + label);
                continue;
            }
            if (stream.getDeliveryMethod() == DeliveryMethod.SABR
                    && stream.getDeliveryMethodInfo() instanceof YoutubeSabrInfo) {
                sabrInfo = (YoutubeSabrInfo) stream.getDeliveryMethodInfo();
                continue;
            }
            if (stream.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP
                    || !stream.isUrl()) {
                System.out.println("[" + videoId + "] skip probe " + label);
                continue;
            }
            final int code = probe(content);
            probed++;
            System.out.println("[" + videoId + "] " + code + " " + label);
            if (code != 200 && code != 206) {
                failures.add(code + " " + label);
            }
        }
        System.out.println("[" + videoId + "] probed " + probed + " direct streams");
        if (sabrInfo != null) {
            probeSabrFormatUrls(videoId, sabrInfo, failures);
            final int segments = probeSabr(sabrInfo);
            System.out.println("[" + videoId + "] SABR session returned " + segments
                    + " segments");
            if (segments == 0) {
                failures.add("SABR session returned no media");
            }
        }
        if (!failures.isEmpty()) {
            fail("Unplayable streams for " + videoId + ":\n" + String.join("\n", failures));
        }
    }

    private static int probe(final String url) throws Exception {
        final Response response = NewPipe.getDownloader().get(url,
                Map.of("Range", Collections.singletonList("bytes=0-1023")));
        return response.responseCode();
    }

    /**
     * The per-format URLs of SABR formats are built from the same signature ciphers as direct
     * stream URLs, so they show whether signatures were deobfuscated correctly.
     */
    private static void probeSabrFormatUrls(final String videoId,
                                            final YoutubeSabrInfo info,
                                            final List<String> failures) throws Exception {
        int probed = 0;
        for (final YoutubeSabrInfo.Format format : info.getFormats()) {
            final String url = format.getInitializationUrl();
            if (url == null || url.isEmpty()) {
                continue;
            }
            final int code = probe(url);
            probed++;
            if (code != 200 && code != 206) {
                failures.add(code + " SABR format URL itag=" + format.getItag());
            }
        }
        System.out.println("[" + videoId + "] probed " + probed + " of "
                + info.getFormats().size() + " SABR format URLs");
    }

    /**
     * Opens a SABR session the way the player does and returns how many media segments the
     * first request delivered.
     */
    private static int probeSabr(final YoutubeSabrInfo info) throws Exception {
        YoutubeSabrInfo.Format audio = null;
        YoutubeSabrInfo.Format video = null;
        for (final YoutubeSabrInfo.Format format : info.getFormats()) {
            if (format.isAudio() && (audio == null || format.getBitrate() < audio.getBitrate())) {
                audio = format;
            } else if (format.isVideo()
                    && (video == null || format.getHeight() < video.getHeight())) {
                video = format;
            }
        }
        final List<YoutubeSabrInfo.Format> formats = new ArrayList<>();
        if (audio != null) {
            formats.add(audio);
        }
        if (video != null) {
            formats.add(video);
        }
        final AtomicInteger segments = new AtomicInteger();
        final YoutubeSabrSession session = new YoutubeSabrSession(info);
        for (int attempt = 0; attempt < 3 && segments.get() == 0; attempt++) {
            final YoutubeSabrSession.RequestResult result = session.requestOnce(
                    YoutubeSabrRequest.preparation(0, formats), segment -> {
                        segments.incrementAndGet();
                        segment.delete();
                    });
            if (result.getBackoffMs() > 0 && segments.get() == 0) {
                Thread.sleep(Math.min(result.getBackoffMs(), 5000));
            }
        }
        return segments.get();
    }

    private static String describe(final Stream stream) {
        return "itag=" + (stream.getItagItem() == null ? "?" : stream.getItagItem().id)
                + " format=" + stream.getFormat()
                + " delivery=" + stream.getDeliveryMethod();
    }

    private static String resolveLiveVideoId(final String channel) {
        try {
            final String page = new DownloaderTestImpl().get(
                    "https://www.youtube.com/@" + channel + "/live",
                    Map.of("Cookie", Collections.singletonList("SOCS=CAI"),
                            "Accept-Language", Collections.singletonList("en")))
                    .responseBody();
            final Matcher matcher = CANONICAL_VIDEO_ID.matcher(page);
            if (matcher.find() && page.contains("\"isLive\":true")) {
                return matcher.group(1);
            }
        } catch (final Exception e) {
            System.out.println("Could not resolve live stream of " + channel + ": " + e);
            return null;
        }
        System.out.println("No live stream found for " + channel);
        return null;
    }

    /**
     * Decodes throttling parameters for real through the decoder API but no signatures, like
     * the on-device decoder does when it cannot use the signature function of a player.
     */
    private static final class SignatureLessLocalDecoder implements YoutubeJavaScriptDecoder {
        private static final Map<String, List<String>> API_HEADERS =
                Map.of("User-Agent", Collections.singletonList("PipePipe/4.9.0"));
        private final DownloaderTestImpl apiDownloader = new DownloaderTestImpl();

        @Nonnull
        @Override
        public PlayerData getPlayerData(@Nonnull final String videoId) throws ParsingException {
            try {
                final JsonObject json = JsonParser.object().from(apiDownloader.get(
                        "https://api.pipepipe.dev/decoder/latest-player", API_HEADERS)
                        .responseBody());
                return new PlayerData(json.getString("player"), json.getInt("signatureTimestamp"));
            } catch (final Exception e) {
                throw new ParsingException("Could not load player metadata", e);
            }
        }

        @Nonnull
        @Override
        public YoutubeApiDecoder.BatchDecodeResult decodeBatch(
                @Nonnull final String playerId,
                @Nullable final List<String> signatures,
                @Nullable final List<String> throttlingParameters) throws ParsingException {
            final Map<String, String> nResults = new HashMap<>();
            if (throttlingParameters != null && !throttlingParameters.isEmpty()) {
                try {
                    final JsonObject json = JsonParser.object().from(apiDownloader.get(
                            "https://api.pipepipe.dev/decoder/decode?player=" + playerId
                                    + "&n=" + String.join(",", throttlingParameters),
                            API_HEADERS).responseBody());
                    final JsonObject data = json.getArray("responses").getObject(0)
                            .getObject("data");
                    for (final String n : throttlingParameters) {
                        nResults.put(n, data.getString(n));
                    }
                } catch (final Exception e) {
                    throw new ParsingException("Could not decode n parameters", e);
                }
            }
            return new YoutubeApiDecoder.BatchDecodeResult(new HashMap<>(), nResults);
        }
    }

    private static String removeSabrStreamingUrl(final String url, final String body) {
        if (!url.contains("/youtubei/v1/player")) {
            return body;
        }
        playerResponseHadFormatUrls = FORMAT_URL.matcher(body).find();
        return SABR_STREAMING_URL.matcher(body)
                .replaceAll("\"serverAbrStreamingUrlRemoved\":\"\"");
    }

    /**
     * Makes the decoder API report that it could not decode one parameter type while still
     * decoding the other, which is how a broken signature or throttling function shows up.
     */
    private static BiFunction<String, String, String> failDecoding(final String failingType) {
        return (url, body) -> {
            if (!url.startsWith("https://api.pipepipe.dev/decoder/decode")
                    || !url.contains("&" + failingType + "=")) {
                return body;
            }
            try {
                final JsonObject json = JsonParser.object().from(body);
                final JsonArray responses = json.getArray("responses");
                // Responses are ordered as the parameters in the URL: n first, then sig.
                final boolean hasBoth = url.contains("&n=") && url.contains("&sig=");
                final int index = hasBoth && "sig".equals(failingType) ? 1 : 0;
                final JsonObject error = new JsonObject();
                error.put("type", "error");
                error.put("error", "simulated " + failingType + " decoding failure");
                responses.set(index, error);
                return JsonWriter.string(json);
            } catch (final Exception e) {
                throw new IllegalStateException("Could not rewrite decoder response: " + body,
                        e);
            }
        };
    }
}
