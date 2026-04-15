package dev.kefir;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;

import java.util.concurrent.Executor;

public class TokenInterceptor extends CallCredentials {
    private final String token;

    public TokenInterceptor(String token) {
        this.token = token;
    }

    @Override
    public void applyRequestMetadata(RequestInfo requestInfo, Executor executor, MetadataApplier metadataApplier) {
        executor.execute(() -> {
            try {
                Metadata headers = new Metadata();
                Metadata.Key<String> authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
                headers.put(authKey, "Bearer " + token);
                metadataApplier.apply(headers);
            } catch (Throwable e) {
                metadataApplier.fail(Status.UNAUTHENTICATED.withCause(e));
            }
        });
    }

    @Override
    public void thisUsesUnstableApi() {}
}
