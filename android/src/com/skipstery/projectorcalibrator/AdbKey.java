package com.skipstery.projectorcalibrator;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.io.ByteArrayInputStream;

/**
 * The app's own ADB client key. The launcher pairs it once from the computer and streams the
 * private key into the app through the shell-only provider. ADB over TLS identifies a client only
 * by the public key in its certificate, so a self-signed certificate is enough.
 */
final class AdbKey {
  static final int MAX_PEM_BYTES = 8192;
  final PrivateKey privateKey;
  final X509Certificate certificate;

  private AdbKey(PrivateKey privateKey, X509Certificate certificate) {
    this.privateKey = privateKey;
    this.certificate = certificate;
  }

  static File file(Context context) {
    return new File(context.getNoBackupFilesDir(), "adbkey");
  }

  static boolean exists(Context context) {
    return file(context).isFile();
  }

  static AdbKey load(Context context) throws Exception {
    return parse(Files.readAllBytes(file(context).toPath()));
  }

  /** Validates the PEM before atomically replacing the stored key. */
  static void store(Context context, byte[] pem) throws Exception {
    parse(pem);
    File target = file(context);
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

  static void delete(Context context) {
    file(context).delete();
  }

  static AdbKey parse(byte[] pem) throws Exception {
    if (pem.length > MAX_PEM_BYTES) throw new IllegalArgumentException("ADB key is too large");
    String text = new String(pem, StandardCharsets.US_ASCII);
    String begin = "-----BEGIN PRIVATE KEY-----", end = "-----END PRIVATE KEY-----";
    int start = text.indexOf(begin), stop = text.indexOf(end);
    if (start < 0 || stop < start) throw new IllegalArgumentException("Expected a PKCS#8 ADB key");
    byte[] der = Base64.decode(text.substring(start + begin.length(), stop), Base64.DEFAULT);
    KeyFactory factory = KeyFactory.getInstance("RSA");
    PrivateKey key = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
    if (!(key instanceof RSAPrivateCrtKey))
      throw new IllegalArgumentException("Expected an RSA ADB key");
    RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) key;
    if (rsa.getModulus().bitLength() != 2048)
      throw new IllegalArgumentException("ADB keys are 2048-bit RSA");
    PublicKey pub =
        factory.generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
    return new AdbKey(key, selfSigned(key, pub));
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
