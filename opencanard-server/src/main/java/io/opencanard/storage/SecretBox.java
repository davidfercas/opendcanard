package io.opencanard.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the secrets kept in the metadata database (AES-256-GCM), so a copy of {@code metadata.duckdb}
 * alone does not reveal them. JDK only.
 * <p>
 * Stored form: {@code v1:} + Base64(12-byte nonce + ciphertext + tag). The <i>context</i> (for storages,
 * {@code login/name}) is authenticated with the data: a secret copied to another row does not decrypt.
 * <p>
 * The key comes from the property {@code opencanard.storage.key} when set, otherwise from the file
 * {@code conf/storage.key}, created with random content on first use. Without the key the secrets cannot be read.
 */
public final class SecretBox {

	private static final String PREFIX = "v1:";
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public SecretBox(byte[] keyMaterial) {
		try {
			this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(keyMaterial), "AES");
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * @param configured value of {@code opencanard.storage.key}, may be null or blank
	 * @param keyFile    where the generated key lives when nothing is configured
	 */
	public static SecretBox open(String configured, Path keyFile) throws IOException {
		if (configured != null && !configured.isBlank()) {
			return new SecretBox(configured.getBytes(StandardCharsets.UTF_8));
		}
		if (!Files.exists(keyFile)) {
			byte[] fresh = new byte[32];
			new SecureRandom().nextBytes(fresh);
			try {
				Files.write(keyFile, Base64.getEncoder().encode(fresh), java.nio.file.StandardOpenOption.CREATE_NEW);
				try {
					Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
				} catch (UnsupportedOperationException ignored) {
					// not a POSIX file system (Windows)
				}
			} catch (FileAlreadyExistsException raced) {
				// another start created it first: use that one
			}
		}
		return new SecretBox(Files.readAllBytes(keyFile));
	}

	public String encrypt(String plain, String context) {
		try {
			byte[] nonce = new byte[NONCE_BYTES];
			random.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			byte[] all = new byte[nonce.length + encrypted.length];
			System.arraycopy(nonce, 0, all, 0, nonce.length);
			System.arraycopy(encrypted, 0, all, nonce.length, encrypted.length);
			return PREFIX + Base64.getEncoder().encodeToString(all);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Cannot encrypt the secret", e);
		}
	}

	/** @throws IllegalStateException if the key is not the one that encrypted it, or the data or context changed */
	public String decrypt(String stored, String context) {
		if (stored == null || !stored.startsWith(PREFIX)) {
			throw new IllegalStateException("The stored secret has an unknown format");
		}
		try {
			byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
			cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
			return new String(cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("The stored secret cannot be decrypted: the storage key changed or the data was altered", e);
		}
	}
}
