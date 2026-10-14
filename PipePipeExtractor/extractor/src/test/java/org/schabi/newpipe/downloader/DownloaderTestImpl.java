package org.schabi.newpipe.downloader;

import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * Real-network {@link Downloader} for JVM tests.
 *
 * <p>A response rewriter can be installed to simulate a failing remote service, e.g. a decoder
 * API that can no longer decode signatures.</p>
 */
public final class DownloaderTestImpl extends Downloader {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    private static final int MAX_RATE_LIMIT_RETRIES = 4;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .build();

    @Nullable
    private volatile BiFunction<String, String, String> responseRewriter;

    /**
     * @param rewriter receives the request URL and response body and returns the body to hand to
     *                 the extractor, or {@code null} to clear the rewriter
     */
    public void setResponseRewriter(@Nullable final BiFunction<String, String, String> rewriter) {
        responseRewriter = rewriter;
    }

    @Override
    public Response execute(@Nonnull final Request request)
            throws IOException, ReCaptchaException {
        // Back off when rate limited, as the tests send many requests in a short time.
        for (int attempt = 0; ; attempt++) {
            try (okhttp3.Response response = newCall(request).execute()) {
                if (response.code() != 429 || attempt == MAX_RATE_LIMIT_RETRIES) {
                    return toResponse(request.url(), response);
                }
            }
            try {
                Thread.sleep(2000L << attempt);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }

    @Override
    public CancellableCall executeAsync(@Nonnull final Request request,
                                        @Nonnull final AsyncCallback callback) {
        final Call call = newCall(request);
        final CancellableCall cancellableCall = new CancellableCall(call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@Nonnull final Call c, @Nonnull final IOException e) {
                try {
                    callback.onError(e);
                } finally {
                    cancellableCall.setFinished();
                }
            }

            @Override
            public void onResponse(@Nonnull final Call c,
                                   @Nonnull final okhttp3.Response response) {
                try (response) {
                    callback.onSuccess(toResponse(request.url(), response));
                } catch (final Exception e) {
                    callback.onError(e);
                } finally {
                    cancellableCall.setFinished();
                }
            }
        });
        return cancellableCall;
    }

    @Nonnull
    private Call newCall(@Nonnull final Request request) {
        final byte[] data = request.dataToSend();
        final okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .method(request.httpMethod(), data == null ? null : RequestBody.create(data))
                .url(request.url())
                .header("User-Agent", USER_AGENT);
        for (final Map.Entry<String, List<String>> header : request.headers().entrySet()) {
            builder.removeHeader(header.getKey());
            for (final String value : header.getValue()) {
                builder.addHeader(header.getKey(), value);
            }
        }
        final OkHttpClient callClient = request.followRedirects() ? client
                : client.newBuilder().followRedirects(false).followSslRedirects(false).build();
        return callClient.newCall(builder.build());
    }

    @Nonnull
    private Response toResponse(@Nonnull final String url,
                                @Nonnull final okhttp3.Response response)
            throws IOException, ReCaptchaException {
        if (response.code() == 429) {
            throw new ReCaptchaException("reCaptcha Challenge requested", url);
        }
        final ResponseBody body = response.body();
        byte[] raw = body == null ? new byte[0] : body.bytes();
        String text = new String(raw, StandardCharsets.UTF_8);
        final BiFunction<String, String, String> rewriter = responseRewriter;
        if (rewriter != null) {
            final String rewritten = rewriter.apply(url, text);
            if (rewritten != null && !rewritten.equals(text)) {
                text = rewritten;
                raw = text.getBytes(StandardCharsets.UTF_8);
            }
        }
        return new Response(response.code(), response.message(),
                response.headers().toMultimap(), text, raw,
                response.request().url().toString());
    }
}
