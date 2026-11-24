package com.paynux.setup;

/**
 * Pinned description of the official Ubuntu Base rootfs that Setup downloads.
 *
 * The SHA-256 below was taken from the official SHA256SUMS file whose GPG
 * signature was verified (tools/verify-rootfs.sh) against the
 * "Ubuntu CD Image Automatic Signing Key (2012)":
 * 8439 38DF 228D 22F7 B374 2BC0 D94A A3F0 EFE2 1092
 *
 * To update the rootfs: change VERSION/FILE_NAME/SHA256 together, then re-run
 * tools/verify-rootfs.sh. Never edit SHA256 by hand without that check.
 */
public final class RootfsSource {
    private RootfsSource() {}

    public static final String VERSION = "24.04.5";
    public static final String FILE_NAME = "ubuntu-base-24.04.5-base-arm64.tar.gz";
    public static final String BASE_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/";
    public static final String URL = BASE_URL + FILE_NAME;

    /** Lower-case hex SHA-256 of the .tar.gz file. */
    public static final String SHA256 =
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2";

    /** Fingerprint of the key that signs SHA256SUMS (documentation only). */
    public static final String GPG_FINGERPRINT =
            "843938DF228D22F7B3742BC0D94AA3F0EFE21092";

    /** Setup refuses to start the download below this much free space. */
    public static final long MIN_FREE_BYTES = 400L * 1024L * 1024L;
}
