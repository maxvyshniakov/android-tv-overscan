package com.skipstery.projectorcalibrator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The app's own 2048-bit RSA ADB client key. It can be generated on the device for legacy ADB on
 * port 5555 or paired and imported once by the desktop launcher for Wireless debugging (TLS).
 * Exposes both a self-signed X.509 certificate for TLS and the Android mincrypt RSAPublicKey
 * payload for legacy AUTH_RSAPUBLICKEY.
 */
final class AdbKey {
  static final int MAX_PEM_BYTES = 8192;
  static final int MINCRYPT_BYTES = 524;
  static final int MODULUS_WORDS = 64;
  static final int MODULUS_BYTES = MODULUS_WORDS * 4;
  static final String DEFAULT_IDENTITY = "android-tv-overscan@local";

  final PrivateKey privateKey;
  final X509Certificate certificate;
  final byte[] publicKey;

  private AdbKey(PrivateKey privateKey, X509Certificate certificate, byte[] publicKey) {
    this.privateKey = privateKey;
    this.certificate = certificate;
    this.publicKey = publicKey;
  }

  static boolean exists(File file) {
    return file != null && file.isFile();
  }

  static AdbKey load(File file) throws Exception {
    return parse(Files.readAllBytes(file.toPath()));
  }

  static AdbKey generate() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return parse(encodePem(generator.generateKeyPair().getPrivate()));
  }

  static AdbKey loadOrGenerate(File file) throws Exception {
    if (exists(file)) return load(file);
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    byte[] pem = encodePem(generator.generateKeyPair().getPrivate());
    store(file, pem);
    return parse(pem);
  }

  static byte[] encodePem(PrivateKey key) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded());
    String pem = "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    return pem.getBytes(StandardCharsets.US_ASCII);
  }

  /** Validates the PEM before atomically replacing the stored key. */
  static void store(File target, byte[] pem) throws Exception {
    parse(pem);
    File parent = target.getParentFile();
    if (parent != null && !parent.exists()) parent.mkdirs();
    File temporary = new File(target.getPath() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(temporary)) {
      out.write(pem);
      out.getFD().sync();
    }
    if (!temporary.renameTo(target)) {
      temporary.delete();
      throw new IOException("Could not save the ADB key");
    }
  }

  static void delete(File file) {
    if (file != null) file.delete();
  }

  static AdbKey parse(byte[] pem) throws Exception {
    if (pem.length > MAX_PEM_BYTES) throw new IllegalArgumentException("ADB key is too large");
    String text = new String(pem, StandardCharsets.US_ASCII);
    String begin = "-----BEGIN PRIVATE KEY-----", end = "-----END PRIVATE KEY-----";
    int start = text.indexOf(begin), stop = text.indexOf(end);
    if (start < 0 || stop < start) throw new IllegalArgumentException("Expected a PKCS#8 ADB key");
    byte[] der = Base64.getMimeDecoder().decode(text.substring(start + begin.length(), stop));
    KeyFactory factory = KeyFactory.getInstance("RSA");
    PrivateKey key = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
    if (!(key instanceof RSAPrivateCrtKey))
      throw new IllegalArgumentException("Expected an RSA ADB key");
    RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) key;
    if (rsa.getModulus().bitLength() != 2048)
      throw new IllegalArgumentException("ADB keys are 2048-bit RSA");
    PublicKey pub =
        factory.generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
    byte[] legacyPublicKey =
        formatPublicKey(rsa.getModulus(), rsa.getPublicExponent(), DEFAULT_IDENTITY);
    return new AdbKey(key, selfSigned(key, pub), legacyPublicKey);
  }

  /**
   * Encodes an RSA public key into Android's 524-byte little-endian mincrypt RSAPublicKey struct:
   * {@code uint32_t len=64, uint32_t n0inv, uint32_t n[64], uint32_t rr[64], uint32_t exponent}.
   */
  static byte[] mincryptPublicKey(BigInteger modulus, BigInteger exponent) {
    if (modulus.bitLength() != 2048)
      throw new IllegalArgumentException("ADB keys are 2048-bit RSA");
    BigInteger word = BigInteger.ONE.shiftLeft(32);
    int n0inv = word.subtract(modulus.modInverse(word)).intValue();
    BigInteger r = BigInteger.ONE.shiftLeft(2048);
    BigInteger rr = r.multiply(r).mod(modulus);
    ByteBuffer buffer = ByteBuffer.allocate(MINCRYPT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(MODULUS_WORDS);
    buffer.putInt(n0inv);
    buffer.put(littleEndian256(modulus));
    buffer.put(littleEndian256(rr));
    buffer.putInt(exponent.intValue());
    return buffer.array();
  }

  /** Formats the mincrypt public key as {@code <base64> <identity>\0} in US-ASCII for adbd. */
  static byte[] formatPublicKey(BigInteger modulus, BigInteger exponent, String identity) {
    String base64 = Base64.getEncoder().encodeToString(mincryptPublicKey(modulus, exponent));
    return (base64 + " " + identity + "\0").getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] littleEndian256(BigInteger value) {
    byte[] big = value.toByteArray();
    byte[] little = new byte[MODULUS_BYTES];
    int offset = big.length > 0 && big[0] == 0 ? 1 : 0;
    int count = big.length - offset;
    if (count > MODULUS_BYTES) throw new IllegalArgumentException("Integer exceeds 2048 bits");
    for (int i = 0; i < count; i++) {
      little[i] = big[big.length - 1 - i];
    }
    return little;
  }

  // Minimal DER encoder for one self-signed X.509 v3 certificate.
  private static final byte[] SHA256_RSA = {
    0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b
  };
  private static final byte[] COMMON_NAME = {0x55, 0x04, 0x03};

  private static X509Certificate selfSigned(PrivateKey key, PublicKey pub) throws Exception {
    byte[] algorithm = der(0x30, der(0x06, SHA256_RSA), der(0x05));
    byte[] name =
        der(
            0x30,
            der(
                0x31,
                der(0x30, der(0x06, COMMON_NAME), der(0x0c, "adb".getBytes(StandardCharsets.UTF_8)))));
    byte[] validity =
        der(
            0x30,
            der(0x17, "000101000000Z".getBytes(StandardCharsets.US_ASCII)),
            der(0x18, "99991231235959Z".getBytes(StandardCharsets.US_ASCII)));
    byte[] tbs =
        der(
            0x30,
            der(0xa0, der(0x02, BigInteger.valueOf(2).toByteArray())),
            der(0x02, BigInteger.ONE.toByteArray()),
            algorithm,
            name,
            validity,
            name,
            pub.getEncoded());
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(key);
    signer.update(tbs);
    byte[] signature = signer.sign();
    byte[] bits = new byte[signature.length + 1];
    System.arraycopy(signature, 0, bits, 1, signature.length);
    byte[] certificate = der(0x30, tbs, algorithm, der(0x03, bits));
    return (X509Certificate)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(certificate));
  }

  static byte[] der(int tag, byte[]... parts) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (byte[] part : parts) body.write(part, 0, part.length);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    int length = body.size();
    if (length < 0x80) {
      out.write(length);
    } else {
      int bytes = length > 0xffff ? 3 : length > 0xff ? 2 : 1;
      out.write(0x80 | bytes);
      for (int i = bytes - 1; i >= 0; i--) out.write(length >>> (8 * i));
    }
    byte[] content = body.toByteArray();
    out.write(content, 0, content.length);
    return out.toByteArray();
  }
}
