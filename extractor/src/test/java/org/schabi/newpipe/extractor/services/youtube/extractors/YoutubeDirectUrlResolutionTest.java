package org.schabi.newpipe.extractor.services.youtube.extractors;

import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class YoutubeDirectUrlResolutionTest {
    private static final String BASE =
            "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1&itag=251&n=encN&cpn=CPN";

    private static YoutubeApiDecoder.BatchDecodeResult decoded(final String sig,
                                                               final String sigValue,
                                                               final String n,
                                                               final String nValue) {
        final Map<String, String> sigs = new HashMap<>();
        final Map<String, String> ns = new HashMap<>();
        if (sig != null) {
            sigs.put(sig, sigValue);
        }
        if (n != null) {
            ns.put(n, nValue);
        }
        return new YoutubeApiDecoder.BatchDecodeResult(sigs, ns);
    }

    @Test
    void decodedSignatureAndThrottlingParameterAreApplied() {
        final String url = YoutubeStreamExtractor.resolveDirectUrl(BASE, "obfSig", "sig",
                decoded("obfSig", "AB+C=D", "encN", "decN"));

        assertEquals("https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1&itag=251"
                + "&n=decN&cpn=CPN&sig=AB%2BC%3DD", url);
    }

    @Test
    void streamWithUndecodedSignatureIsDropped() {
        assertNull(YoutubeStreamExtractor.resolveDirectUrl(BASE, "obfSig", "sig",
                decoded(null, null, "encN", "decN")));
    }

    @Test
    void streamWithUndecodedThrottlingParameterIsDropped() {
        assertNull(YoutubeStreamExtractor.resolveDirectUrl(BASE, null, null,
                decoded(null, null, null, null)));
    }

    @Test
    void urlWithoutSignatureOrThrottlingParameterIsUnchanged() {
        final String url = "https://rr1---sn-abc.googlevideo.com/videoplayback?itag=18&cpn=CPN";
        assertEquals(url, YoutubeStreamExtractor.resolveDirectUrl(url, null, null,
                decoded(null, null, null, null)));
    }

    @Test
    void signatureParameterDefaultsToSignature() {
        final String url = "https://rr1---sn-abc.googlevideo.com/videoplayback?itag=18";
        assertEquals(url + "&signature=dec", YoutubeStreamExtractor.resolveDirectUrl(
                url, "obf", null, decoded("obf", "dec", null, null)));
    }

    @Test
    void onlyTheThrottlingParameterValueIsReplaced() {
        // The n value also appears inside another parameter; only n itself may change.
        final String url = "https://rr1---sn-abc.googlevideo.com/videoplayback?id=xxencNxx"
                + "&n=encN&cpn=CPN";
        assertEquals("https://rr1---sn-abc.googlevideo.com/videoplayback?id=xxencNxx"
                + "&n=decN&cpn=CPN", YoutubeStreamExtractor.resolveDirectUrl(url, null, null,
                decoded(null, null, "encN", "decN")));
    }
}
