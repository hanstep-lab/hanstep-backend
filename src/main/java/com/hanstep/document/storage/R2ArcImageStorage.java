package com.hanstep.document.storage;

import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * R2 구현. 클라이언트는 처음 쓸 때 만든다(R2 값이 비어 있어도 앱·테스트는 뜬다).
 * path-style, checksum WHEN_REQUIRED 는 기준선 2 시제품(DocUploadR2PerfTest)과 같다.
 */
@Component
public class R2ArcImageStorage implements ArcImageStorage {

    private final ArcStorageProperties properties;
    private volatile S3Client client;

    public R2ArcImageStorage(ArcStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public void put(String objectKey, byte[] bytes, String contentType) {
        try {
            client().putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(objectKey)
                    .contentType(contentType).contentLength((long) bytes.length).build(), RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            throw new ArcStorageException("R2 put failed: " + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client().deleteObject(b -> b.bucket(properties.bucket()).key(objectKey));
        } catch (SdkException e) {
            throw new ArcStorageException("R2 delete failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private S3Client client() {
        S3Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                List<String> missing = properties.missing();
                if (!missing.isEmpty()) {
                    throw new ArcStorageException("R2 settings missing: " + missing, null);
                }
                client = S3Client.builder()
                        .endpointOverride(URI.create(properties.endpoint()))
                        .region(Region.of(properties.region()))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey())))
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                        .build();
            }
            return client;
        }
    }
}
