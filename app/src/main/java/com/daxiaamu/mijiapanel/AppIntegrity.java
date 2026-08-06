package com.daxiaamu.mijiapanel;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Verifies the installed module through platform and direct APK evidence paths. */
final class AppIntegrity {
    static final String MODULE_PACKAGE = "com.daxiaamu.mijiapanel";

    private static final String TAG = "MijiaPanelIntegrity";
    private static final int APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a;
    private static final int APK_SIGNATURE_SCHEME_V3_BLOCK_ID = 0xf05368c0;
    private static final int APK_SIGNATURE_SCHEME_V31_BLOCK_ID = 0x1b93ad61;
    private static final byte[] APK_SIG_BLOCK_MAGIC =
            "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);
    private static final int ZIP_EOCD_MIN_SIZE = 22;
    private static final int ZIP_MAX_COMMENT_SIZE = 65_535;

    private AppIntegrity() {
    }

    static boolean verify(Context context) {
        return verifyPackage(context, MODULE_PACKAGE);
    }

    static boolean verifyPackage(Context context, String packageName) {
        if (!BuildConfig.ENFORCE_INTEGRITY) {
            return true;
        }
        try {
            byte[] expected = decodeSha256(BuildConfig.EXPECTED_CERT_SHA256);
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo;
            Signature[] currentSigners;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo = packageManager.getPackageInfo(
                        packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo signingInfo = packageInfo.signingInfo;
                if (signingInfo == null || signingInfo.hasMultipleSigners()) {
                    return reject("unexpected platform signer set");
                }
                currentSigners = signingInfo.getApkContentsSigners();
            } else {
                packageInfo = packageManager.getPackageInfo(
                        packageName,
                        PackageManager.GET_SIGNATURES);
                currentSigners = packageInfo.signatures;
            }
            if (currentSigners == null
                    || currentSigners.length != 1
                    || !matchesCertificate(currentSigners[0].toByteArray(), expected)) {
                return reject("platform signer mismatch");
            }

            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            byte[] directCertificate = readSigningBlockCertificate(applicationInfo.sourceDir);
            if (directCertificate == null
                    || !matchesCertificate(directCertificate, expected)) {
                return reject("APK signing block mismatch");
            }
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Integrity verification could not complete", error);
            return false;
        }
    }

    private static boolean reject(String reason) {
        Log.e(TAG, "Integrity verification rejected: " + reason);
        return false;
    }

    private static boolean matchesCertificate(byte[] certificate, byte[] expected)
            throws Exception {
        return MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(certificate),
                expected);
    }

    private static byte[] decodeSha256(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^0-9A-Fa-f]", "");
        if (normalized.length() != 64) {
            throw new IllegalArgumentException("Invalid signing certificate anchor");
        }
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            int offset = index * 2;
            result[index] = (byte) Integer.parseInt(
                    normalized.substring(offset, offset + 2),
                    16);
        }
        return result;
    }

    private static byte[] readSigningBlockCertificate(String apkPath) throws IOException {
        try (RandomAccessFile apk = new RandomAccessFile(apkPath, "r")) {
            long centralDirectoryOffset = findCentralDirectoryOffset(apk);
            if (centralDirectoryOffset < 32L) {
                throw new IOException("Invalid central directory offset");
            }

            apk.seek(centralDirectoryOffset - 24L);
            long blockSize = readLongLittleEndian(apk);
            byte[] magic = new byte[APK_SIG_BLOCK_MAGIC.length];
            apk.readFully(magic);
            if (!MessageDigest.isEqual(magic, APK_SIG_BLOCK_MAGIC)) {
                throw new IOException("APK signing block magic is absent");
            }
            if (blockSize < 24L || blockSize > centralDirectoryOffset - 8L) {
                throw new IOException("Invalid APK signing block size");
            }

            long blockStart = centralDirectoryOffset - blockSize - 8L;
            apk.seek(blockStart);
            if (readLongLittleEndian(apk) != blockSize) {
                throw new IOException("APK signing block sizes disagree");
            }

            long pairsEnd = centralDirectoryOffset - 24L;
            byte[] v2 = null;
            byte[] v3 = null;
            byte[] v31 = null;
            while (apk.getFilePointer() < pairsEnd) {
                long pairSize = readLongLittleEndian(apk);
                if (pairSize < 4L
                        || pairSize > Integer.MAX_VALUE
                        || pairSize > pairsEnd - apk.getFilePointer()) {
                    throw new IOException("Invalid APK signing pair size");
                }
                int id = readIntLittleEndian(apk);
                int valueSize = (int) pairSize - 4;
                byte[] value = new byte[valueSize];
                apk.readFully(value);
                if (id == APK_SIGNATURE_SCHEME_V31_BLOCK_ID) {
                    v31 = value;
                } else if (id == APK_SIGNATURE_SCHEME_V3_BLOCK_ID) {
                    v3 = value;
                } else if (id == APK_SIGNATURE_SCHEME_V2_BLOCK_ID) {
                    v2 = value;
                }
            }
            if (apk.getFilePointer() != pairsEnd) {
                throw new IOException("APK signing pairs do not end at the footer");
            }
            byte[] selected = v31 != null ? v31 : (v3 != null ? v3 : v2);
            return selected == null ? null : extractSingleSignerCertificate(selected);
        }
    }

    private static long findCentralDirectoryOffset(RandomAccessFile apk) throws IOException {
        long fileSize = apk.length();
        int tailSize = (int) Math.min(
                fileSize,
                ZIP_EOCD_MIN_SIZE + ZIP_MAX_COMMENT_SIZE);
        if (tailSize < ZIP_EOCD_MIN_SIZE) {
            throw new IOException("APK is too small");
        }
        byte[] tail = new byte[tailSize];
        apk.seek(fileSize - tailSize);
        apk.readFully(tail);
        ByteBuffer buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = tailSize - ZIP_EOCD_MIN_SIZE; offset >= 0; offset--) {
            if (buffer.getInt(offset) != 0x06054b50) {
                continue;
            }
            int commentSize = Short.toUnsignedInt(buffer.getShort(offset + 20));
            if (offset + ZIP_EOCD_MIN_SIZE + commentSize != tailSize) {
                continue;
            }
            return Integer.toUnsignedLong(buffer.getInt(offset + 16));
        }
        throw new IOException("ZIP end of central directory was not found");
    }

    private static byte[] extractSingleSignerCertificate(byte[] schemeBlock)
            throws IOException {
        ByteBuffer container = ByteBuffer.wrap(schemeBlock).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer signers = readLengthPrefixedSlice(container);
        if (container.hasRemaining()) {
            throw new IOException("Unexpected bytes after APK signer sequence");
        }
        byte[] certificate = null;
        int signerCount = 0;
        while (signers.hasRemaining()) {
            ByteBuffer signer = readLengthPrefixedSlice(signers);
            ByteBuffer signedData = readLengthPrefixedSlice(signer);
            readLengthPrefixedSlice(signedData); // digests
            ByteBuffer certificates = readLengthPrefixedSlice(signedData);
            ByteBuffer firstCertificate = readLengthPrefixedSlice(certificates);
            byte[] current = new byte[firstCertificate.remaining()];
            firstCertificate.get(current);
            certificate = current;
            signerCount++;
        }
        if (signerCount != 1) {
            throw new IOException("Unexpected APK signer count: " + signerCount);
        }
        return certificate;
    }

    private static ByteBuffer readLengthPrefixedSlice(ByteBuffer source) throws IOException {
        if (source.remaining() < 4) {
            throw new IOException("Length-prefixed field is truncated");
        }
        int size = source.getInt();
        if (size < 0 || size > source.remaining()) {
            throw new IOException("Invalid length-prefixed field size");
        }
        ByteBuffer result = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        result.limit(size);
        source.position(source.position() + size);
        return result;
    }

    private static long readLongLittleEndian(RandomAccessFile file) throws IOException {
        return Long.reverseBytes(file.readLong());
    }

    private static int readIntLittleEndian(RandomAccessFile file) throws IOException {
        return Integer.reverseBytes(file.readInt());
    }
}
