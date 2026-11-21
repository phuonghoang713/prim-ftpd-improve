package org.primftpd.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hashing of the password stored in preferences.
 * <div>
 * Current format: <code>pbkdf2-sha256$iterations$salt$hash</code> (PBKDF2 with HMAC-SHA256, random salt,
 * salt and hash base64 encoded).
 * </div>
 * <div>
 * Legacy format: base64 of a single SHA-512 over password and a salt which is the same for all installations.
 * Such hashes are still accepted, as the password is not known they cannot be converted.
 * They are replaced when the user sets the password again.
 * </div>
 */
public class EncryptionUtil
{
	protected static final Logger logger = LoggerFactory.getLogger(EncryptionUtil.class);

	static private final String LEGACY_SALT = "H§R&q}9";

	static final String PBKDF2_PREFIX = "pbkdf2-sha256$";
	static private final String HMAC_ALGO = "HmacSHA256";
	// a login must stay fast enough on slow devices, clients open several connections at once
	static private final int PBKDF2_ITERATIONS = 60_000;
	static private final int PBKDF2_MAX_ITERATIONS = 10_000_000;
	static private final int SALT_LENGTH = 16;
	static private final int HASH_LENGTH = 32;

	/**
	 * @return Hash of given password to be stored in preferences, null on error.
	 */
	public static String encrypt(String str)
	{
		if (str == null) {
			return null;
		}
		try {
			byte[] salt = new byte[SALT_LENGTH];
			new SecureRandom().nextBytes(salt);
			byte[] hash = pbkdf2(str, salt, PBKDF2_ITERATIONS, HASH_LENGTH);
			Base64.Encoder encoder = Base64.getEncoder();
			return PBKDF2_PREFIX + PBKDF2_ITERATIONS
					+ "$" + encoder.encodeToString(salt)
					+ "$" + encoder.encodeToString(hash);
		} catch (Exception e) {
			logger.error("could not encrypt", e);
		}
		return null;
	}

	/**
	 * @return true if given password matches the stored hash (current or legacy format).
	 */
	public static boolean matches(String password, String stored)
	{
		if (password == null || stored == null || stored.isEmpty()) {
			return false;
		}
		try {
			if (stored.startsWith(PBKDF2_PREFIX)) {
				String[] parts = stored.substring(PBKDF2_PREFIX.length()).split("\\$");
				if (parts.length != 3) {
					return false;
				}
				int iterations = Integer.parseInt(parts[0]);
				if (iterations < 1 || iterations > PBKDF2_MAX_ITERATIONS) {
					return false;
				}
				Base64.Decoder decoder = Base64.getDecoder();
				byte[] salt = decoder.decode(parts[1]);
				byte[] expected = decoder.decode(parts[2]);
				if (expected.length == 0) {
					return false;
				}
				byte[] actual = pbkdf2(password, salt, iterations, expected.length);
				return MessageDigest.isEqual(expected, actual);
			}
			String legacy = encryptLegacy(password);
			return legacy != null && MessageDigest.isEqual(
					stored.getBytes(StandardCharsets.UTF_8),
					legacy.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			logger.error("could not check password", e);
			return false;
		}
	}

	static String encryptLegacy(String str)
	{
		try {
			MessageDigest cipher = MessageDigest.getInstance("SHA-512");
			byte[] encrypted = cipher.digest((str + LEGACY_SALT).getBytes(StandardCharsets.UTF_8));
			byte[] base64 = org.apache.mina.util.Base64.encodeBase64(encrypted);
			return new String(base64, StandardCharsets.UTF_8);
		} catch (NoSuchAlgorithmException e) {
			logger.error("could not encrypt", e);
		}
		return null;
	}

	/**
	 * PBKDF2 (RFC 8018) with HMAC-SHA256. Implemented here instead of using a SecretKeyFactory as providers
	 * differ in how they convert passwords with non ascii characters to bytes. Password is used as UTF-8.
	 */
	static byte[] pbkdf2(String password, byte[] salt, int iterations, int length) throws Exception
	{
		Mac mac = Mac.getInstance(HMAC_ALGO);
		mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
		int macLength = mac.getMacLength();
		byte[] result = new byte[length];
		int blocks = (length + macLength - 1) / macLength;
		for (int block = 1; block <= blocks; block++) {
			mac.update(salt);
			mac.update(new byte[] {
					(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
			byte[] u = mac.doFinal();
			byte[] t = u.clone();
			for (int i = 1; i < iterations; i++) {
				u = mac.doFinal(u);
				for (int j = 0; j < t.length; j++) {
					t[j] ^= u[j];
				}
			}
			int offset = (block - 1) * macLength;
			System.arraycopy(t, 0, result, offset, Math.min(macLength, length - offset));
		}
		return result;
	}
}
