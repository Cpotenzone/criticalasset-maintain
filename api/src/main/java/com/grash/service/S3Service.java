package com.grash.service;

import com.grash.exception.CustomException;
import com.grash.model.File;
import com.grash.utils.Helper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.time.Duration;

/**
 * S3-backed storage — the production backend since the GCP→AWS migration
 * (2026-08-01).
 * <p>
 * Selected with {@code STORAGE_TYPE=S3}. {@link GCPService} is retained so a
 * self-hoster on Google, or a read of pre-migration objects, still works;
 * {@link MinioService} is retained for self-hosters running their own object
 * store.
 * <p>
 * Credentials come from the DEFAULT PROVIDER CHAIN, which on EC2 means the
 * instance role. That is deliberate and is the whole reason this class exists
 * rather than pointing {@link MinioService} at S3: the MinIO client needs a
 * static access key/secret pair, and minting a long-lived IAM user key for a
 * workload that already has a role is exactly the credential sprawl the
 * migration was meant to end.
 * <p>
 * Paths are stored as bare keys ({@code folder/name}), identical to the GCP
 * backend, so {@code File.path} rows written before the migration keep
 * resolving without a data migration.
 */
@Service
@RequiredArgsConstructor
public class S3Service implements StorageService {
    @Value("${storage.s3.bucket:}")
    private String bucket;
    @Value("${storage.s3.region:us-east-1}")
    private String region;

    private S3Client client;
    private S3Presigner presigner;
    private boolean configured = false;

    @PostConstruct
    private void init() {
        // No bucket → stay unconfigured and fail loudly on first use, rather
        // than constructing a client that 404s on every object. An empty
        // bucket name previously produced runtime errors far from the cause.
        if (bucket == null || bucket.isEmpty()) {
            return;
        }
        Region r = Region.of(region);
        client = S3Client.builder()
                .region(r)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        presigner = S3Presigner.builder()
                .region(r)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        configured = true;
    }

    @PreDestroy
    private void close() {
        if (client != null) client.close();
        if (presigner != null) presigner.close();
    }

    public String upload(MultipartFile file, String folder) {
        checkIfConfigured();
        Helper helper = new Helper();
        try {
            String filePath = folder + "/" + helper.generateString() + " " + file.getOriginalFilename();
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(filePath)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromBytes(file.getBytes())
            );
            return filePath;
        } catch (IllegalStateException | IOException | S3Exception e) {
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    public String upload(byte[] data, String fileName, String folder) {
        checkIfConfigured();
        String filePath = folder + "/" + fileName;
        try {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(filePath).build(),
                    RequestBody.fromBytes(data)
            );
            return filePath;
        } catch (S3Exception e) {
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    public byte[] download(String filePath) {
        checkIfConfigured();
        try {
            ResponseBytes<GetObjectResponse> obj = client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(filePath).build());
            return obj.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new CustomException("File not found", HttpStatus.NOT_FOUND);
        } catch (S3Exception e) {
            throw new CustomException("Error retrieving file", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public byte[] download(File file) {
        checkIfConfigured();
        return download(file.getPath());
    }

    public String generateSignedUrl(File file, long expirationMinutes) {
        return generateSignedUrl(file.getPath(), expirationMinutes);
    }

    /**
     * A presigned GET URL — the S3 equivalent of a GCS V4 signed URL.
     * <p>
     * Unlike the GCP backend this does NOT fetch the object first to prove it
     * exists: presigning is a local signing operation, and a HEAD per link
     * would double the request count on every attachment list. A link to a
     * missing key presigns fine and 404s when followed, which is the same
     * outcome the caller would have got anyway.
     */
    public String generateSignedUrl(String filePath, long expirationMinutes) {
        checkIfConfigured();
        try {
            GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(expirationMinutes))
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(filePath).build())
                    .build();
            return presigner.presignGetObject(req).url().toString();
        } catch (S3Exception e) {
            throw new CustomException("Error generating signed URL: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void checkIfConfigured() {
        if (!configured)
            throw new CustomException("S3 storage is not configured. Set STORAGE_S3_BUCKET (and "
                    + "STORAGE_S3_REGION) in the environment.", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
